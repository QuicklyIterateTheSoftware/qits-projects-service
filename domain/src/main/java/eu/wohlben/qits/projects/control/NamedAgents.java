package eu.wohlben.qits.projects.control;

import java.util.List;
import java.util.Optional;

/**
 * <b>The named agents every project has</b> (qits-2154): kept Claude sessions in the project's front
 * desk container, each with its own name, briefing (its own agent surface's configuration),
 * credential and session. A closed registry in code, deliberately: a name is a path segment of the
 * doors, a session key on the control socket and a file name in the container, so a new one is a
 * line here and a surface default in {@link AgentSurfaceDefaults}, never a row somebody types.
 *
 * <ul>
 *   <li>{@link #DESK} — the front desk itself. It keeps its {@code front_desk} row, its keeper in
 *       the daemon and its {@code front-desk/*} routes exactly as before; the agents doors delegate
 *       to those and add only the wake-on-prompt. It never has a {@code named_agent} row.
 *   <li>{@link #UI_BUILDER} — the project's UI builder, on surface {@link
 *       AgentSurfaceDefaults#PROJECT_UI_BUILDER}: never the desk's briefing, skills or triggers.
 * </ul>
 *
 * <p><b>The session key is the wire's, not this registry's</b>: {@code agent:<name>} is spelled
 * by {@code eu.wohlben.qits.projectsdaemon.protocol.NamedAgents} (qits-projects-daemon, capability
 * 4), and the service's hosts ask that class for it ({@code namedAgentKey}, {@code
 * namedAgentName}) rather than a copy here: the daemon holds a name to its {@code NAME} pattern
 * because the name becomes a path segment and a file name, and a second spelling of the rule is the
 * one that drifts. This module does not depend on the daemon's protocol, so every name added below
 * must also match it — {@code NamedAgentsComposeTest} asserts that for each.
 */
public final class NamedAgents {

  /** One named agent: its name and the surface its session launches as. */
  public record Agent(String name, String surface) {

    /** Whether this is the front desk, which keeps its own row and routes. */
    public boolean desk() {
      return DESK.equals(name);
    }
  }

  /** The front desk, as a named agent. */
  public static final String DESK = "desk";

  /** The project's UI builder (qits-2154's pilot). */
  public static final String UI_BUILDER = "ui-builder";

  /** Every named agent, in the order the listing answers them: the desk first. */
  public static final List<Agent> ALL =
      List.of(
          new Agent(DESK, AgentSurfaceDefaults.PROJECT_WORK),
          new Agent(UI_BUILDER, AgentSurfaceDefaults.PROJECT_UI_BUILDER));

  private NamedAgents() {}

  /** The named agent {@code name}, or empty for a name nobody registered. */
  public static Optional<Agent> find(String name) {
    if (name == null) {
      return Optional.empty();
    }
    return ALL.stream().filter(agent -> agent.name().equals(name)).findFirst();
  }

  /** The named agents that are not the desk: the ones with a {@code named_agent} row. */
  public static List<Agent> hosted() {
    return ALL.stream().filter(agent -> !agent.desk()).toList();
  }
}
