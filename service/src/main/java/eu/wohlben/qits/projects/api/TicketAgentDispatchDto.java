package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;

/**
 * What a ticket's agent dispatch made at qits-workspaces, as the SPA reads it.
 *
 * <p>{@code workspaceRowId} and {@code repositoryId} are the pair the workspaces link is composed
 * from, and they are the reason this door answers anything at all — a dispatch that could only say
 * "done" would leave the browser with no way to send the person to the workspace their agent is
 * working in.
 *
 * <p>{@code fresh} and {@code agentLaunch} are the far side's report of what it actually did: a
 * second press on the same ticket adopts the workspace already standing on the branch and answers
 * {@code SKIPPED_RUNNING} rather than starting a second agent. Both travel to the browser because
 * both change what it should say.
 */
public record TicketAgentDispatchDto(
    long workspaceRowId, String repositoryId, String branch, boolean fresh, String agentLaunch) {

  public static TicketAgentDispatchDto of(
      WorkspaceAgentDispatch.Dispatch dispatch, String repositoryId, String branch) {
    return new TicketAgentDispatchDto(
        dispatch.workspaceRowId(), repositoryId, branch, dispatch.fresh(), dispatch.agentLaunch());
  }
}
