package eu.wohlben.qits.projects.control;

import java.time.Instant;

/**
 * Tells the rest of the platform that a pitch changed (qits-2155): created, a version appended, or
 * archived — so a page showing pitches need not poll.
 *
 * <p>A port, for {@link RepositoryAnnouncer}'s reason: {@code domain} knows no event bus. The one
 * implementation is {@code service/…/bus/PitchEventAnnouncer} ({@code @DefaultBean}). <b>Absent is
 * a supported configuration</b>, injected as an {@code Instance<T>}: with none a pitch write simply
 * announces nothing.
 *
 * <p><b>Nothing here may throw</b>, and it is called only after the write committed, never inside
 * its transaction ({@link Pitches} is the one caller).
 */
public interface PitchAnnouncer {

  /** What happened to the pitch. */
  enum Change {
    CREATED,
    VERSION_ADDED,
    ARCHIVED
  }

  /**
   * One committed change.
   *
   * @param version the pitch's latest version number after the change
   * @param changedAt when the change committed: the row's {@code updated_at}
   */
  record PitchChange(
      String projectId,
      String pitchId,
      String agentName,
      String repositoryId,
      String route,
      String screenshot,
      Change change,
      int version,
      Instant changedAt) {}

  /** {@code change} committed. One call per pitch per change. */
  void onPitchChanged(PitchChange change);
}
