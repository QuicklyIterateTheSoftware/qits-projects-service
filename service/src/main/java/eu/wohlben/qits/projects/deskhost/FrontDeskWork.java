package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerMessage;
import eu.wohlben.qits.projectsdeskrunner.protocol.Inventory;
import eu.wohlben.qits.projectsdeskrunner.protocol.LaunchFailed;
import eu.wohlben.qits.projectsdeskrunner.protocol.Removed;
import eu.wohlben.qits.runner.protocol.Nothing;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.projectsdeskrunner.protocol.Take;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The front desk's half of a runner's socket (qits-767), the {@link DeskRunnerWork} the registry
 * asks: {@code reserve} is placement ({@link FrontDesks#take}), the held desks a {@code hello} names
 * are adopted when they are placed on that runner, a greeting is answered with the estate, and
 * {@code inventory}, {@code launchFailed} and {@code removed} land on the rows.
 */
@ApplicationScoped
public class FrontDeskWork implements DeskRunnerWork {

  private static final Logger LOG = Logger.getLogger(FrontDeskWork.class);

  @Inject FrontDesks desks;

  @Inject FrontDeskEstates estates;

  @Override
  public RunnerMessage reserve(DeskRunnerRegistry.Session session) {
    Take take = desks.take(session.runnerId());
    return take == null ? new Nothing() : take;
  }

  @Override
  public long backlog(DeskRunnerRegistry.Session session) {
    return desks.backlog();
  }

  @Override
  public List<String> adopted(DeskRunnerRegistry.Session session, List<String> held) {
    return desks.adopted(session.runnerId(), held);
  }

  @Override
  public void greeted(DeskRunnerRegistry.Session session) {
    estates.push(session);
  }

  @Override
  public void onFrame(DeskRunnerRegistry.Session session, DeskRunnerMessage frame) {
    switch (frame) {
      case Inventory inventory -> desks.inventory(session.runnerId(), inventory);
      case LaunchFailed failed ->
          desks.launchFailed(session.runnerId(), failed.projectId(), failed.detail());
      case Removed removed -> desks.removed(session.runnerId(), removed.projectId());
      default ->
          LOG.debugf(
              "Runner %s sent %s, which the front desk does not handle",
              session.runnerName(), frame.getClass().getSimpleName());
    }
  }
}
