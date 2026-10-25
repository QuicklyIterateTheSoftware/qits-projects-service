package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.agenthost.ContainerProxyPath;
import eu.wohlben.qits.projects.refinementhost.RefinementService;
import java.time.Instant;

/**
 * One refinement as the refining route reads it — the row plus the live halves, field names kept
 * deliberately in step with the workspace projection the SPA's status strip already consumes
 * ({@code runtimeStatus}, {@code clean}, {@code ahead}, {@code daemonConnectedAt}, …), so the
 * cutover moves a base URL rather than a vocabulary.
 *
 * <p>{@code runtimeStatus} is one of {@code RUNNING | STOPPED | PROVISIONING | FAILED} — the
 * published strings the strip switches on. {@code clean} is three-valued: {@code null} is "the
 * daemon has not vouched", which blocks a recreate. {@code daemonOutdated} is {@code TRUE} or
 * {@code null}, never {@code false} — "outdated" is a claim, its absence is not one.
 *
 * <p><b>Two version questions are answered here and they are different questions.</b> {@code
 * daemonOutdated} compares this daemon against the newest daemon connected to <em>this host</em> —
 * right for "somebody else has a newer one", and {@code null} whenever this is the only refinement
 * daemon connected, which is the common case, so a container running a months-old image can answer
 * nothing at all. {@code daemonVersionStale} compares the connected daemon against {@code
 * pinnedDaemonVersion}, the image this service actually deploys — right for "this is not the image
 * we deploy". A reader who confuses them builds the wrong thing. Neither replaces the other and
 * both stay.
 *
 * <p>{@code pinnedDaemonVersion} is a property of this service rather than of the container, so it
 * is present even with no daemon connected. {@code daemonVersionStale} is a plain {@code boolean}
 * and not a third three-valued field: unlike {@code daemonOutdated}, whose {@code null} hides
 * whether a peer comparison was possible, the only thing that could make this one unanswerable is
 * no daemon being connected — which this row already says twice, in {@code daemonConnectedAt} and
 * {@code daemonVersion}. So {@code false} is "no claim" and never a guess, exactly as it is on
 * {@code agenthost/AgentContainerState}, whose shape this pair copies.
 *
 * <p><b>{@code entityId} and {@code epicId} carry the same value, and only one of them is staying</b>
 * (qits-395). A refinement names an entity of any archetype with a lifecycle — an epic or a ticket —
 * and {@code entityId} is that key. {@code epicId} is the name the deployed SPA reads (it matches its
 * epic against the project listing by it), so it keeps answering in this release, holding the entity
 * id whatever the archetype: ids are one space, so an epic-matching reader never matches a ticket's
 * room by accident. <b>{@code epicId} is removed in a later release</b>, once the SPA reads {@code
 * entityId}; nothing new may read it.
 *
 * <p><b>A session room (epic qits-770)</b> — every room opened since then — runs as a session of its
 * project's front desk rather than in a container of its own. Its {@code runtimeStatus} is the
 * session's reported state ({@code STARTING | RUNNING | STOPPED | FAILED}) and {@code runtimeError}
 * the daemon's detail; {@code sessionId} and {@code commandId} are the harness session and the
 * daemon command running it (the command only while RUNNING); {@code deskStatus} and {@code
 * deskDetail} are the front desk's own state word ({@code QUEUED}, {@code PROVISIONING}, {@code
 * RUNNING}, {@code STOPPED}, {@code FAILED}, {@code UNAVAILABLE}, {@code ABSENT}) and failure; and
 * {@code deskBase} is the desk daemon's proxy base, {@code /projects/container/<projectId>/}. A
 * legacy container room carries those five as null. The container-only fields — {@code branch},
 * {@code parent}, {@code clean}, {@code ahead}, {@code behind}, {@code conflictsWithParent} and the
 * {@code daemon*}/{@code pinnedDaemonVersion} family — stay in this release (the deployed SPA's
 * pact pins them) and are null or false for a session room; qits-1316 drops them.
 */
public record RefinementDto(
    Long id,
    String entityId,
    // Deprecated duplicate of entityId, kept for the deployed SPA — see the class javadoc.
    String epicId,
    String projectId,
    String repositoryId,
    String branch,
    String parent,
    String label,
    String runtimeStatus,
    String runtimeError,
    Boolean clean,
    Integer ahead,
    Integer behind,
    boolean conflictsWithParent,
    String agentActivity,
    Instant daemonConnectedAt,
    String daemonVersion,
    Boolean daemonOutdated,
    String pinnedDaemonVersion,
    boolean daemonVersionStale,
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
        view.refinement().branch,
        view.refinement().parent,
        view.refinement().label,
        view.runtimeStatus(),
        view.runtimeError(),
        view.clean(),
        view.ahead(),
        view.behind(),
        view.conflictsWithParent(),
        view.agentActivity(),
        view.daemonConnectedAt(),
        view.daemonVersion(),
        view.daemonOutdated(),
        view.pinnedDaemonVersion(),
        view.daemonVersionStale(),
        view.refinement().createdAt,
        view.sessionId(),
        view.commandId(),
        view.deskStatus(),
        view.deskDetail(),
        view.session() ? ContainerProxyPath.base(view.refinement().projectId) : null);
  }
}
