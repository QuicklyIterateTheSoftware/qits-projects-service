package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.WorkEntity;

/**
 * <b>A person's write of an assignee, and the claim it makes</b> (qits-2259): the one rule the
 * create, the PATCH and the PUT-shaped transition share for {@link WorkEntity#assigneeClaimed}. A
 * different non-blank assignee is a claim; a blank or absent one clears both; the value the row
 * already holds, restated, changes nothing — the SPA re-sends the whole row on every save, and that
 * must not turn the agent a dispatch put there into a person's claim. The dispatch's own write is
 * {@code EntityDispatchService.setAssignee}'s, which clears the flag.
 */
final class AssigneeClaims {

  private AssigneeClaims() {}

  /** Writes {@code assignee} onto {@code row} as a person's write, and the claim with it. */
  static void write(WorkEntity row, String assignee) {
    String next = assignee == null || assignee.isBlank() ? null : assignee;
    if (next == null) {
      row.assignee = null;
      row.assigneeClaimed = false;
    } else if (!next.equals(row.assignee)) {
      row.assignee = next;
      row.assigneeClaimed = true;
    }
  }
}
