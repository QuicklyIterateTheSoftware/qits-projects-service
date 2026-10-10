package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ReleaseRequestAnnouncer;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The suite's {@link ReleaseRequestAnnouncer}: records what would have been published, so a test can
 * assert both that an event was dispatched and — the harder half — that one was <b>not</b>. An
 * ordinary bean over the {@code @DefaultBean} announcer, so no test reaches the event bus.
 */
@ApplicationScoped
public class RecordingReleaseRequestAnnouncer implements ReleaseRequestAnnouncer {

  /** One announcement, field for field as the port declares it — this IS the wire contract. */
  public record Announced(
      String projectId,
      String repoId,
      String repoName,
      String releaseRequestId,
      String backingBranch,
      String mergedSha,
      Instant changedAt,
      String priority,
      List<String> downstreamTechnicalComponents,
      String preRun) {}

  private final List<Announced> announced = Collections.synchronizedList(new ArrayList<>());

  private final java.util.concurrent.atomic.AtomicInteger failures =
      new java.util.concurrent.atomic.AtomicInteger();

  /** The next {@code times} announcements throw and record nothing — a bus that refused them. */
  public void failNext(int times) {
    failures.set(times);
  }

  public List<Announced> announced() {
    return List.copyOf(announced);
  }

  /** Only the announcements about one request — a shared trigger touches several. */
  public List<Announced> announcedFor(String releaseRequestId) {
    return announced().stream()
        .filter(event -> event.releaseRequestId().equals(releaseRequestId))
        .toList();
  }

  /** Only the announcements that ask qits-ci for a QA run: preRun DONE (or absent). */
  public List<Announced> qaAnnouncedFor(String releaseRequestId) {
    return announcedFor(releaseRequestId).stream()
        .filter(event -> !ReleaseRequestAnnouncer.PRE_RUN_PENDING.equals(event.preRun()))
        .toList();
  }

  public void reset() {
    announced.clear();
    failures.set(0);
  }

  @Override
  public void onReleaseRequestChanged(
      String projectId,
      String repoId,
      String repoName,
      String releaseRequestId,
      String backingBranch,
      String mergedSha,
      Instant changedAt,
      String priority,
      List<String> downstreamTechnicalComponents,
      String preRun) {
    if (failures.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
      throw new IllegalStateException("the bus refused the announcement (test)");
    }
    announced.add(
        new Announced(
            projectId,
            repoId,
            repoName,
            releaseRequestId,
            backingBranch,
            mergedSha,
            changedAt,
            priority,
            downstreamTechnicalComponents,
            preRun));
  }
}
