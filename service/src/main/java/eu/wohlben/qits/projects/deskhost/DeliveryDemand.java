package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.DeskRole;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.Project;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

/**
 * A project whose {@code project.yml} says {@code front_desk.delivery.enabled: true} (epic
 * qits-1440): its DELIVERY desk runs the delivery loop, so it is wanted for as long as the loop is
 * on. Since qits-1482 the loop has a desk of its own, so this source speaks for the DELIVERY desk
 * alone and no longer wants the project's FRONT desk.
 */
@ApplicationScoped
public class DeliveryDemand implements FrontDeskDemandSource {

  @Override
  public String name() {
    return "delivery";
  }

  @Override
  public DeskRole role() {
    return DeskRole.DELIVERY;
  }

  @Override
  public boolean wants(Project project, FrontDesk row, Instant now) {
    return project != null && project.deliveryEnabled;
  }
}
