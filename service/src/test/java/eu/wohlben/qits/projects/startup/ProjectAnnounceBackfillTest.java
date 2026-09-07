package eu.wohlben.qits.projects.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.testsupport.RecordingProjectAnnouncer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The boot-time catch-up for projects created before {@code ProjectCreated} existed — the case no
 * create path can ever fix, because a project is created once and those creations are in the past.
 *
 * <p>The state is staged by persisting a row directly with a null {@code announced_at}, which is the
 * point: {@code ProjectService.create} stamps the column on the insert, so the only way a platform
 * holds an unannounced project is for the row to predate V15.
 *
 * <p>The backfill is driven by hand rather than by booting, the arrangement {@code
 * ScheduledBackupSweep} and {@code StartupSelfSeed} make with their own gates: the observer stands
 * down under {@code %test} because {@code qits.eventstream.enabled=false} there, and a stamp written
 * against an announcement that never left the process would be the one unrecoverable outcome this
 * class is arranged to avoid.
 */
@QuarkusTest
public class ProjectAnnounceBackfillTest {

  @Inject ProjectAnnounceBackfill backfill;

  @Inject ProjectRepository projectRepository;

  @Inject RecordingProjectAnnouncer announcer;

  @BeforeEach
  void clearRecordings() {
    announcer.clear();
  }

  /** A platform with nothing owed announces nothing and says nothing about it. */
  @Test
  public void aPlatformWithNothingUnannouncedDoesNothing() {
    assertEquals(0, backfill.backfill());
    assertTrue(announcer.created().isEmpty());
  }

  /** One announcement per unstamped row, carrying that row's slug, and the row is stamped. */
  @Test
  public void everyUnannouncedProjectIsAnnouncedOnceAndStamped() {
    String id = persistUnannounced("predates-the-event");

    assertEquals(1, backfill.backfill());

    var announced = announcer.createdOf(id);
    assertEquals(1, announced.size());
    assertEquals("predates-the-event", announced.get(0).slug());
    assertNotNull(announced.get(0).occurredAt());
    assertNotNull(
        QuarkusTransaction.requiringNew().call(() -> projectRepository.findById(id)).announcedAt,
        "publish then stamp: the row carries the stamp once the announcement has been made");
  }

  /**
   * The stamp is what makes this a one-off. A second run finds nothing — which is the whole reason
   * V15 exists, since without it every boot would re-announce every project on the platform.
   */
  @Test
  public void aSecondRunAnnouncesNothing() {
    String id = persistUnannounced("announced-once");

    assertEquals(1, backfill.backfill());
    announcer.clear();

    assertEquals(0, backfill.backfill());
    assertTrue(
        announcer.createdOf(id).isEmpty(), "a stamped row is never announced a second time");
  }

  /** Several owed projects are all caught up in one pass, and each is stamped. */
  @Test
  public void everyOwedProjectIsCaughtUpInOnePass() {
    String first = persistUnannounced("owed-one");
    String second = persistUnannounced("owed-two");

    assertEquals(2, backfill.backfill());

    assertEquals(1, announcer.createdOf(first).size());
    assertEquals(1, announcer.createdOf(second).size());
    assertTrue(
        QuarkusTransaction.requiringNew().call(() -> projectRepository.listUnannounced()).isEmpty());
  }

  /** A row the way it looked before V15 stamped anything: no {@code announced_at}. */
  private String persistUnannounced(String slug) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = id;
              project.name = slug;
              project.slug = slug;
              projectRepository.persist(project);
            });
    return id;
  }
}
