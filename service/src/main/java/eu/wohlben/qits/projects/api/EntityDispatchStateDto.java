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
 * @param dispatchable whether a FLOW press would run something and be accepted, as far as this row
 *     can say (a project with no wrapper or no workspaces context still refuses): {@code nextPhase
 *     != null && !blocked}, and also true at an unblocked REFINED (qits-1075), where a person's
 *     Dispatch schedules the entity and starts implement — {@code nextPhase} stays null there,
 *     because a PHASE press starts nothing at REFINED
 * @param mode the mode the last press recorded, which is what the phase advance follows on the next
 *     transition: {@code FLOW} or {@code PHASE}; {@code null} for a feature or a task
 * @param preApprovedBy read-only: the person whose Dispatch press pre-approved scheduling the entity
 *     once its refine phase lands REFINED (qits-1075), or {@code null} for none (and always for a
 *     campaign, a feature or a task). Nothing a client sends writes it
 */
public record EntityDispatchStateDto(
    String entityId,
    String archetype,
    String status,
    String nextPhase,
    boolean blocked,
    boolean dispatchable,
    DispatchMode mode,
    String preApprovedBy) {}
