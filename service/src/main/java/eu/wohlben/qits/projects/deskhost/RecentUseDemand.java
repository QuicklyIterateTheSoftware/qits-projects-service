package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.Project;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A desk somebody used within {@code qits.projects.agent-idle-timeout} (PT4H) (qits-767): the
 * {@code last_demand_at} stamp an {@code ensure} writes, and the daemon frames that evidence use
 * write (throttled). A stop clears the stamp, so the window closes at once.
 */
@ApplicationScoped
public class RecentUseDemand implements FrontDeskDemandSource {

  @ConfigProperty(name = "qits.projects.agent-idle-timeout", defaultValue = "PT4H")
  Duration idleTimeout;

  @Override
  public String name() {
    return "recent-use";
  }

  @Override
  public boolean wants(Project project, FrontDesk row, Instant now) {
    if (row == null || row.lastDemandAt == null) {
      return false;
    }
    if (idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative()) {
      return true; // no idle window: once used, wanted until stopped
    }
    return Duration.between(row.lastDemandAt, now).compareTo(idleTimeout) < 0;
  }
}
