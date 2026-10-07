package eu.wohlben.qits.projects.api;

/**
 * What one press of the unified dispatch door did, as the SPA reads it — the answer to {@code POST
 * /projects/api/work/{qualifiedId}/dispatch}, wrapped as {@code {"dispatch": …}}.
 *
 * <p>The last five fields are the ones the two retired DTOs ({@code TicketAgentDispatchDto}, {@code
 * EpicAgentDispatchDto}, removed with their doors in qits-399) carried, with the same meaning: {@code workspaceRowId} and {@code
 * repositoryId} compose the workspaces link, and {@code fresh}/{@code agentLaunch} are the far
 * side's report ({@code SKIPPED_RUNNING} when an agent was already working there). The first four
 * say what was started: which entity, of which kind, which phase its status started, and which mode
 * the press recorded.
 *
 * @param entityId the entity the press was about
 * @param archetype {@code TICKET} or {@code EPIC}
 * @param phase {@code refine}, {@code implement} or {@code verify}
 * @param mode {@code FLOW} or {@code PHASE}, as recorded on the entity
 * @param assignee the entity's assignee as the press left it (qits-887): the dispatched agent's
 *     identity, or {@code workspace <workspaceRowId>} when qits-workspaces named none
 */
public record EntityDispatchDto(
    String entityId,
    String archetype,
    String phase,
    DispatchMode mode,
    long workspaceRowId,
    String repositoryId,
    String branch,
    boolean fresh,
    String agentLaunch,
    String assignee) {}
