package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.control.NamedAgents;
import eu.wohlben.qits.projects.control.NamedAgentStateAnnouncer.NamedAgentStateChange;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * <b>The last named-agent state announced, per (project, agent)</b> (qits-2157): what {@link
 * NamedAgentHost#observe} hands each observation to, so a {@code NamedAgentStateChanged} goes out
 * only when an agent's (state, commandId) differs from the last one announced for it. In memory
 * only: the first observation after boot announces, whatever it says.
 *
 * <p><b>Never the desk.</b> Its state is read from its daemon's {@code last-answer}, a daemon call
 * per read, so nothing observes it and an observation of it is dropped here too. An agent never
 * announced whose observation is {@code STOPPED} with nothing behind it (no row yet: nobody ever
 * prompted it) is not announced either — every project lists every name, and a page reading the
 * listing reads that much already.
 *
 * <p>Plain, not a bean, so the dedup is a unit test. <b>Never throws</b>: a failed announcement is
 * a WARN, and it is forgotten so the next observation tries again.
 */
final class NamedAgentStates {

  private static final Logger LOG = Logger.getLogger(NamedAgentStates.class);

  /** What was announced last, by {@code <projectId>/<agentName>}. */
  private record Announced(String state, String commandId) {}

  private final ConcurrentHashMap<String, Announced> announced = new ConcurrentHashMap<>();

  private final Consumer<NamedAgentStateChange> announcer;

  NamedAgentStates(Consumer<NamedAgentStateChange> announcer) {
    this.announcer = announcer;
  }

  /**
   * {@code state} of {@code projectId}'s agent was just observed; {@code known} says whether it has
   * a row. Answers whether it was announced.
   */
  boolean observed(String projectId, NamedAgentHost.AgentState state, boolean known, Instant at) {
    if (projectId == null || state == null || NamedAgents.DESK.equals(state.name())) {
      return false;
    }
    String key = projectId + "/" + state.name();
    Announced next = new Announced(state.state(), state.commandId());
    Announced[] previous = new Announced[1];
    boolean[] changed = new boolean[1];
    announced.compute(
        key,
        (k, last) -> {
          previous[0] = last;
          if (Objects.equals(last, next)) {
            return last;
          }
          if (last == null && !known && NamedAgentHost.STOPPED.equals(state.state())) {
            return null; // never prompted, never announced: nothing to say
          }
          changed[0] = true;
          return next;
        });
    if (!changed[0]) {
      return false;
    }
    try {
      announcer.accept(
          new NamedAgentStateChange(
              projectId, state.name(), state.state(), state.commandId(), state.sessionId(), at));
      return true;
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not announce project %s's named agent %s as %s",
          projectId,
          state.name(),
          state.state());
      // Forget it, so the next observation announces again rather than believing this one went.
      announced.compute(key, (k, last) -> next.equals(last) ? previous[0] : last);
      return false;
    }
  }

  /** Forget {@code projectId}'s agents — the project is gone. */
  void forget(String projectId) {
    announced.keySet().removeIf(key -> key.startsWith(projectId + "/"));
  }
}
