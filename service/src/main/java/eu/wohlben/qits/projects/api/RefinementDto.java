package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.agenthost.ContainerProxyPath;
import eu.wohlben.qits.projects.refinementhost.RefinementService;
import java.time.Instant;

/**
 * One refinement room as the refining route reads it — the row plus the live halves of its session
 * in the project's front desk (epic qits-770).
 *
 * <p>{@code runtimeStatus} is the session's reported state ({@code STARTING | RUNNING | STOPPED |
 * FAILED}; before any report, {@code STARTING} while wanted, else {@code STOPPED}) and {@code
 * runtimeError} the daemon's detail; {@code sessionId} and {@code commandId} are the harness session
 * and the daemon command running it (the command only while RUNNING); {@code deskStatus} and {@code
 * deskDetail} are the front desk's own state word ({@code QUEUED}, {@code PROVISIONING}, {@code
 * RUNNING}, {@code STOPPED}, {@code FAILED}, {@code UNAVAILABLE}, {@code ABSENT}) and failure; and
 * {@code deskBase} is the desk daemon's proxy base, {@code /projects/container/<projectId>/}. {@code
 * agentActivity} is always null now that no per-room daemon reports one.
 *
 * <p>The container rooms' fields — {@code branch}, {@code parent}, {@code clean}, {@code ahead},
 * {@code behind}, {@code conflictsWithParent}, {@code daemonConnectedAt}, {@code daemonVersion},
 * {@code daemonOutdated}, {@code pinnedDaemonVersion} and {@code daemonVersionStale} — went with the
 * containers (qits-1316).
 *
 * <p><b>{@code entityId} and {@code epicId} carry the same value, and only one of them is staying</b>
 * (qits-395). A refinement names an entity of any archetype with a lifecycle and {@code entityId} is
 * that key. {@code epicId} is the name an older SPA reads, holding the entity id whatever the
 * archetype; nothing new may read it.
 */
public record RefinementDto(
    Long id,
    String entityId,
    // Deprecated duplicate of entityId, kept for the deployed SPA — see the class javadoc.
    String epicId,
    String projectId,
    String repositoryId,
    String label,
    String runtimeStatus,
    String runtimeError,
    String agentActivity,
    Instant createdAt,
    String sessionId,
    String commandId,
    String deskStatus,
    String deskDetail,
    String deskBase) {

  public static RefinementDto of(RefinementService.RefinementView view) {
    return new RefinementDto(
        view.refinement().id,
        view.refinement().entityId,
        view.refinement().entityId,
        view.refinement().projectId,
        view.refinement().repositoryId,
        view.refinement().label,
        view.runtimeStatus(),
        view.runtimeError(),
        null,
        view.refinement().createdAt,
        view.sessionId(),
        view.commandId(),
        view.deskStatus(),
        view.deskDetail(),
        ContainerProxyPath.base(view.refinement().projectId));
  }
}
