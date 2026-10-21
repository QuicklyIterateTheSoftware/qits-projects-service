package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * <b>What the caller may decide on one release request's current fold</b> (epic qits-1038) — the
 * read a page asks before it draws Approve and Decline, from the same proof the doors check. The
 * request itself answers the same for every viewer; this is the one per-viewer read.
 *
 * <p>A page shows Approve when an id here names a requirement that is still open, and Decline when
 * any id is here at all.
 *
 * @param viewer the name the caller would decide under, or the caller's principal where it holds no
 *     approval role; null for an anonymous caller
 * @param heldRoles the approval roles this service proved the caller holds, relative to the request
 *     ({@code agent} for an agent of its own project), sorted; empty where none
 * @param eligibleRequirementIds every requirement of the current fold the caller may satisfy — open
 *     or already satisfied — in the request's order
 */
public record ApprovalEligibilityDto(
    String viewer, List<String> heldRoles, List<String> eligibleRequirementIds) {}
