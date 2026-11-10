package eu.wohlben.qits.projects.startup;

import eu.wohlben.qits.entities.control.EntityDispatchService;
import eu.wohlben.qits.entities.control.NeedRef;
import eu.wohlben.qits.entities.control.NeedService;
import eu.wohlben.qits.projects.api.PhaseAdvance;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * <b>The pre-approved runs already stuck behind met needs, resumed once at boot</b> (qits-2026).
 * Until qits-2026 a FLOW refine that landed REFINED with a need unmet kept its pre-approval and
 * waited for a re-wake nothing made; {@link PhaseAdvance#resumeHeld} is that re-wake now, called
 * when a need is met and after a needs write, but neither fires again for a row whose needs were
 * met before the fix shipped (qits-2004: REFINED, FLOW, the desk's pre-approval, every need met).
 * This pass is what moves those on by themselves after the deploy.
 *
 * <p>It reads the candidates in one query ({@link
 * EntityDispatchService#preApprovedAtRefinedWithNeeds}: an epic or a ticket, REFINED, FLOW,
 * pre-approved, not blocked, at least one need), their needs in one batch read ({@link
 * NeedService#ofEntities}), and hands every row with none unmet to {@link PhaseAdvance#resumeHeld},
 * which re-reads the row and its needs and judges every condition itself — so a row that moved
 * between the query and the resume is left where it is, and a scheduling another trigger made
 * meanwhile is made once. A row that needs nothing is no candidate: no need ever held it.
 *
 * <p>On a virtual thread, as {@link IdeaStatusReconcile} runs, so boot never waits on it — it can
 * hand turns to qits-workspaces — and in {@link LaunchMode#NORMAL} alone, as {@code FrontDeskSweep}
 * runs: in the suites a row a test is building could be swept from under it, so the tests call
 * {@link #sweep} themselves. A failure is a WARN and the next boot asks again.
 */
@ApplicationScoped
public class HeldPreApprovalSweep {

  private static final Logger LOG = Logger.getLogger(HeldPreApprovalSweep.class);

  @Inject EntityDispatchService dispatchEntities;

  @Inject NeedService needs;

  @Inject PhaseAdvance advance;

  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    Thread.ofVirtual().name("qits-held-pre-approval-sweep").start(this::sweepQuietly);
  }

  void sweepQuietly() {
    try {
      int resumed = sweep();
      if (resumed > 0) {
        LOG.infof("Resumed %d pre-approved run(s) their met needs had left held.", resumed);
      }
    } catch (RuntimeException e) {
      LOG.warn("The held pre-approval sweep could not run — retried on the next boot.", e);
    }
  }

  /**
   * Resumes every pre-approved FLOW run held at REFINED whose needs are all met now.
   *
   * @return how many were handed on to their scheduling
   */
  public int sweep() {
    List<String> candidates = dispatchEntities.preApprovedAtRefinedWithNeeds();
    if (candidates.isEmpty()) {
      return 0;
    }
    Map<String, List<NeedRef>> stated = needs.ofEntities(candidates);
    int resumed = 0;
    for (String id : candidates) {
      List<NeedRef> theirs = stated.getOrDefault(id, List.of());
      if (theirs.isEmpty() || NeedRef.unmet(theirs) > 0) {
        continue;
      }
      if (advance.resumeHeld(id, PhaseAdvance.PLATFORM)) {
        resumed++;
      }
    }
    return resumed;
  }
}
