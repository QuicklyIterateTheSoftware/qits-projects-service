package eu.wohlben.qits.epics.entity;

/**
 * Which planning entity an {@link AuditEntry} concerns. A closed vocabulary, pinned by a check
 * constraint on {@code auditentry.entity_type} — widening it is a migration (V4 added the two
 * ticket words) as well as a value here.
 */
public enum AuditEntityType {
  EPIC,
  FEATURE,
  TASK,

  /**
   * A {@link Ticket}. Its rows carry the ticket's own id in {@link AuditEntry#epicId} — see that
   * field, which is the subtree key rather than a foreign key to an epic.
   */
  TICKET,

  /** A {@link TicketComment}. Its rows carry the owning ticket's id as the subtree key. */
  TICKET_COMMENT
}
