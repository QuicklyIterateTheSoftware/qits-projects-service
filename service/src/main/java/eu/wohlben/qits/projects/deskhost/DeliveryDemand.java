package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.Project;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

/**
 * A project whose {@code project.yml} says {@code front_desk.delivery.enabled: true} (epic
 * qits-1440): the desk runs the delivery loop, so it is wanted whatever its lifecycle — an
 * ON_DEMAND desk included — for as long as the loop is on.
 */
@ApplicationScoped
public class DeliveryDemand implements FrontDeskDemandSource {

  @Override
  public String name() {
    return "delivery";
  }

  @Override
  public boolean wants(Project project, FrontDesk row, Instant now) {
    return project != null && project.deliveryEnabled;
  }
}
