package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.DeskRunner;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerBinary;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@link DeskRunnerSessions} shipped until the runner socket lands (qits-767): no runner is ever
 * connected, so nothing is sent and every notification is dropped. The pin is real — it is the
 * protocol jar's {@link DeskRunnerBinary#VERSION}. {@code @DefaultBean}, so the socket's registry
 * wins the injection point simply by existing.
 */
@ApplicationScoped
@DefaultBean
public class NoDeskRunnerSessions implements DeskRunnerSessions {

  @Override
  public Boolean connected(UUID runnerId) {
    return false;
  }

  @Override
  public Instant connectedSince(UUID runnerId) {
    return null;
  }

  @Override
  public String pinnedVersion() {
    return DeskRunnerBinary.VERSION;
  }

  @Override
  public String loginCommand(DeskRunner runner) {
    return null;
  }

  @Override
  public Optional<String> requestHealthCheck(UUID runnerId) {
    return Optional.empty();
  }

  @Override
  public boolean probeLogin(UUID runnerId) {
    return false;
  }

  @Override
  public void deleted(UUID runnerId) {}

  @Override
  public void reinstated(UUID runnerId, String by) {}

  @Override
  public void slotsChanged(UUID runnerId) {}
}
