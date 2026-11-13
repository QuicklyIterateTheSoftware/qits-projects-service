package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.NamedAgent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Which named agent is calling</b> (qits-2155): a caller's token subject resolved back to the
 * named agent ({@link NamedAgents}) whose credential it is. qits-2154 stores each agent's token
 * subject on its {@code named_agent} row; this is the lookup in the other direction, which "a named
 * agent writes only its own pitches" needs, because every named agent's token is {@code qits:agent}
 * with only a {@code project} claim and its principal names no agent.
 *
 * <ul>
 *   <li><b>The row with that project and that subject</b>: the {@code named_agent} row whose {@code
 *       project_id} is the token's project and whose {@code token_subject} is the caller's subject
 *       resolves to that row's name.
 *   <li><b>The desk</b> has no {@code named_agent} row: its {@code front_desk} row's {@code
 *       token_subject} resolves to {@link NamedAgents#DESK}, so the desk could pitch too.
 *   <li>Anything else — a person, a platform service, a workspace agent, another project's agent —
 *       resolves to nothing.
 * </ul>
 *
 * <p><b>Cached by subject</b>, the way {@code FrontDesks} keeps each desk's bound subject: a
 * subject is minted with one token and never moves to another row, so a hit stays true for as long
 * as the token can be presented at all (a revoked token no longer authenticates). Only hits are
 * cached: an agent whose first token is minted after a miss is found on its next call.
 */
@ApplicationScoped
public class NamedAgentCaller {

  /** A resolved caller: the named agent {@code name} of project {@code projectId}. */
  public record Caller(String projectId, String name) {}

  private final ConcurrentHashMap<String, Caller> bySubject = new ConcurrentHashMap<>();

  /**
   * The named agent whose token subject is {@code subject} in project {@code projectId} (the
   * token's {@code project} claim), or empty.
   */
  public Optional<Caller> resolve(String projectId, String subject) {
    if (projectId == null || projectId.isBlank() || subject == null || subject.isBlank()) {
      return Optional.empty();
    }
    Caller cached = bySubject.get(subject);
    if (cached != null) {
      return cached.projectId().equals(projectId) ? Optional.of(cached) : Optional.empty();
    }
    Optional<Caller> found =
        QuarkusTransaction.requiringNew().call(() -> lookup(projectId, subject));
    found.ifPresent(caller -> bySubject.put(subject, caller));
    return found;
  }

  private static Optional<Caller> lookup(String projectId, String subject) {
    Optional<NamedAgent> agent =
        NamedAgent.<NamedAgent>find(
                "projectId = ?1 and tokenSubject = ?2", projectId, subject)
            .firstResultOptional();
    if (agent.isPresent()) {
      return Optional.of(new Caller(projectId, agent.get().name));
    }
    FrontDesk desk = FrontDesk.findById(projectId);
    if (desk != null && subject.equals(desk.tokenSubject)) {
      return Optional.of(new Caller(projectId, NamedAgents.DESK));
    }
    return Optional.empty();
  }
}
