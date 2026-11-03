package eu.wohlben.qits.projects.startup;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.WrapperReconcileService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Re-reads every project's {@code .config/qits/project.yml} once per boot (qits-767), through
 * {@link WrapperReconcileService#reassertProjectConfig(String)}.
 *
 * <p>The other trigger, a wrapper's {@code main} moving ({@code ReleaseRequests.onMainMoved}), only
 * sees moves this process was up for. A release that landed while the service was down, or one
 * whose read failed, is caught up here: boot is a deployment's one guaranteed "new code is now
 * running" moment, the reason {@link StartupSelfSeed} lives at startup too.
 *
 * <p><b>Each boot restates every wrapper project's {@code ProjectChanged}, moved or not
 * (qits-386).</b> The edge learns {@code supportsEnvironments} and {@code frontDeskLifecycle} only
 * from that event, and the reconcile announces only a move — so a frame lost between a commit and
 * its publish used to be lost for good. The re-assert bounds that loss to the next deploy: a pass
 * that moved a value announces it once, a pass that moved nothing restates what is stored, and a
 * project with no wrapper is told nothing. One frame per project per boot, into a projection that
 * is idempotent on the values.
 *
 * <p>Packaged runs only ({@link LaunchMode#NORMAL}), never under {@code quarkus:dev} or tests — the
 * suite drives {@link #reconcileAll()} directly. It runs on a virtual thread so it never blocks
 * readiness, and each project is reconciled in its own try/catch: one wrapper that cannot be read
 * costs that project and no other, and the next boot (or the next move of its {@code main}) asks
 * again.
 */
@ApplicationScoped
public class ProjectConfigBootPass {

  private static final Logger LOG = Logger.getLogger(ProjectConfigBootPass.class);

  @Inject ProjectService projectService;

  @Inject WrapperReconcileService wrapperReconcile;

  void onStart(@Observes StartupEvent event) {
    if (!shouldRun(LaunchMode.current())) {
      return;
    }
    Thread.ofVirtual().name("qits-project-config-boot-pass").start(this::reconcileAllQuietly);
  }

  /** Packaged runs only — dev and test launch modes never run the pass by themselves. */
  static boolean shouldRun(LaunchMode mode) {
    return mode == LaunchMode.NORMAL;
  }

  void reconcileAllQuietly() {
    try {
      reconcileAll();
    } catch (RuntimeException e) {
      LOG.warn("The project configuration boot pass could not list the projects — retried on the next boot.", e);
    }
  }

  /**
   * Reconciles the project configuration of every project and re-asserts what it announces,
   * logging and skipping a project whose reconcile fails.
   *
   * @return how many projects were reconciled without an error
   */
  public int reconcileAll() {
    List<String> projectIds =
        QuarkusTransaction.requiringNew()
            .call(() -> projectService.list().stream().map(p -> p.id).toList());
    int reconciled = 0;
    for (String projectId : projectIds) {
      try {
        wrapperReconcile.reassertProjectConfig(projectId);
        reconciled++;
      } catch (RuntimeException e) {
        LOG.warnf(
            e,
            "Could not reconcile the project configuration of %s at boot — the next boot or wrapper"
                + " release reads it again.",
            projectId);
      }
    }
    LOG.infof(
        "Project configuration boot pass: %d of %d project(s) reconciled.",
        reconciled, projectIds.size());
    return reconciled;
  }
}
