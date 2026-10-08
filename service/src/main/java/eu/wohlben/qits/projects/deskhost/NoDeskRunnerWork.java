package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerMessage;
import eu.wohlben.qits.runner.protocol.Nothing;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The {@link DeskRunnerWork} shipped until the front desk lands (qits-767): every {@code reserve} is
 * answered {@code nothing}, no held desk is adopted, nothing is sent at a greeting and every desk
 * frame is dropped. {@code @DefaultBean}, so the front desk wins the injection point simply by
 * existing.
 */
@ApplicationScoped
@DefaultBean
public class NoDeskRunnerWork implements DeskRunnerWork {

  private static final Logger LOG = Logger.getLogger(NoDeskRunnerWork.class);

  @Override
  public RunnerMessage reserve(DeskRunnerRegistry.Session session) {
    return new Nothing();
  }

  @Override
  public List<String> adopted(DeskRunnerRegistry.Session session, List<String> held) {
    return List.of();
  }

  @Override
  public void greeted(DeskRunnerRegistry.Session session) {}

  @Override
  public void onFrame(DeskRunnerRegistry.Session session, DeskRunnerMessage frame) {
    LOG.debugf(
        "Runner %s sent %s and no front desk is here to read it",
        session.runnerName(), frame.getClass().getSimpleName());
  }
}
