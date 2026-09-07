package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.testsupport.RecordingProjectAnnouncer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@link ProjectAnnouncer} port {@code ProjectService} fires at both ends of a project's life,
 * at the seam rather than on the wire: <b>did this context say so</b>, once, with the slug the
 * platform edge derives {@code *.<slug>.<domain>} from.
 *
 * <p>The bus is dark under {@code %test} and {@code domain} does not know it exists at all, so the
 * recording fake is the only place these facts are visible. What is asserted here is what the wire
 * cannot say: that every creation path announces, that a refused creation announces nothing, and
 * that a deletion is announced only once the rows are actually gone.
 */
@QuarkusTest
public class ProjectLifecycleAnnouncementTest {

  @Inject ProjectService projectService;

  @Inject ProjectRepository projectRepository;

  @Inject RecordingProjectAnnouncer announcer;

  /** One application, and therefore one recording bean, is shared across the class. */
  @BeforeEach
  void clearRecordings() {
    announcer.clear();
  }

  /**
   * The whole of the create contract: exactly one announcement, carrying the slug the row committed
   * with — not the one the caller asked for, which a derived or suffixed slug need not equal.
   */
  @Test
  public void aCreatedProjectIsAnnouncedOnceWithItsCommittedSlug() {
    Project project = projectService.create("Announce Me", "announce-me", "desc");

    var announced = announcer.createdOf(project.id);
    assertEquals(1, announced.size(), "once per created project");
    assertEquals("announce-me", announced.get(0).slug());
    assertEquals("Announce Me", announced.get(0).name());
    assertNotNull(announced.get(0).occurredAt());
    assertEquals(
        project.slug,
        announced.get(0).slug(),
        "the committed slug, which a derived one need not equal what was asked for");
  }

  /**
   * A creation with no dns record announces too. The registrar is what a record gates; the
   * announcement says a project <em>exists</em>, and the edge's hosts are derived from the slug
   * whether or not this row happens to name a record of its own.
   */
  @Test
  public void aProjectWithoutADnsRecordIsStillAnnounced() {
    Project project = projectService.create("No Record", "no-record", null);

    assertEquals(1, announcer.createdOf(project.id).size());
  }

  /**
   * A derived slug is what travels, not the display name it came from — the edge would otherwise
   * build hosts out of a label that may change and that no repository is named after.
   */
  @Test
  public void aDerivedSlugIsWhatTravels() {
    Project project = projectService.create("Derived Name Here", null);

    assertEquals(project.slug, announcer.createdOf(project.id).get(0).slug());
  }

  /**
   * A refused creation announces nothing. A reserved slug is refused before the transaction opens,
   * so there is no row and there must be no event either — an announcement for a project that does
   * not exist would have the edge issue a certificate for a host nothing serves.
   */
  @Test
  public void aRefusedCreateAnnouncesNothing() {
    assertThrows(
        BadRequestException.class, () -> projectService.create("Projects", "projects", null));
    assertThrows(BadRequestException.class, () -> projectService.create(null, null));

    assertTrue(announcer.created().isEmpty(), "nothing was created, so nothing may be announced");
  }

  /**
   * The row is committed before the port is called, asserted the only way a test can see it: read
   * the project back in a <b>fresh</b> transaction, where an uncommitted row would not be visible.
   */
  @Test
  public void theAnnouncementFollowsTheCreatingTransaction() {
    Project project = projectService.create("Committed First", "committed-first", null);

    String projectId = announcer.createdOf(project.id).get(0).projectId();
    Project readBack = QuarkusTransaction.requiringNew().call(() -> projectService.get(projectId));
    assertEquals("committed-first", readBack.slug);
  }

  /**
   * A created row is born <b>announced</b>: {@code announced_at} is stamped by the insert, because
   * the create path publishes the moment it commits. Without that, every new project would land on
   * the backfill's list and be announced a second time at the next boot.
   */
  @Test
  public void aCreatedRowIsStampedAsAnnounced() {
    Project project = projectService.create("Stamped", "stamped", null);

    Project readBack = QuarkusTransaction.requiringNew().call(() -> projectService.get(project.id));
    assertNotNull(readBack.announcedAt, "a create announces immediately, so the row is stamped");
    assertTrue(
        QuarkusTransaction.requiringNew().call(() -> projectRepository.listUnannounced()).stream()
            .noneMatch(p -> p.id.equals(project.id)),
        "and so it is not on the backfill's list");
  }

  /**
   * The delete contract: announced with the slug the project held — after the rows are gone, so a
   * consumer that goes looking finds nothing rather than something half deleted.
   */
  @Test
  public void aDeletedProjectIsAnnouncedAfterTheRowsAreGone() {
    Project project = projectService.create("Delete And Tell", "delete-and-tell", null);
    announcer.clear();

    projectService.delete(project.id);

    var announced = announcer.deletionOf(project.id).orElseThrow();
    assertEquals("delete-and-tell", announced.slug(), "the slug the edge keyed its hosts on");
    assertNotNull(announced.occurredAt());
    assertThrows(
        NotFoundException.class,
        () -> QuarkusTransaction.requiringNew().call(() -> projectService.get(project.id)),
        "the announcement is made after the delete commits, not before it");
  }

  /** A delete that finds nothing to delete announces nothing. */
  @Test
  public void aDeleteOfAMissingProjectAnnouncesNothing() {
    assertThrows(NotFoundException.class, () -> projectService.delete("no-such-project"));

    assertTrue(announcer.deleted().isEmpty());
  }
}
