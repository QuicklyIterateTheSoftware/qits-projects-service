package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.Pitch;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The pitches (qits-2155, V77); the caller owns the transaction. */
@ApplicationScoped
public class PitchRepository implements PanacheRepositoryBase<Pitch, UUID> {

  /** What {@link #search} narrows by; a null member narrows nothing. */
  public record Filter(
      String projectId,
      String agentName,
      String repositoryId,
      String route,
      String screenshot,
      boolean archived) {}

  /**
   * The pitches {@code filter} admits, newest {@code updated_at} first (the id breaks a tie).
   * Archived pitches only when {@link Filter#archived()} asks for them.
   */
  public List<Pitch> search(Filter filter) {
    StringBuilder query = new StringBuilder("projectId = :projectId");
    Map<String, Object> params = new HashMap<>();
    params.put("projectId", filter.projectId());
    narrow(query, params, "agentName", filter.agentName());
    narrow(query, params, "repositoryId", filter.repositoryId());
    narrow(query, params, "route", filter.route());
    narrow(query, params, "screenshot", filter.screenshot());
    if (!filter.archived()) {
      query.append(" and archivedAt is null");
    }
    query.append(" order by updatedAt desc, id");
    return new ArrayList<>(list(query.toString(), params));
  }

  /** The pitch {@code id}, locked for the write that follows, or null. */
  public Pitch lock(UUID id) {
    return find("id", id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
  }

  private static void narrow(
      StringBuilder query, Map<String, Object> params, String field, String value) {
    if (value != null) {
      query.append(" and ").append(field).append(" = :").append(field);
      params.put(field, value);
    }
  }
}
