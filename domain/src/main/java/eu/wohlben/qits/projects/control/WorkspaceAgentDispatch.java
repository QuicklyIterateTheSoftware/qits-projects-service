package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.error.DomainException;

/**
 * A workspace with a coding agent already running in it, asked for on a branch of one repository —
 * the seam behind the "Assign agent" button on a ticket.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A ticket names work and names no repository, so the thing that can act on one is an aggregate
 * workspace over the whole estate: the wrapper's branch with every submodule branched alongside it.
 * qits-workspaces is the only context that can make one, and the only context that can launch an
 * agent into it. Nothing here can do either, and nothing on the bus can ask for them — a dispatch is
 * a request with an outcome somebody is waiting on, not a fact to announce.
 *
 * <h2>The outcome matters, and that is the whole difference from {@link ReleasedBranchWorkspaces}</h2>
 *
 * <p>That port is a fire-and-forget told <em>after</em> a release has already landed, so its contract
 * is that it never throws and answers nothing. This one is the request itself: a person pressed a
 * button and is waiting to be sent to the workspace. <b>So an implementation that could not dispatch
 * must throw</b>, as a {@link DomainException} carrying the status the door should answer —
 * <b>502</b> when qits-workspaces refused, was unreachable or answered something unreadable, and
 * <b>503</b> when this hop is not configured to reach it at all (no address, no machine credential).
 * A WARN and a hopeful empty would leave a ticket saying an agent is on it when none is.
 *
 * <p>It is <b>not</b> idempotent in the way a resolution is, and the far side is what makes that
 * safe: a second dispatch onto the same branch adopts the workspace that is already there, which is
 * what {@link Dispatch#fresh()} and {@code agentLaunch} report back. Nothing here retries — a
 * dispatch is one press of one button, and a person can press it again.
 *
 * <h2>Absent is a supported configuration</h2>
 *
 * <p>Injected as {@code Instance<T>} like every port here. With no implementation present the door
 * answers <b>503</b> naming what is missing, which is the honest answer: an assembly that does not
 * run qits-workspaces has no workspaces to dispatch into, and the ticket is unchanged — no comment
 * is stamped and no hint is fired, because nothing happened.
 *
 * <h2>Why this is not a verb on {@link WorkspaceLifecycle}</h2>
 *
 * <p>{@link ReleasedBranchWorkspaces}' javadoc gives the reason in full and it has not changed: that
 * port has <b>no production adapter</b>, so an HTTP implementation added for one verb would be
 * forced to co-implement {@code createMainWorkspace} and {@code releaseRepository}, each of which is
 * a live flow this change neither owns nor tests. A separate port keeps the blast radius at exactly
 * one call on exactly one path.
 */
public interface WorkspaceAgentDispatch {

  /**
   * What qits-workspaces answered, narrowed to what this side acts on.
   *
   * @param workspaceRowId the workspace's row id at qits-workspaces — the SPA composes the link to
   *     it from this and the repository id, so it is the one field the door exists to return
   * @param fresh whether the workspace was created by this call, or adopted because it was already
   *     standing on the branch
   * @param agentLaunch {@code SCHEDULED} when an agent was started for this dispatch, {@code
   *     SKIPPED_RUNNING} when one was already working in that workspace. A string rather than an
   *     enum: it is the far side's vocabulary, and a value this side has never heard of must reach
   *     the ticket thread rather than fail the dispatch.
   */
  record Dispatch(long workspaceRowId, boolean fresh, String agentLaunch) {}

  /**
   * Make (or adopt) the workspace on {@code branch} and launch an agent in it.
   *
   * @param repositoryId the <b>catalog</b> repository id — this service's row id, which is what
   *     qits-workspaces keys its {@code repository_id} by. For a ticket this is the project's
   *     wrapper, because a ticket names no repository.
   * @param branch the branch to stand the workspace on, created at the repository's default branch
   *     if it does not exist
   * @param branchTree whether the submodules are branched alongside the wrapper — true for the
   *     whole-estate aggregate a ticket needs
   * @param preamble the workspace's goal, Markdown, rendered from the row the dispatch is about
   * @param instruction the agent's first turn
   * @return what the far side made or adopted
   * @throws DomainException when the dispatch could not be made — 502 for a far side that refused,
   *     was unreachable or answered unreadably, 503 for a hop with no address or no credential
   */
  Dispatch dispatchAgent(
      String repositoryId, String branch, boolean branchTree, String preamble, String instruction);
}
