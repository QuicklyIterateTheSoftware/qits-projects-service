package eu.wohlben.qits.projects.control;

import java.time.Instant;

/**
 * Tells the rest of the platform that a named agent's computed state changed (qits-2157): STOPPED,
 * STARTING, IDLE, BUSY or FAILED, as {@code deskhost/NamedAgentHost}'s listing spells it — so a
 * page following the UI builder need not poll {@code GET …/agents}.
 *
 * <p>A port, for {@link RepositoryAnnouncer}'s reason: {@code domain} knows no event bus. The one
 * implementation is {@code service/…/bus/NamedAgentStateEventAnnouncer} ({@code @DefaultBean}).
 * <b>Absent is a supported configuration</b>, injected as an {@code Instance<T>}: with none a state
 * change simply announces nothing.
 *
 * <p><b>Never the desk.</b> The desk's state is read from its daemon's {@code last-answer} — one
 * daemon call per read — so it is not observed, and nothing announces it.
 *
 * <p><b>Nothing here may throw</b>, and it is called only after any write that moved the state
 * committed, never inside its transaction.
 */
public interface NamedAgentStateAnnouncer {

  /**
   * One observed change.
   *
   * @param projectId the agent's project
   * @param agentName the registry's name, e.g. {@code ui-builder}; never {@code desk}
   * @param state STOPPED, STARTING, IDLE, BUSY or FAILED
   * @param commandId the daemon's command running it now, or null while none runs
   * @param sessionId its harness session, or null before it ever ran
   * @param changedAt when the change was observed
   */
  record NamedAgentStateChange(
      String projectId,
      String agentName,
      String state,
      String commandId,
      String sessionId,
      Instant changedAt) {}

  /** {@code change} was observed. One call per agent per change. */
  void onNamedAgentStateChanged(NamedAgentStateChange change);
}
