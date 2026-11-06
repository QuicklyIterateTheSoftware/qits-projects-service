package eu.wohlben.qits.entities.control;

import java.time.Instant;
import java.util.List;

/**
 * <b>Needs a transition turned from unmet to met</b> (qits-1903), fired on the CDI bus by {@link
 * NeedService#announceMet} inside the read of who waits on the moved items, so an observer {@code
 * during = AFTER_SUCCESS} — {@code WorkNeedAnnouncer} in {@code service} — publishes one {@code
 * WorkNeedMet} per entry once that read has committed. The ids are bare: the qualified ids are
 * rendered where the project slug can be read.
 */
public record NeedsMet(List<Met> met) {

  public NeedsMet {
    met = met == null ? List.of() : List.copyOf(met);
  }

  /**
   * One need that is met now and was not before the move.
   *
   * @param projectId the project of both items
   * @param entityId the item that waits
   * @param number its per-project number
   * @param neededEntityId the item that moved
   * @param neededNumber its per-project number
   * @param status the status the need names
   * @param neededStatus the status the needed item just reached
   * @param allMet whether the waiting item has no unmet need left
   * @param metAt when the move committed
   */
  public record Met(
      String projectId,
      String entityId,
      long number,
      String neededEntityId,
      long neededNumber,
      String status,
      String neededStatus,
      boolean allMet,
      Instant metAt) {}
}
