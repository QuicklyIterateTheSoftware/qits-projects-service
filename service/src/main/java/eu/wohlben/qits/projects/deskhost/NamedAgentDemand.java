package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.NamedAgent;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.NamedAgentRepository;
import eu.wohlben.qits.projects.refinementhost.RefinementSessions;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * A project one of whose named agents wants its session (qits-2154): a named agent runs in the
 * front desk, so the desk is wanted — an {@code ON_DEMAND} desk included — while any of the
 * project's named agents was prompted, or used, within {@code qits.projects.agent-idle-timeout}
 * ({@link RefinementSessions#wantsRunning(NamedAgent, Instant)}). A prompt stamps the desk's own
 * demand too, so this mostly agrees with {@link RecentUseDemand}; it is what keeps the desk up for
 * an agent whose session is still in use after the desk's own stamp aged out, the way {@link
 * RefinementDemand} does for a room. Reads the project's rows; writes nothing.
 */
@ApplicationScoped
public class NamedAgentDemand implements FrontDeskDemandSource {

  @Inject NamedAgentRepository rows;

  @Inject RefinementSessions sessions;

  @Override
  public String name() {
    return "named-agent";
  }

  @Override
  public boolean wants(Project project, FrontDesk row, Instant now) {
    if (project == null) {
      return false;
    }
    return QuarkusTransaction.requiringNew().call(() -> rows.listByProject(project.id)).stream()
        .anyMatch(agent -> sessions.wantsRunning(agent, now));
  }
}
