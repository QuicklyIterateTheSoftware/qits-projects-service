package eu.wohlben.qits.entities.milestone;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Milestones a write met or un-met</b> (epic qits-615): what {@link MilestoneDates#recompute}
 * answers, and what an authoring write fires on the CDI bus inside its own transaction ({@link
 * MilestoneService#announce}) so the service layer's announcer — observing {@code during =
 * AFTER_SUCCESS} — publishes {@code MilestoneMet} / {@code MilestoneUnmet} only once the write has
 * committed. This module's own record, because it depends on no event vocabulary.
 *
 * <p>The bus listener does not go through the CDI event: it publishes what {@link
 * MilestoneEvaluator#observe} answers itself, after its own {@code epics} transaction committed.
 */
public record MilestonesChanged(List<Met> met, List<Unmet> unmet) {

  /** A milestone came to hold: {@code groupId} is the holding group whose date it took. */
  public record Met(
      String milestoneId, String projectId, String displayName, String groupId, Instant satisfiedAt) {}

  /** A milestone stopped holding, at {@code at}. */
  public record Unmet(String milestoneId, String projectId, String displayName, Instant at) {}

  public MilestonesChanged {
    met = met == null ? List.of() : List.copyOf(met);
    unmet = unmet == null ? List.of() : List.copyOf(unmet);
  }

  /** Nothing changed. */
  public static MilestonesChanged none() {
    return new MilestonesChanged(List.of(), List.of());
  }

  public boolean isEmpty() {
    return met.isEmpty() && unmet.isEmpty();
  }

  /** Both, in order — {@code this} first. */
  public MilestonesChanged and(MilestonesChanged other) {
    if (other == null || other.isEmpty()) {
      return this;
    }
    if (isEmpty()) {
      return other;
    }
    List<Met> allMet = new ArrayList<>(met);
    allMet.addAll(other.met());
    List<Unmet> allUnmet = new ArrayList<>(unmet);
    allUnmet.addAll(other.unmet());
    return new MilestonesChanged(allMet, allUnmet);
  }

  /** The ids of the milestones that came to hold. */
  public List<String> metIds() {
    return met.stream().map(Met::milestoneId).toList();
  }
}
