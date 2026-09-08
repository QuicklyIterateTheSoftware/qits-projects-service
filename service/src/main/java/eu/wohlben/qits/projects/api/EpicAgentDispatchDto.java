package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;

/**
 * What an epic's agent dispatch made at qits-workspaces, as the SPA reads it.
 *
 * <p>{@code workspaceRowId} and {@code repositoryId} are the pair the workspaces link is composed
 * from, and they are why this door answers anything at all: a dispatch that could only say "done"
 * would leave the browser with no way to send the person to the workspace their agent is working
 * in.
 *
 * <p>{@code fresh} and {@code agentLaunch} are the far side's report of what it actually did. They
 * matter more here than on a ticket, because "Start implementation" is <b>re-pressable by
 * design</b>: an epic already in IMPLEMENTATION is dispatched onto as it stands, and the far side
 * then adopts the workspace already on {@code epic/<slug>} and answers {@code SKIPPED_RUNNING}
 * rather than starting a second agent. Both fields travel to the browser because both change what
 * it should say.
 *
 * <p><b>A separate record from {@link TicketAgentDispatchDto} rather than a shared one</b>, though
 * the five fields are the same today. The two are different flows with different re-press
 * semantics, the javadoc that explains each belongs on its own type, and the OpenAPI schema names
 * them — a generated client reading {@code TicketAgentDispatchDto} out of an epic response would be
 * a shape that says the wrong thing about which door it came from.
 */
public record EpicAgentDispatchDto(
    long workspaceRowId, String repositoryId, String branch, boolean fresh, String agentLaunch) {

  public static EpicAgentDispatchDto of(
      WorkspaceAgentDispatch.Dispatch dispatch, String repositoryId, String branch) {
    return new EpicAgentDispatchDto(
        dispatch.workspaceRowId(), repositoryId, branch, dispatch.fresh(), dispatch.agentLaunch());
  }
}
