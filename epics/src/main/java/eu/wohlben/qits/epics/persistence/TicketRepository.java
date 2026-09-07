package eu.wohlben.qits.epics.persistence;

import eu.wohlben.qits.epics.entity.Ticket;
import eu.wohlben.qits.epics.entity.TicketStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class TicketRepository implements PanacheRepositoryBase<Ticket, String> {

  /** Oldest first, with the id as a deterministic tie-break — an epic list's order. */
  public List<Ticket> listByProject(String projectId) {
    return find("projectId", Sort.by("createdAt").and("id"), projectId).list();
  }

  /** The same list narrowed to one status — the (project_id, status) index V4 adds. */
  public List<Ticket> listByProjectAndStatus(String projectId, TicketStatus status) {
    return find("projectId = ?1 and status = ?2", Sort.by("createdAt").and("id"), projectId, status)
        .list();
  }
}
