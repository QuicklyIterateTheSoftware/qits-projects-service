package eu.wohlben.qits.projects.control;

import java.util.List;
import java.util.Optional;

/**
 * <b>qits-maintenance's automation kinds catalogue</b> — the one read {@code
 * approval/AutomationContentSource} makes (epic qits-1038, task qits-1046), as a port in the house
 * shape: the kinds are qits-maintenance's to declare, this service keeps no kind table, and the one
 * implementation is an HTTP client in {@code service/…/maintenancehost/}.
 *
 * <p>No caching here: the source keeps the last good answer (five minutes fresh, and the fallback
 * while qits-maintenance is unreachable), so an implementation is one plain read.
 */
public interface AutomationKinds {

  /**
   * One release-request automation kind, as the catalogue answers it.
   *
   * @param kind its id, e.g. {@code screenshot-baselines}
   * @param label what a person reads, e.g. {@code Screenshot baselines}
   * @param committablePaths the git pathspecs a run of it may commit — {@code :(glob)…} or plain
   *     prefixes ({@code approval/GitPathspec})
   * @param approvalRoles the approval roles a fold it wrote into needs ({@code person}, {@code agent},
   *     …); empty for a kind that needs no approval
   */
  record Kind(
      String kind, String label, List<String> committablePaths, List<String> approvalRoles) {

    public Kind {
      committablePaths = committablePaths == null ? List.of() : List.copyOf(committablePaths);
      approvalRoles = approvalRoles == null ? List.of() : List.copyOf(approvalRoles);
    }
  }

  /**
   * Whether an address is configured at all. Unconfigured is the posture of a deployment that
   * switched qits-maintenance off (blank {@code qits.projects.release-requests.maintenance-url}, the
   * same key that turns the automations gate off): no automation ran, so no automation content can
   * need approving.
   */
  boolean configured();

  /**
   * {@code GET /maintenance/api/automations/kinds}: every kind the build offers. {@link
   * Optional#empty()} is "could not ask" — unreachable, refusing, an answer that will not parse —
   * and never throws.
   */
  Optional<List<Kind>> kinds();
}
