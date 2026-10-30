package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.Idea;
import eu.wohlben.qits.entities.entity.IdeaStatus;
import eu.wohlben.qits.entities.persistence.IdeaRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * <b>An idea's status, derived from the work that names it, and stored</b> (qits-1387). The rule is
 * {@link #derive}, the one place it is written:
 *
 * <ul>
 *   <li>DROPPED work is not counted at all;
 *   <li>no counted work → {@link IdeaStatus#PLANNED};
 *   <li>every counted item DONE → {@link IdeaStatus#DONE} (VERIFIED is not enough);
 *   <li>otherwise → {@link IdeaStatus#QUEUED}.
 * </ul>
 *
 * <p><b>Recomputed in the same transaction as every write that can change it</b>, by the writer
 * itself calling {@link #recompute} inside its hold: {@code WorkEntityService}'s create, edit, every
 * lifecycle move (the carried descendants and a supersede's successor included) and delete, and
 * {@code EntityTransitionService}'s whole-state write. Each answers the {@link IdeaChange}s it made,
 * which the writer announces after its hold ({@link IdeaAnnouncements}). A boot reconcile ({@link
 * #reconcile}) repairs whatever drift a write made outside those paths.
 */
@ApplicationScoped
public class IdeaStatusRecompute {

  @Inject IdeaRepository ideas;

  @Inject WorkEntityRepository entities;

  @Inject WritePatience writes;

  /** The status an idea has when its work stands at {@code workStatuses}. */
  public static IdeaStatus derive(Collection<String> workStatuses) {
    int counted = 0;
    int done = 0;
    for (String status : workStatuses) {
      if (status == null || EntityStatus.DROPPED.name().equals(status)) {
        continue;
      }
      counted++;
      if (EntityStatus.DONE.name().equals(status)) {
        done++;
      }
    }
    if (counted == 0) {
      return IdeaStatus.PLANNED;
    }
    return done == counted ? IdeaStatus.DONE : IdeaStatus.QUEUED;
  }

  /** The status {@code ideaId} should have now, read from the work that names it. */
  public IdeaStatus derivedFor(String ideaId) {
    return derive(entities.statusesInspiredBy(ideaId));
  }

  /**
   * <b>Inside a write's transaction</b>: re-derives each named idea (nulls and repeats ignored),
   * writes the ones whose stored status differs, and answers an {@link IdeaChange.Kind#UPDATED} for
   * each of those — for the caller to announce after its hold. An id naming no idea is skipped.
   */
  public List<IdeaChange> recompute(Collection<String> ideaIds, String changedBy) {
    Set<String> distinct = new LinkedHashSet<>();
    for (String id : ideaIds) {
      if (id != null) {
        distinct.add(id);
      }
    }
    List<IdeaChange> changed = new ArrayList<>();
    for (String id : distinct) {
      Idea idea = ideas.findById(id);
      if (idea == null) {
        continue;
      }
      IdeaStatus derived = derivedFor(id);
      if (derived != idea.status) {
        String before = idea.status == null ? null : idea.status.name();
        idea.status = derived;
        ideas.getEntityManager().flush();
        changed.add(IdeaChange.of(IdeaChange.Kind.UPDATED, idea, before, changedBy));
      }
    }
    return changed;
  }

  /** {@link #recompute} over the pairs of a write that may have moved an idea, before and after. */
  public List<IdeaChange> recompute(String before, String after, String changedBy) {
    if (before == null && after == null) {
      return List.of();
    }
    List<String> ids = new ArrayList<>(2);
    ids.add(before);
    if (!Objects.equals(before, after)) {
      ids.add(after);
    }
    return recompute(ids, changedBy);
  }

  /** One idea whose stored status the reconcile found wrong, and the status it was given. */
  public record Drift(String ideaId, String projectId, String stored, String derived) {}

  /**
   * <b>The boot reconcile</b>: every idea re-derived in one write, each whose stored status differs
   * fixed. Answers the drifts (for the caller's WARNs) and the changes (for its announcement).
   */
  public Reconciled reconcile(String changedBy) {
    List<Drift> drifts = new ArrayList<>();
    List<IdeaChange> changes = new ArrayList<>();
    writes.run(
        "idea status reconcile",
        () -> {
          drifts.clear();
          changes.clear();
          for (Idea idea : ideas.listEvery()) {
            IdeaStatus derived = derivedFor(idea.id);
            if (derived != idea.status) {
              String before = idea.status == null ? null : idea.status.name();
              drifts.add(new Drift(idea.id, idea.projectId, before, derived.name()));
              idea.status = derived;
              changes.add(IdeaChange.of(IdeaChange.Kind.UPDATED, idea, before, changedBy));
            }
          }
        });
    return new Reconciled(List.copyOf(drifts), List.copyOf(changes));
  }

  /** What {@link #reconcile} fixed. */
  public record Reconciled(List<Drift> drifts, List<IdeaChange> changes) {}
}
