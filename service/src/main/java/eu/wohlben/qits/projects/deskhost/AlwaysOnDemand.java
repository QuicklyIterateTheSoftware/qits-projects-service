package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Project;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

/** A project whose {@code project.yml} says {@code front_desk.lifecycle: ALWAYS_ON} (qits-767). */
@ApplicationScoped
public class AlwaysOnDemand implements FrontDeskDemandSource {

  @Override
  public String name() {
    return "always-on";
  }

  @Override
  public boolean wants(Project project, FrontDesk row, Instant now) {
    return project != null && project.frontDeskLifecycle == FrontDeskLifecycle.ALWAYS_ON;
  }
}
