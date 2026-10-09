package eu.wohlben.qits.projects.api;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 * @param blocked whether the entity reads as blocked — the EFFECTIVE block (qits-895): an explicit
 *     block, or its agent session waiting for a person past the debounce. Only the explicit one
 *     refuses the press, which is why {@code dispatchable} can be true while this is
 * @param dispatchable whether a FLOW press would run something and be accepted, as far as this row
 *     can say (a project with no wrapper or no workspaces context still refuses): {@code nextPhase
 *     != null} and not EXPLICITLY blocked — a derived block never refuses a dispatch — and also
 *     true at an explicitly unblocked REFINED (qits-1075), where a person's
 *     Dispatch schedules the entity and starts implement — {@code nextPhase} stays null there,
 *     because a PHASE press starts nothing at REFINED
 * @param mode the mode the last press recorded, which is what the phase advance follows on the next
 *     transition: {@code FLOW} or {@code PHASE}; {@code null} for a feature or a task
 * @param preApprovedBy read-only: the person whose Dispatch press pre-approved scheduling the entity
 *     once its refine phase lands REFINED (qits-1075), or {@code null} for none (and always for a
 *     campaign, a feature or a task). Nothing a client sends writes it
 * @param blockSource {@code EXPLICIT}, {@code AGENT_WAITING} or {@code BOTH}; absent while not
 *     blocked
 * @param blockReason the explicit block's stated reason, else the agent-waiting sentence; absent
 *     while not blocked
 * @param blockedBy who set the explicit block; absent otherwise
 */
public record EntityDispatchStateDto(
    String entityId,
    String archetype,
    String status,
    String nextPhase,
    boolean blocked,
    boolean dispatchable,
    DispatchMode mode,
    String preApprovedBy,
    @JsonInclude(JsonInclude.Include.NON_NULL) String blockSource,
    @JsonInclude(JsonInclude.Include.NON_NULL) String blockReason,
    @JsonInclude(JsonInclude.Include.NON_NULL) String blockedBy) {}
