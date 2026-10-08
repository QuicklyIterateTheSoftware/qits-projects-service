package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projectsdeskrunner.protocol.Estate;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Sends a runner its full {@code estate} (qits-767): on its greeting, on every change to one of its
 * desks, and every {@code qits.projects.desk-runner.estate-interval}.
 *
 * <p><b>The estate is the server's list and it wins</b> — a desk container the runner holds whose
 * project is not on it is removed — so it is sent only from a read that succeeded, never as an empty
 * list on error. It goes to the runner's serving session only: greeted at the pin and not draining.
 */
@ApplicationScoped
public class FrontDeskEstates {

  private static final Logger LOG = Logger.getLogger(FrontDeskEstates.class);

  @Inject FrontDesks desks;

  @Inject DeskRunnerRegistry registry;

  /** Send {@code runnerId}'s estate to its serving session, if it has one. */
  public boolean push(UUID runnerId) {
    DeskRunnerRegistry.Session session = runnerId == null ? null : registry.serving(runnerId);
    return session != null && push(session);
  }

  /** Send the session's runner its estate. False when it could not be read or sent. */
  public boolean push(DeskRunnerRegistry.Session session) {
    Estate estate;
    try {
      estate = desks.estate(session.runnerId());
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not read runner %s's estate; nothing sent: %s", session.runnerName(), e.getMessage());
      return false;
    }
    return registry.send(session, estate);
  }

  /** Every connected runner's estate. */
  public int pushAll() {
    int sent = 0;
    for (UUID runnerId : registry.connectedRunnerIds()) {
      if (push(runnerId)) {
        sent++;
      }
    }
    return sent;
  }

  @Scheduled(
      every = "{qits.projects.desk-runner.estate-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onInterval() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    try {
      pushAll();
    } catch (RuntimeException e) {
      LOG.warn("The estate interval failed — retried on the next one.", e);
    }
  }
}
