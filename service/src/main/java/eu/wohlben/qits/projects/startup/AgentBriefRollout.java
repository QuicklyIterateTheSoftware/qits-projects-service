package eu.wohlben.qits.projects.startup;

import eu.wohlben.qits.projects.control.AgentSurfaceConfigurationService;
import eu.wohlben.qits.projects.control.AgentSurfaceDefaults;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Rolls the workspace agents' brief on asking the delivery desk (qits-1679) onto stored rows that
 * nobody edited, the way {@link DeliveryDeskBriefRollout} does for the desk.
 *
 * <p>The four workspace surfaces shipped an empty prompt before, and the two composed runs {@link
 * AgentSurfaceDefaults#COMPOSED_RUN_PROMPT} alone. A row holding exactly that text is rewritten to
 * what ships now; any other text is an owner's edit and stays. Idempotent, never fails or blocks
 * boot, and the suite drives {@link #rollOut()} directly.
 */
@ApplicationScoped
public class AgentBriefRollout {

  private static final Logger LOG = Logger.getLogger(AgentBriefRollout.class);

  /** Each surface, with the texts it shipped before the current one, oldest first. */
  static final Map<String, List<String>> PREVIOUS_BRIEFS = previous();

  @Inject AgentSurfaceConfigurationService surfaces;

  void onStart(@Observes StartupEvent event) {
    Thread.ofVirtual().name("agent-brief-rollout").start(this::rollOut);
  }

  /** Rewrite each unedited row to its shipped brief. Answers how many rows were rewritten. */
  public int rollOut() {
    int rewritten = 0;
    for (Map.Entry<String, List<String>> entry : PREVIOUS_BRIEFS.entrySet()) {
      String surface = entry.getKey();
      String current = AgentSurfaceDefaults.shippedDefault(surface).systemPrompt();
      try {
        for (String previous : entry.getValue()) {
          if (surfaces.replaceSystemPromptIfUnedited(surface, previous, current)) {
            rewritten++;
            LOG.infof("Rewrote %s's system prompt to the shipped brief (qits-1679)", surface);
            break;
          }
        }
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not roll out the brief to %s; will retry next boot", surface);
      }
    }
    return rewritten;
  }

  private static Map<String, List<String>> previous() {
    Map<String, List<String>> all = new LinkedHashMap<>();
    AgentSurfaceDefaults.WORKSPACE_SURFACES.forEach(s -> all.put(s, List.of("")));
    AgentSurfaceDefaults.COMPOSED_SURFACES.forEach(
        s -> all.put(s, List.of(AgentSurfaceDefaults.COMPOSED_RUN_PROMPT)));
    return Map.copyOf(all);
  }
}
