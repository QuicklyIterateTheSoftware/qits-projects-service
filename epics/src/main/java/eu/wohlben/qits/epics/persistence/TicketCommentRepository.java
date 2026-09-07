package eu.wohlben.qits.epics.persistence;

import eu.wohlben.qits.epics.entity.TicketComment;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class TicketCommentRepository implements PanacheRepositoryBase<TicketComment, String> {

  // Oldest first — a thread is a sequence and reading it backwards is reading a different thread —
  // with the id as a deterministic tie-breaker so two remarks sharing a created_at (several writes
  // in one transaction) come back in a stable order. The mirror of AuditRepository's NEWEST_FIRST.
  private static final Sort OLDEST_FIRST = Sort.by("createdAt").and("id");

  /** Every comment on one ticket, oldest first. */
  public List<TicketComment> listByTicket(String ticketId) {
    return find("ticketId", OLDEST_FIRST, ticketId).list();
  }
}
