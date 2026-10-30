package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.Idea;
import eu.wohlben.qits.entities.entity.IdeaStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.persistence.IdeaRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * <b>Ideas: create, read, edit, archive and unarchive</b> (qits-1387, epic qits-888) — and the rule
 * for which work may name one. There is no delete: an idea is archived with a reason, and the
 * archive is reversible.
 *
 * <p>Every write is one {@link WritePatience} attempt, as {@link WorkEntityService}'s are: the
 * validations that need no row run before the hold, so a bad body is a 400 on the first attempt; the
 * ones that read rows (the {@code enabledBy} target, the ideas this one enables) run inside it. The
 * change is announced once, after the hold ({@link IdeaAnnouncements}).
 *
 * <h2>The rules, each a 400</h2>
 *
 * <ul>
 *   <li><b>title</b>: required, and held to {@link AcceptanceCriteria}' item rules — no line break,
 *       at most one '.', fewer than 20 whitespace characters;
 *   <li><b>description</b>: optional; at most {@value #MAX_PARAGRAPHS} paragraphs (blocks separated
 *       by one or more blank lines, after trimming) and at most {@value #MAX_DESCRIPTION} characters;
 *   <li><b>enabledBy</b>: one idea of the same project, one layer deep — not the idea itself, not
 *       an idea that is itself enabled by another, and never set on an idea that already enables
 *       others. An archived target is allowed;
 *   <li><b>archive</b>: a reason is required. It does not change the status.
 * </ul>
 */
@ApplicationScoped
public class IdeaService {

  /** The archetypes that may name an idea as their {@code inspiredBy}. */
  public static final Set<Archetype> CARRIERS =
      EnumSet.of(Archetype.EPIC, Archetype.TICKET, Archetype.CAMPAIGN);

  /** At most this many paragraphs in a description. */
  public static final int MAX_PARAGRAPHS = 3;

  /** At most this many characters in a description. */
  public static final int MAX_DESCRIPTION = 600;

  /** One or more blank lines: what separates two paragraphs. */
  private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\R[ \\t]*\\R(?:[ \\t]*\\R)*");

  @Inject IdeaRepository ideas;

  @Inject WorkEntityRepository entities;

  @Inject ReadPatience reads;

  @Inject WritePatience writes;

  @Inject IdeaAnnouncements announcements;

  /** What an edit changes: a null leaves a field alone, a {@code clear*} flag empties it. */
  public record IdeaEdit(
      String title, String description, boolean clearDescription, String enabledBy, boolean clearEnabledBy) {

    boolean isEmpty() {
      return title == null
          && description == null
          && !clearDescription
          && enabledBy == null
          && !clearEnabledBy;
    }
  }

  // --- reads ----------------------------------------------------------------------------------

  /** The idea, or a 404. */
  public Idea get(String id) {
    return reads.hold("idea read", () -> find(id));
  }

  /**
   * A project's ideas, oldest first. {@code includeArchived} false leaves the archived out; {@code
   * status} null means every status.
   */
  public List<Idea> list(String projectId, boolean includeArchived, IdeaStatus status) {
    return reads.hold(
        "idea list", () -> ideas.listByProject(projectId, includeArchived, status));
  }

  /** The work naming {@code ideaId} as its inspiration, every status, oldest first. */
  public List<WorkEntity> inspired(String ideaId) {
    return reads.hold("idea inspired", () -> entities.listInspiredBy(ideaId));
  }

  /** The ideas naming {@code ideaId} as their {@code enabledBy}, oldest first. */
  public List<Idea> enables(String ideaId) {
    return reads.hold("idea enables", () -> ideas.listEnabledBy(ideaId));
  }

  /** The idea by id, or empty — no 404, for a reader decorating an answer. */
  public Optional<Idea> lookup(String id) {
    if (id == null) {
      return Optional.empty();
    }
    return reads.hold("idea lookup", () -> Optional.ofNullable(ideas.findById(id)));
  }

  // --- writes ---------------------------------------------------------------------------------

  /** A new idea of {@code projectId}, PLANNED. */
  public Idea create(
      String projectId, String title, String description, String enabledBy, String changedBy) {
    Validations.requireText(projectId, "projectId");
    requireTitle(title);
    String body = normaliseDescription(description);
    List<IdeaChange> changes = new ArrayList<>(1);
    Idea created =
        writes.hold(
            "idea create",
            () -> {
              changes.clear();
              Idea idea = new Idea();
              idea.id = UUID.randomUUID().toString();
              idea.projectId = projectId;
              idea.title = title;
              idea.description = body;
              if (enabledBy != null) {
                requireEnabler(idea, enabledBy);
                idea.enabledBy = enabledBy;
              }
              idea.status = IdeaStatus.PLANNED;
              idea.archived = false;
              ideas.persist(idea);
              ideas.getEntityManager().flush();
              changes.add(IdeaChange.of(IdeaChange.Kind.CREATED, idea, null, changedBy));
              return idea;
            });
    announcements.announce(changes);
    return created;
  }

  /** An edit of {@code id}: see {@link IdeaEdit}. At least one field must be named. */
  public Idea update(String id, IdeaEdit edit, String changedBy) {
    if (edit == null || edit.isEmpty()) {
      throw new BadRequestException("an idea edit must name at least one property");
    }
    if (edit.title() != null) {
      requireTitle(edit.title());
    }
    String body = edit.description() == null ? null : normaliseDescription(edit.description());
    List<IdeaChange> changes = new ArrayList<>(1);
    Idea updated =
        writes.hold(
            "idea update",
            () -> {
              changes.clear();
              Idea idea = find(id);
              if (edit.title() != null) {
                idea.title = edit.title();
              }
              if (edit.clearDescription()) {
                idea.description = null;
              } else if (edit.description() != null) {
                idea.description = body;
              }
              if (edit.clearEnabledBy()) {
                idea.enabledBy = null;
              } else if (edit.enabledBy() != null && !edit.enabledBy().equals(idea.enabledBy)) {
                requireEnabler(idea, edit.enabledBy());
                idea.enabledBy = edit.enabledBy();
              }
              ideas.getEntityManager().flush();
              changes.add(IdeaChange.of(IdeaChange.Kind.UPDATED, idea, null, changedBy));
              return idea;
            });
    announcements.announce(changes);
    return updated;
  }

  /** Archives {@code id} with {@code reason}; the status stays as it was. Again is a re-archive. */
  public Idea archive(String id, String reason, String changedBy) {
    if (reason == null || reason.isBlank()) {
      throw new BadRequestException("reason is required to archive an idea");
    }
    List<IdeaChange> changes = new ArrayList<>(1);
    Idea archived =
        writes.hold(
            "idea archive",
            () -> {
              changes.clear();
              Idea idea = find(id);
              idea.archived = true;
              idea.archiveReason = reason.strip();
              idea.archivedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
              ideas.getEntityManager().flush();
              changes.add(IdeaChange.of(IdeaChange.Kind.ARCHIVED, idea, null, changedBy));
              return idea;
            });
    announcements.announce(changes);
    return archived;
  }

  /** Takes {@code id} out of the archive: archived, its reason and its time are cleared. */
  public Idea unarchive(String id, String changedBy) {
    List<IdeaChange> changes = new ArrayList<>(1);
    Idea unarchived =
        writes.hold(
            "idea unarchive",
            () -> {
              changes.clear();
              Idea idea = find(id);
              boolean was = idea.archived;
              idea.archived = false;
              idea.archiveReason = null;
              idea.archivedAt = null;
              ideas.getEntityManager().flush();
              if (was) {
                changes.add(IdeaChange.of(IdeaChange.Kind.UNARCHIVED, idea, null, changedBy));
              }
              return idea;
            });
    announcements.announce(changes);
    return unarchived;
  }

  // --- the work side --------------------------------------------------------------------------

  /**
   * <b>Whether a row of {@code archetype} in {@code projectId} may name {@code ideaId}</b>, or a
   * 400: the archetype must be one of {@link #CARRIERS}, and the idea must exist and be of the same
   * project. An archived idea may be named. Called inside the work write's transaction.
   */
  public void requireInspirable(Archetype archetype, String ideaId, String projectId) {
    requireCarrier(archetype);
    Idea idea = ideaId == null ? null : ideas.findById(ideaId);
    if (idea == null) {
      throw new BadRequestException("inspiredBy names no idea: " + ideaId);
    }
    if (!Objects.equals(idea.projectId, projectId)) {
      throw new BadRequestException(
          "inspiredBy must name an idea of the same project: " + ideaId + " is another project's");
    }
  }

  /** A 400 unless {@code archetype} may carry an {@code inspiredBy}. */
  public static void requireCarrier(Archetype archetype) {
    if (!CARRIERS.contains(archetype)) {
      throw new BadRequestException(
          "inspiredBy is not written on a "
              + archetype
              + ": only an EPIC, a TICKET or a CAMPAIGN is inspired by an idea");
    }
  }

  // --- the rules ------------------------------------------------------------------------------

  /** The title rule: required, one line, at most one '.', fewer than 20 whitespace characters. */
  public static void requireTitle(String title) {
    if (title == null || title.isBlank()) {
      throw new BadRequestException("title is required");
    }
    AcceptanceCriteria.refusal(title, "title")
        .ifPresent(why -> {
          throw new BadRequestException("title " + why);
        });
  }

  /**
   * The description rule: null or blank is none; otherwise at most {@value #MAX_DESCRIPTION}
   * characters and {@value #MAX_PARAGRAPHS} paragraphs. Answers the text to store, trimmed.
   */
  public static String normaliseDescription(String description) {
    if (description == null || description.isBlank()) {
      return null;
    }
    String trimmed = description.strip();
    int length = trimmed.codePointCount(0, trimmed.length());
    if (length > MAX_DESCRIPTION) {
      throw new BadRequestException(
          "description has "
              + length
              + " characters; at most "
              + MAX_DESCRIPTION
              + " keeps an idea short");
    }
    int paragraphs = paragraphs(trimmed);
    if (paragraphs > MAX_PARAGRAPHS) {
      throw new BadRequestException(
          "description has "
              + paragraphs
              + " paragraphs; at most "
              + MAX_PARAGRAPHS
              + " (a paragraph is a block separated by a blank line)");
    }
    return trimmed;
  }

  /** The paragraphs of a trimmed text: its blocks between one or more blank lines. */
  static int paragraphs(String trimmed) {
    int count = 0;
    for (String block : PARAGRAPH_BREAK.split(trimmed)) {
      if (!block.isBlank()) {
        count++;
      }
    }
    return count;
  }

  /**
   * The {@code enabledBy} rule, inside the write: {@code targetId} names an idea of {@code idea}'s
   * project, not {@code idea} itself, one with no {@code enabledBy} of its own, and {@code idea}
   * enables no other idea.
   */
  private void requireEnabler(Idea idea, String targetId) {
    if (targetId.isBlank()) {
      throw new BadRequestException("enabledBy must not be blank; null clears it");
    }
    if (targetId.equals(idea.id)) {
      throw new BadRequestException("an idea cannot be enabled by itself");
    }
    Idea target = ideas.findById(targetId);
    if (target == null) {
      throw new BadRequestException("enabledBy names no idea: " + targetId);
    }
    if (!Objects.equals(target.projectId, idea.projectId)) {
      throw new BadRequestException(
          "enabledBy must name an idea of the same project: " + targetId + " is another project's");
    }
    if (target.enabledBy != null) {
      throw new BadRequestException(
          "enabledBy is one layer deep: idea "
              + targetId
              + " is itself enabled by "
              + target.enabledBy);
    }
    if (idea.id != null && ideas.enablesAny(idea.id)) {
      throw new BadRequestException(
          "enabledBy is one layer deep: idea "
              + idea.id
              + " already enables other ideas, so it is not enabled by one");
    }
  }

  private Idea find(String id) {
    Idea idea = id == null ? null : ideas.findById(id);
    if (idea == null) {
      throw new NotFoundException("Idea not found: " + id);
    }
    return idea;
  }
}
