package eu.wohlben.qits.projects.api;

/**
 * What a dispatch press on this entity would do now — the answer to {@code GET
 * /projects/api/work/{qualifiedId}/dispatch}, wrapped as {@code {"state": …}}. It is how the SPA learns
 * the status→phase rule without re-implementing it: the server owns {@code PhasePrompts.phaseOf} and
 * this is its answer for one row.
 *
 * @param entityId the entity asked about
 * @param archetype its kind: {@code EPIC}, {@code FEATURE}, {@code TASK} or {@code TICKET}
 * @param status its status word — a feature's and a task's too, since qits-763
 * @param nextPhase {@code refine}, {@code implement} or {@code verify} — the phase a press would
 *     start — or {@code null} where none would (VERIFIED, DONE, DROPPED, or a feature or a task,
 *     which run no phase of their own)
 * @param blocked whether a block refuses the press even though a phase exists (tickets only)
 * @param dispatchable {@code nextPhase != null && !blocked}: whether a press would be accepted, as
 *     far as this row can say (a project with no wrapper or no workspaces context still refuses)
 * @param mode the mode the last press recorded, which is what the phase advance follows on the next
 *     transition: {@code FLOW} or {@code PHASE}; {@code null} for a feature or a task
 */
public record EntityDispatchStateDto(
    String entityId,
    String archetype,
    String status,
    String nextPhase,
    boolean blocked,
    boolean dispatchable,
    DispatchMode mode) {}
