package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>{@code PitchChanged}</b> (qits-2155): a named agent's pitch was created, given a new version,
 * or archived — so a page showing pitches (qits-2158's Original and Pitched tabs) need not poll.
 *
 * <p>Published by {@link PitchEventAnnouncer} after the write committed. The class lives here, not
 * in a vocabulary jar, for {@code RepositoryRenamed}'s reason; a consumer decodes it into a record of
 * its own. No field spells an envelope word ({@code name}, {@code eventId}'s own accessor aside,
 * {@code occurredAt}, {@code signature}): the agent is {@code agentName}, and {@code changedAt} is
 * the envelope's {@code occurredAt}. It is not matchable by {@code qits wait} yet (qits-workspaces'
 * {@code event-fields.json} does not name it).
 *
 * @param projectId the pitch's project
 * @param pitchId the pitch
 * @param agentName the named agent that owns it, e.g. {@code ui-builder}
 * @param repositoryId the repository whose screenshot it pitches
 * @param route the freeze's route
 * @param screenshot the freeze's file key in {@code manifest.json}
 * @param change {@code CREATED}, {@code VERSION_ADDED} or {@code ARCHIVED}
 * @param version the pitch's latest version number after the change
 * @param changedAt when the change committed
 */
public record PitchChanged(
    UUID eventId,
    String projectId,
    String pitchId,
    String agentName,
    String repositoryId,
    String route,
    String screenshot,
    String change,
    int version,
    Instant changedAt)
    implements QitsEvent {

  public PitchChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  @Override
  public Instant occurredAt() {
    return changedAt;
  }
}
