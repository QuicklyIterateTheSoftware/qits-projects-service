package eu.wohlben.qits.projects.desktriggers;

import static eu.wohlben.qits.projects.desktriggers.DeskTriggerTestFrames.commented;
import static eu.wohlben.qits.projects.desktriggers.DeskTriggerTestFrames.entity;
import static eu.wohlben.qits.projects.desktriggers.DeskTriggerTestFrames.requirement;
import static eu.wohlben.qits.projects.desktriggers.DeskTriggerTestFrames.transitioned;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.ReleaseRequestNumbers;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer {@code projects-desk-triggers}</b> (qits-1609): which frames open or join a
 * window, and which the desk is not told about. The bus is dark in {@code %test}, so the frames are
 * handed to the listener bean directly, built through the real {@code CanonicalJson}.
 */
@QuarkusTest
class DeskTriggerListenerTest {

  @Inject DeskTriggerListener listener;

  @Inject ReleaseRequestNumbers releaseRequestNumbers;

  private final List<String> projects = new ArrayList<>();

  @AfterEach
  void drop() {
    DeskTriggerFixtures.drop(projects);
    projects.clear();
  }

  private String project(boolean deliveryEnabled) {
    String id = DeskTriggerFixtures.project(deliveryEnabled);
    projects.add(id);
    return id;
  }

  // ---- the subscription -------------------------------------------------------------------------

  @Test
  void itSubscribesToTheUnionOfItsSourcesUnderItsOwnConsumerId() {
    assertEquals("projects-desk-triggers", listener.consumerId());
    assertEquals(
        Set.of(
            "EntityTransitioned", "ApprovalRequirementChanged", "DeskWaitClosed", "EntityCommented"),
        listener.signatures());
    assertTrue(!listener.replayFromEpoch(), "a desk is told what happens from now on");
  }

  // ---- work-verified ----------------------------------------------------------------------------

  @Test
  void aTicketMovedToVerifiedOpensAWindow() {
    String projectId = project(true);

    listener.onFrame(
        transitioned(entity(projectId, "TICKET", 1580, "Ship it", "VERIFYING", "VERIFIED", "dana")));

    assertEquals(1, DeskTriggerFixtures.windows(projectId).size());
    DeskTriggerEvent line = DeskTriggerFixtures.eventsOf(projectId).get(0);
    assertEquals("work-verified", line.source);
    assertEquals(DeskTriggerFixtures.slugOf(projectId) + "-1580", line.itemRef);
    assertEquals("Ship it", line.label);
    assertEquals("dana", line.cause);
  }

  @Test
  void anEpicMovedToVerifiedQualifiesToo() {
    String projectId = project(true);

    listener.onFrame(
        transitioned(entity(projectId, "EPIC", 7, "The epic", "IMPLEMENTED", "VERIFIED", "dana")));

    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size());
  }

  @Test
  void aPieceAMoveToDoneAndAnUnmovedVerifiedOpenNothing() {
    String projectId = project(true);

    listener.onFrame(
        transitioned(
            entity(projectId, "FEATURE", 1, "a feature", "VERIFYING", "VERIFIED", "dana"),
            entity(projectId, "TASK", 2, "a task", "VERIFYING", "VERIFIED", "dana"),
            entity(projectId, "TICKET", 3, "done", "VERIFIED", "DONE", "dana"),
            entity(projectId, "TICKET", 4, "reshaped", "VERIFIED", "VERIFIED", "dana")));

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  // ---- approval-needed --------------------------------------------------------------------------

  @Test
  void anOpenRequirementAdmittingTheDeskJoinsTheWindow() {
    String projectId = project(true);
    String requestId = releaseRequest(projectId, "Release qits-1597 wrapper");

    listener.onFrame(
        transitioned(entity(projectId, "TICKET", 1580, "Ship it", "VERIFYING", "VERIFIED", "dana")));
    listener.onFrame(
        requirement(
            projectId,
            requestId,
            "qits-qits-rr-201",
            "manual-review",
            "configured by manual-review",
            List.of("person", "desk"),
            "OPEN"));

    assertEquals(1, DeskTriggerFixtures.windows(projectId).size(), "one window, joined");
    DeskTriggerEvent line =
        DeskTriggerFixtures.eventsOf(projectId).stream()
            .filter(event -> event.source.equals("approval-needed"))
            .findFirst()
            .orElseThrow();
    assertEquals("qits-qits-rr-201", line.itemRef);
    assertEquals(requestId, line.itemId);
    assertEquals(
        "Release qits-1597 wrapper; waits on manual-review: configured by manual-review",
        line.label);
    assertEquals("fold 412911eb", line.cause);
  }

  @Test
  void twoOpenRequirementsOfOneFoldAreTwoLinesAndAMissingSummaryIsLeftOut() {
    String projectId = project(true);
    String requestId = UUID.randomUUID().toString();

    for (String requirementId : List.of("manual-review", "no-changes")) {
      listener.onFrame(
          requirement(
              projectId, requestId, "p-rr-1", requirementId, "why", List.of("desk"), "OPEN"));
    }

    List<String> labels =
        DeskTriggerFixtures.eventsOf(projectId).stream().map(event -> event.label).sorted().toList();
    assertEquals(List.of("waits on manual-review: why", "waits on no-changes: why"), labels);
  }

  @Test
  void aRequirementOnlyAPersonMayAnswerOrOneNoLongerOpenAdmitsNothing() {
    String projectId = project(true);
    String requestId = UUID.randomUUID().toString();

    listener.onFrame(
        requirement(projectId, requestId, "p-rr-1", "manual-review", "r", List.of("person"), "OPEN"));
    for (String state : List.of("SATISFIED", "VOID", "DECLINED")) {
      listener.onFrame(
          requirement(projectId, requestId, "p-rr-1", "manual-review", "r", List.of("desk"), state));
    }

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  // ---- desk-mentioned (qits-1660) ---------------------------------------------------------------

  @Test
  void aCommentToTheDeskJoinsTheWindow() {
    String projectId = project(true);

    listener.onFrame(
        commented(projectId, 770, "Desk triggers", "tok-agent", "qits-12", "tok-agent", "desk"));

    DeskTriggerEvent line = DeskTriggerFixtures.eventsOf(projectId).get(0);
    assertEquals("desk-mentioned", line.source);
    assertEquals(DeskTriggerFixtures.slugOf(projectId) + "-770", line.itemRef);
    assertEquals("Desk triggers; from qits-12's agent", line.label);
    assertEquals("tok-agent", line.cause);
  }

  @Test
  void twoCommentsByOneAuthorOnOneItemAreOneLine() {
    String projectId = project(true);

    listener.onFrame(commented(projectId, 5, "t", "dana", null, "dana", "desk"));
    listener.onFrame(commented(projectId, 5, "t", "dana", null, "dana", "bob", "desk"));

    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size());
    assertEquals("t; from dana", DeskTriggerFixtures.eventsOf(projectId).get(0).label);
  }

  @Test
  void aCommentThatDoesNotAddressTheDeskAdmitsNothing() {
    String projectId = project(true);

    listener.onFrame(commented(projectId, 5, "t", "dana", null, "dana"));
    listener.onFrame(commented(projectId, 5, "t", "dana", null, "dana", "desk-admin", "bob"));

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  @Test
  void aCommentTheDeskWroteItselfAdmitsNothing() {
    String projectId = project(true);
    DeskTriggerFixtures.desk(projectId, "tok-desk", "tok-delivery", "tok-session");

    for (String subject : List.of("tok-delivery", "tok-session", "tok-desk")) {
      listener.onFrame(commented(projectId, 5, "t", subject, null, subject, "desk"));
    }

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  // ---- what the desk is not told ----------------------------------------------------------------

  @Test
  void aMoveTheDeskMadeItselfAdmitsNothing() {
    String projectId = project(true);
    DeskTriggerFixtures.desk(projectId, "tok-desk", "tok-delivery", "tok-session");

    for (String subject : List.of("tok-delivery", "tok-session", "tok-desk")) {
      listener.onFrame(
          transitioned(entity(projectId, "TICKET", 9, "mine", "VERIFYING", "VERIFIED", subject)));
    }
    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());

    listener.onFrame(
        transitioned(entity(projectId, "TICKET", 9, "theirs", "VERIFYING", "VERIFIED", "dana")));
    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size(), "a person's move still counts");
  }

  @Test
  void aProjectWithDeliveryOffAdmitsNothing() {
    String projectId = project(false);

    listener.onFrame(
        transitioned(entity(projectId, "TICKET", 1, "t", "VERIFYING", "VERIFIED", "dana")));
    listener.onFrame(
        requirement(
            projectId, UUID.randomUUID().toString(), "p-rr-1", "m", "r", List.of("desk"), "OPEN"));

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  @Test
  void anUnreadablePayloadIsWarnedAboutAndSkipped() {
    String projectId = project(true);
    EventFrame poison =
        new EventFrame(
            UUID.randomUUID().toString(),
            "EntityTransitioned",
            Instant.now(),
            "{not json",
            null,
            null,
            null);

    listener.onFrame(poison);

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  @Test
  void aRedeliveredFrameIsOneLine() {
    String projectId = project(true);
    EventFrame frame =
        transitioned(entity(projectId, "TICKET", 5, "twice", "VERIFYING", "VERIFIED", "dana"));

    listener.onFrame(frame);
    listener.onFrame(frame);

    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size());
  }

  // -------------------------------------------------------------------------------------------

  /** A release request of a fresh repository of the project, carrying {@code summary}. */
  private String releaseRequest(String projectId, String summary) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Repository repository = new Repository();
              repository.id = "desk-trigger-repo-" + UUID.randomUUID();
              repository.project = Project.findById(projectId);
              repository.mainBranch = "main";
              repository.persist();
              ReleaseRequest row = new ReleaseRequest();
              row.id = id;
              row.repoId = repository.id;
              row.projectId = projectId;
              row.repoName = "qits-qits";
              row.state = ReleaseRequest.State.PENDING;
              row.retryable = false;
              row.armedAt = Instant.now();
              row.summary = summary;
              row.createdAt = Instant.now();
              row.updatedAt = Instant.now();
              row.name(releaseRequestNumbers.next(row.repoId));
              row.persist();
            });
    return id;
  }
}
