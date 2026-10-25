package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.refinementhost.RefinementSessions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * A project one of whose refinement rooms wants its session (epic qits-770): a session room runs in
 * the front desk, so the desk is wanted — an {@code ON_DEMAND} desk included — while any of the
 * project's session rooms is desired RUNNING ({@link RefinementSessions#wantsRunning}: demand younger
 * than {@code qits.projects.agent-idle-timeout}). Reads the project's rooms; writes nothing.
 */
@ApplicationScoped
public class RefinementDemand implements FrontDeskDemandSource {

  @Inject RefinementSessions sessions;

  @Override
  public String name() {
    return "refinement";
  }

  @Override
  public boolean wants(Project project, FrontDesk row, Instant now) {
    return project != null && !sessions.running(project.id, now).isEmpty();
  }
}
