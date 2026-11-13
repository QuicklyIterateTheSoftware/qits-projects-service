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
 * <p><b>The session key spelling</b> ({@link #KEY_PREFIX}, {@link #key}, {@link #nameOfKey}) mirrors
 * {@code eu.wohlben.qits.projectsdaemon.protocol.NamedAgents} (qits-projects-daemon, capability 4),
 * which is not in the protocol release this service is pinned to yet. TODO(qits-2154): once the
 * maintenance bump pins a protocol carrying it, delegate these three to that class.
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

  /** What every named agent's session key starts with on the control socket. */
  public static final String KEY_PREFIX = "agent:";

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

  /** The session (and credential) key of the named agent {@code name}: {@code agent:<name>}. */
  public static String key(String name) {
    return KEY_PREFIX + name;
  }

  /** The name a session key {@code agent:<name>} belongs to, or empty for any other key. */
  public static Optional<String> nameOfKey(String key) {
    if (key == null || !key.startsWith(KEY_PREFIX)) {
      return Optional.empty();
    }
    String name = key.substring(KEY_PREFIX.length());
    return name.isEmpty() ? Optional.empty() : Optional.of(name);
  }
}
