package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>{@code NamedAgentStateChanged}</b> (qits-2157): a named agent's computed state — the one
 * {@code GET …/projects/{projectId}/agents} lists — changed, so a page following the UI builder
 * need not poll.
 *
 * <p>Published by {@link NamedAgentStateEventAnnouncer} when {@code deskhost/NamedAgentHost}
 * observes an agent's (state, commandId) differ from the last it announced; the first observation
 * after boot announces. <b>Never for the desk</b>: its state costs a daemon call per read, so it is
 * not observed. The class lives here, not in a vocabulary jar, for {@code RepositoryRenamed}'s
 * reason; a consumer decodes it into a record of its own. No field spells an envelope word ({@code
 * name}, {@code occurredAt}, {@code signature}): the agent is {@code agentName}, and {@code
 * changedAt} is the envelope's {@code occurredAt}. It is not matchable by {@code qits wait} yet.
 *
 * @param projectId the agent's project
 * @param agentName the named agent, e.g. {@code ui-builder}
 * @param state {@code STOPPED}, {@code STARTING}, {@code IDLE}, {@code BUSY} or {@code FAILED}
 * @param commandId the daemon's command running it now, or null while none runs
 * @param sessionId its harness session, or null before it ever ran
 * @param changedAt when the change was observed
 */
public record NamedAgentStateChanged(
    UUID eventId,
    String projectId,
    String agentName,
    String state,
    String commandId,
    String sessionId,
    Instant changedAt)
    implements QitsEvent {

  public NamedAgentStateChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  @Override
  public Instant occurredAt() {
    return changedAt;
  }
}
