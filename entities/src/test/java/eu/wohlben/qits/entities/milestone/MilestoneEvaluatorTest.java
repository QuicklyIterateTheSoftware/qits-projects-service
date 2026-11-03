package eu.wohlben.qits.entities.milestone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.campaign.Observation;
import eu.wohlben.qits.entities.campaign.Observation.DeploymentWentActive;
import eu.wohlben.qits.entities.campaign.Observation.EntityReached;
import eu.wohlben.qits.entities.campaign.Observation.Observed;
import eu.wohlben.qits.entities.campaign.Observation.Released;
import eu.wohlben.qits.entities.control.EntitiesTestSupport;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.TestDrops;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionalException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link MilestoneEvaluator} and the dates and latches {@link MilestoneService} keeps, against the
 * real database (epic qits-615): what latches and with what evidence, the floor at the criterion's
 * own creation, forward-only, the group and milestone dates, the un-meet an edit causes, and the
 * current-state latch. No campaign anywhere: a milestone is evaluated on its own. A {@code
 * @QuarkusTest} on {@link EntitiesTestSupport} with <b>no {@code @TestProfile}</b>.
 */
@QuarkusTest
class MilestoneEvaluatorTest extends EntitiesTestSupport {

  private static final String PROJECT = "proj-milestone-evaluator";
  private static final String WHO = "tester";
  private static final Duration HOUR = Duration.ofHours(1);

  @Inject MilestoneEvaluator evaluator;
  @Inject MilestoneService milestones;
  @Inject WorkEntityService workEntities;

  @Test
  void aReleaseLatchesTheCriterionWithItsEvidenceAndTheMilestoneIsMetAtThatTime() {
    String repo = unique("repo");
    MilestoneService.Edited created = create("bootstrap test node", group(release(repo)));
    String id = created.view().milestone().id;
    assertTrue(created.changes().isEmpty(), "nothing holds yet");
    Observed observed = now(new Released(PROJECT, repo, "2026.1001.93000"));

    MilestonesChanged changes = observe(observed);

    assertEquals(List.of(id), changes.metIds());
    MilestonesChanged.Met met = changes.met().get(0);
    assertEquals(PROJECT, met.projectId());
    assertEquals("bootstrap test node", met.displayName());
    MilestoneService.View view = milestones.get(id);
    assertEquals(view.groups().get(0).group().id, met.groupId());
    assertEquals(micros(observed.occurredAt()), micros(met.satisfiedAt()));
    assertEquals(micros(observed.occurredAt()), micros(view.milestone().satisfiedAt));
    assertEquals(micros(observed.occurredAt()), micros(view.groups().get(0).group().satisfiedAt));
    MilestoneCriterion latched = only(view);
    assertEquals(observed.eventId(), latched.evidenceEventId);
    assertEquals("ReleaseRequestStateChanged", latched.evidenceSignature);
    assertEquals(
        "ReleaseRequestStateChanged: " + repo + " 2026.1001.93000", latched.evidenceSummary);
  }

  @Test
  void theFloorIsTheCriterionsOwnCreationAndNoCampaignIsNeeded() {
    String app = unique("app");
    String id = create("deployed", group(deployment(app))).view().milestone().id;
    Instant createdAt = only(milestones.get(id)).createdAt;

    assertTrue(
        observe(at(new DeploymentWentActive(app, "dev", "1"), createdAt.minus(HOUR))).isEmpty(),
        "a fact from before the criterion was written latches nothing");
    assertNull(only(milestones.get(id)).satisfiedAt);

    assertEquals(
        List.of(id),
        observe(at(new DeploymentWentActive(app, "dev", "1"), createdAt.plusSeconds(1))).metIds(),
        "after it, it lands");
  }

  @Test
  void aLatchIsForwardOnlyAndARedeliveryChangesNothing() {
    String repo = unique("repo");
    String id = create("released", group(release(repo))).view().milestone().id;
    Observed first = now(new Released(PROJECT, repo, "1"));
    assertEquals(List.of(id), observe(first).metIds());
    MilestoneCriterion latched = only(milestones.get(id));

    assertTrue(observe(first).isEmpty(), "the redelivery changes nothing");
    assertTrue(
        observe(now(new Released(PROJECT, repo, "2"))).isEmpty(), "nor does a later event");
    MilestoneCriterion after = only(milestones.get(id));
    assertEquals(first.eventId(), after.evidenceEventId);
    assertEquals(latched.satisfiedAt, after.satisfiedAt);
    assertEquals(latched.evidenceSummary, after.evidenceSummary);
  }

  @Test
  void aGroupHoldsSinceItsLastLatchAndTheMilestoneSinceItsEarliestHoldingGroup() {
    String app = unique("app");
    String repo = unique("repo");
    MilestoneService.Edited created =
        create("both", group(deployment(app), release(repo)), group(approval(null)));
    String id = created.view().milestone().id;
    String firstGroup = created.view().groups().get(0).group().id;
    Instant t1 = Instant.now().plusSeconds(5);
    Instant t2 = t1.plusSeconds(60);

    assertTrue(observe(at(new DeploymentWentActive(app, "dev", "1"), t1)).isEmpty(), "half a group");
    assertNull(milestones.get(id).groups().get(0).group().satisfiedAt);

    MilestonesChanged met = observe(at(new Released(PROJECT, repo, "1"), t2));
    assertEquals(List.of(id), met.metIds());
    assertEquals(firstGroup, met.met().get(0).groupId());
    MilestoneService.View view = milestones.get(id);
    assertEquals(micros(t2), micros(view.groups().get(0).group().satisfiedAt), "its last latch");
    assertEquals(micros(t2), micros(view.milestone().satisfiedAt));

    // The second group comes to hold now — before t2, which lies in the future — so the milestone
    // is met since the earlier of the two, and it was already met: nothing new to announce.
    String approvalId = view.groups().get(1).criteria().get(0).id;
    MilestoneService.Edited approved = milestones.approve(id, approvalId, "ok", "xion");
    assertTrue(approved.changes().isEmpty(), "already met");
    MilestoneService.View after = milestones.get(id);
    Instant secondGroup = after.groups().get(1).group().satisfiedAt;
    assertNotNull(secondGroup);
    assertEquals(micros(secondGroup), micros(after.milestone().satisfiedAt), "the earliest");
  }

  @Test
  void anEditThatUnlatchesTheOnlyHoldingCriterionUnmeetsTheMilestone() {
    String repo = unique("repo");
    MilestoneService.Edited created = create("released", group(release(repo), approval(null)));
    String id = created.view().milestone().id;
    String releaseId = created.view().groups().get(0).criteria().get(0).id;
    String approvalId = created.view().groups().get(0).criteria().get(1).id;
    observe(now(new Released(PROJECT, repo, "1")));
    assertEquals(List.of(id), milestones.approve(id, approvalId, null, "xion").changes().metIds());

    // Restating the approval as it is keeps its latch; rewriting the release clears it.
    MilestoneService.Edited edited =
        milestones.replaceGroups(
            id,
            List.of(group(spec(releaseId, "SCM_RELEASE", Map.of("repositoryName", repo + "-x")), approval(approvalId))),
            WHO);

    assertEquals(List.of(id), edited.changes().unmet().stream().map(MilestonesChanged.Unmet::milestoneId).toList());
    assertTrue(edited.changes().met().isEmpty());
    MilestoneService.View view = milestones.get(id);
    assertNull(view.milestone().satisfiedAt, "met since: cleared when it stops holding");
    assertNull(view.groups().get(0).group().satisfiedAt);
    MilestoneCriterion rewritten = view.groups().get(0).criteria().get(0);
    assertEquals(releaseId, rewritten.id, "the same row, edited");
    assertNull(rewritten.satisfiedAt);
    assertNull(rewritten.evidenceEventId);
    MilestoneCriterion kept = view.groups().get(0).criteria().get(1);
    assertEquals(approvalId, kept.id);
    assertNotNull(kept.satisfiedAt, "a restated criterion keeps its latch");
    assertEquals("xion", kept.approvedBy);

    assertTrue(observe(now(new Released(PROJECT, repo, "2"))).isEmpty(), "the old fact is no news");
    assertEquals(List.of(id), observe(now(new Released(PROJECT, repo + "-x", "1"))).metIds());
  }

  @Test
  void anEntityStatusOnAnItemAlreadyDoneLatchesAtCreateWithCurrentStateEvidence() {
    WorkEntity done = walk(ticket("Done"), "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE");
    WorkEntity refined = walk(ticket("Refined"), "REFINED");

    MilestoneService.Edited created =
        create(
            "done things",
            group(spec(null, "ENTITY_STATUS", Map.of("entityId", done.id, "status", "VERIFIED"))));

    String id = created.view().milestone().id;
    assertEquals(List.of(id), created.changes().metIds(), "met at once");
    MilestoneCriterion latched = only(milestones.get(id));
    assertEquals(MilestoneCriterion.CURRENT_STATE, latched.evidenceSignature);
    assertNull(latched.evidenceEventId);
    assertEquals(
        "#" + done.number + " was already DONE when the criterion was written",
        latched.evidenceSummary);
    assertNotNull(milestones.get(id).milestone().satisfiedAt);

    // An edit naming a target not there yet: unlatched and un-met; the event then latches it.
    String criterionId = latched.id;
    MilestoneService.Edited edited =
        milestones.replaceGroups(
            id,
            List.of(group(spec(criterionId, "ENTITY_STATUS", Map.of("entityId", refined.id, "status", "VERIFIED")))),
            WHO);
    assertEquals(1, edited.changes().unmet().size());
    assertEquals(
        List.of(id),
        observe(now(new EntityReached(refined.id, PROJECT, "VERIFIED", "IMPLEMENTED"))).metIds());
  }

  @Test
  void aTargetOutsideTheProjectIsRefused() {
    WorkEntity elsewhere =
        workEntities
            .create(
                Archetype.TICKET,
                "proj-elsewhere",
                EntityWrite.ticket("Far", "it occurs", null, "BUG", null),
                WHO)
            .entity();
    BadRequestException refused =
        assertThrows(
            BadRequestException.class,
            () ->
                create(
                    "far",
                    group(spec(null, "ENTITY_STATUS", Map.of("entityId", elsewhere.id, "status", "DONE")))));
    assertTrue(refused.getMessage().contains("not a work entity of this milestone's project"));
    assertThrows(BadRequestException.class, () -> milestones.create(PROJECT, "empty", List.of(), WHO));
  }

  @Test
  void anObservationOutsideATransactionIsRefused() {
    Observed observed = now(new Released(PROJECT, "anything", "1"));
    assertThrows(TransactionalException.class, () -> evaluator.observe(observed));
  }

  // --- fixtures ------------------------------------------------------------------------------------

  private MilestoneService.Edited create(String name, MilestoneService.GroupSpec... groups) {
    return milestones.create(PROJECT, name, List.of(groups), WHO);
  }

  private MilestonesChanged observe(Observed observed) {
    return QuarkusTransaction.requiringNew().call(() -> evaluator.observe(observed));
  }

  private static MilestoneCriterion only(MilestoneService.View view) {
    assertEquals(1, view.groups().size());
    assertEquals(1, view.groups().get(0).criteria().size());
    return view.groups().get(0).criteria().get(0);
  }

  private static Observed now(Observation observation) {
    return at(observation, Instant.now());
  }

  private static Observed at(Observation observation, Instant occurredAt) {
    String signature =
        switch (observation) {
          case EntityReached r -> "EntityTransitioned";
          case DeploymentWentActive d -> "DeploymentActive";
          case Released r -> "ReleaseRequestStateChanged";
        };
    // Microseconds, what the column holds (postgres rounds, it does not truncate).
    return new Observed(
        observation, UUID.randomUUID(), signature, occurredAt.truncatedTo(ChronoUnit.MICROS));
  }

  private WorkEntity ticket(String title) {
    return workEntities
        .create(
            Archetype.TICKET,
            PROJECT,
            EntityWrite.ticket(title, "it occurs", null, "BUG", null)
                .withAcceptanceCriteria(EntitiesTestSupport.CRITERIA),
            WHO)
        .entity();
  }

  private WorkEntity walk(WorkEntity row, String... statuses) {
    WorkEntity moved = row;
    for (String status : statuses) {
      moved =
          workEntities
              .transition(row.archetype, row.id, status, Mover.person(WHO), TestDrops.of(status))
              .entity();
    }
    return moved;
  }

  private static Instant micros(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS);
  }

  private static String unique(String prefix) {
    return prefix + "_" + UUID.randomUUID();
  }

  private static MilestoneService.GroupSpec group(MilestoneService.CriterionSpec... criteria) {
    return new MilestoneService.GroupSpec(List.of(criteria));
  }

  private static MilestoneService.CriterionSpec spec(
      String id, String kind, Map<String, Object> predicate) {
    return new MilestoneService.CriterionSpec(id, kind, predicate);
  }

  private static MilestoneService.CriterionSpec approval(String id) {
    return spec(id, "APPROVAL", Map.of());
  }

  private static MilestoneService.CriterionSpec release(String repo) {
    return spec(null, "SCM_RELEASE", Map.of("repositoryName", repo));
  }

  private static MilestoneService.CriterionSpec deployment(String app) {
    Map<String, Object> predicate = new LinkedHashMap<>();
    predicate.put("applicationName", app);
    predicate.put("environmentName", "dev");
    return spec(null, "DEPLOYMENT_ACTIVE", predicate);
  }
}
