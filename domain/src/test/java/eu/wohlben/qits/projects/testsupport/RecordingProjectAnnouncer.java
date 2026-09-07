package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.ProjectAnnouncer;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A TEST-SCOPE implementation of the {@link ProjectAnnouncer} port that records the announcements
 * instead of publishing them.
 *
 * <p>It is what lets a suite assert the one thing about a project's lifecycle that is not a row:
 * that the platform was told, once, with the slug the edge derives its hosts from. The bus itself is
 * dark under {@code %test} — {@code QitsEventBus.publish} is a debug log with the switch off — so
 * without this fake "an event was announced" and "nothing happened" would look identical.
 *
 * <p>An ordinary bean and not an {@code @Alternative}: {@code service}'s shipped implementation is
 * {@code @DefaultBean}, so this one wins the port's injection point simply by existing, exactly as
 * {@link RecordingRepositoryAnnouncer} does. The rule that decides between the two shapes is the
 * port's return type — every method here is {@code void}, so nothing has to be scripted and nothing
 * competes to give the one answer. Nothing in {@code src/main} references it.
 */
@ApplicationScoped
public class RecordingProjectAnnouncer implements ProjectAnnouncer {

  /** One creation announcement, as the port stated it. */
  public record Created(String projectId, String slug, String name, Instant occurredAt) {}

  /** One deletion announcement, as the port stated it. */
  public record Deleted(String projectId, String slug, Instant occurredAt) {}

  private final List<Created> created = new ArrayList<>();

  private final List<Deleted> deleted = new ArrayList<>();

  @Override
  public synchronized void onProjectCreated(
      String projectId, String slug, String name, Instant occurredAt) {
    created.add(new Created(projectId, slug, name, occurredAt));
  }

  @Override
  public synchronized void onProjectDeleted(String projectId, String slug, Instant occurredAt) {
    deleted.add(new Deleted(projectId, slug, occurredAt));
  }

  /** Every creation announced, in the order they were made. */
  public synchronized List<Created> created() {
    return List.copyOf(created);
  }

  /** Every deletion announced, in the order they were made. */
  public synchronized List<Deleted> deleted() {
    return List.copyOf(deleted);
  }

  /** Every creation announced about {@code projectId} — a count of one is the usual assertion. */
  public synchronized List<Created> createdOf(String projectId) {
    return created.stream().filter(c -> c.projectId().equals(projectId)).toList();
  }

  /** The deletion announced about {@code projectId}, if there was one. */
  public synchronized Optional<Deleted> deletionOf(String projectId) {
    return deleted.stream().filter(d -> d.projectId().equals(projectId)).findFirst();
  }

  /**
   * Forgets everything recorded so far. {@code @QuarkusTest} shares one application — and therefore
   * one instance of this bean — across a class, so a test that counts announcements has to start
   * from a known state.
   */
  public synchronized void clear() {
    created.clear();
    deleted.clear();
  }
}
