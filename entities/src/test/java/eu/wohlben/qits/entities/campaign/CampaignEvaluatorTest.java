package eu.wohlben.qits.entities.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.campaign.Observation.DeploymentWentActive;
import eu.wohlben.qits.entities.campaign.Observation.EntityReached;
import eu.wohlben.qits.entities.campaign.Observation.Observed;
import eu.wohlben.qits.entities.campaign.Observation.Released;
import eu.wohlben.qits.entities.control.EntitiesTestSupport;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionalException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link CampaignEvaluator} against the real database: what latches, with what evidence, what the
 * observation answers — and above all the near misses, one per field and per gate, for every kind.
 * A {@code @QuarkusTest} on {@link EntitiesTestSupport} with <b>no {@code @TestProfile}</b> — the
 * test-profile budget rule.
 */
@QuarkusTest
class CampaignEvaluatorTest extends EntitiesTestSupport {

  private static final String PROJECT = "proj-campaign-evaluator";
  private static final String WHO = "tester";
  private static final Duration HOUR = Duration.ofHours(1);

  @Inject CampaignEvaluator evaluator;
  @Inject CampaignService campaigns;
  @Inject WorkEntityService workEntities;
  @Inject CampaignStartRecordRepository starts;
  @Inject EntityMembershipRepository memberships;

  /** One campaign, one waiting membership with one criterion of a kind, and the fact it waits on. */
  private record Fixture(WorkEntity campaign, String membershipId, Observation hit) {}

  // --- ENTITY_STATUS -------------------------------------------------------------------------------

  @Test
  void aTransitionLatchesTheSeedWithItsEvidenceAndAnswersTheWaitingMember() {
    WorkEntity campaign = campaign();
    WorkEntity a = ticket("A");
    campaigns.addMember(campaign.id, a.id, null, false, WHO);
    String waiting = campaigns.addMember(campaign.id, ticket("B").id, null, false, WHO).membership().id;
    walk(campaign, "REFINED");
    start(campaign.id, Instant.now().minus(HOUR), true);
    UUID eventId = UUID.randomUUID();

    List<String> satisfied =
        observe(
            new Observed(
                new EntityReached(a.id, PROJECT, "VERIFIED", "IMPLEMENTED"),
                eventId,
                "EntityTransitioned",
                Instant.now()));

    assertEquals(List.of(waiting), satisfied);
    CampaignCriterion seed = only(campaign, waiting);
    assertNotNull(seed.satisfiedAt);
    assertEquals(eventId, seed.evidenceEventId);
    assertEquals("EntityTransitioned", seed.evidenceSignature);
    assertEquals(
        "EntityTransitioned: #" + a.number + " IMPLEMENTED → VERIFIED", seed.evidenceSummary);
  }

  @Test
  void entityStatusNearMisses() {
    Fixture f = fixture(CriterionKind.ENTITY_STATUS);
    EntityReached hit = (EntityReached) f.hit();

    assertMisses(f, new EntityReached(UUID.randomUUID().toString(), PROJECT, "VERIFIED", "IMPLEMENTED"));
    assertMisses(f, new EntityReached(hit.entityId(), PROJECT, "DONE", "VERIFIED"));
    assertMisses(f, new EntityReached(hit.entityId(), PROJECT, "VERIFIED", "VERIFIED"));
    assertMisses(f, new Released(PROJECT, hit.entityId(), "1"));

    assertEquals(List.of(f.membershipId()), observe(now(hit)), "and the hit still lands after them");
  }

  // --- DEPLOYMENT_ACTIVE ---------------------------------------------------------------------------

  @Test
  void deploymentActiveNearMissesThenTheFloorItself() {
    Fixture f = fixture(CriterionKind.DEPLOYMENT_ACTIVE);
    DeploymentWentActive hit = (DeploymentWentActive) f.hit();

    assertMisses(f, new DeploymentWentActive(hit.applicationName() + "-x", "dev", "2026.930.10"));
    assertMisses(f, new DeploymentWentActive(hit.applicationName(), "prod", "2026.930.10"));
    assertMisses(f, new DeploymentWentActive(hit.applicationName(), "dev", "2026.930.9"));
    assertMisses(f, new DeploymentWentActive(hit.applicationName(), "dev", ""));
    assertMisses(f, new Released(PROJECT, hit.applicationName(), "2026.930.10"));

    assertEquals(List.of(f.membershipId()), observe(now(hit)), "exactly at the floor");
    assertEquals(
        "DeploymentActive: " + hit.applicationName() + " 2026.930.10 in dev",
        only(f.campaign(), f.membershipId()).evidenceSummary);
  }

  // --- SCM_RELEASE ---------------------------------------------------------------------------------

  @Test
  void scmReleaseNearMissesThenTheFloorItself() {
    Fixture f = fixture(CriterionKind.SCM_RELEASE);
    Released hit = (Released) f.hit();

    assertMisses(f, new Released(PROJECT, hit.repositoryName() + "-x", "2026.1001.93000"));
    assertMisses(f, new Released("proj-other", hit.repositoryName(), "2026.1001.93000"));
    assertMisses(f, new Released(PROJECT, hit.repositoryName(), "2026.1001.92999"));
    assertMisses(f, new Released(PROJECT, hit.repositoryName(), null));
    assertMisses(f, new DeploymentWentActive(hit.repositoryName(), null, "2026.1001.93000"));

    assertEquals(List.of(f.membershipId()), observe(now(hit)));
    assertEquals(
        "SCMRelease: " + hit.repositoryName() + " 2026.1001.93000",
        only(f.campaign(), f.membershipId()).evidenceSummary);
  }

  // --- the gates, for every kind -------------------------------------------------------------------

  @Test
  void aFactFromBeforeTheFirstStartLatchesNothing() {
    for (CriterionKind kind : EVENT_KINDS) {
      Fixture f = fixture(kind, Instant.now().plus(HOUR), true);
      assertMisses(f, f.hit(), Instant.now().plus(Duration.ofMinutes(30)));
      assertEquals(
          List.of(f.membershipId()),
          observe(at(f.hit(), Instant.now().plus(HOUR).plusSeconds(1))),
          kind + ": after the start it lands");
    }
  }

  @Test
  void aFactFromBeforeTheCriterionWasWrittenLatchesNothing() {
    for (CriterionKind kind : EVENT_KINDS) {
      // Started long ago, but the criterion is new: the floor is the later of the two.
      Fixture f = fixture(kind, Instant.now().minus(HOUR.multipliedBy(2)), true);
      assertMisses(f, f.hit(), Instant.now().minus(HOUR));
    }
  }

  @Test
  void aClaimedMembershipIsNoCandidate() {
    for (CriterionKind kind : EVENT_KINDS) {
      Fixture f = fixture(kind);
      QuarkusTransaction.requiringNew()
          .run(() -> memberships.findById(f.membershipId()).claimedAt = Instant.now());
      assertMisses(f, f.hit());
    }
  }

  @Test
  void aDroppedCampaignLatchesNothing() {
    for (CriterionKind kind : EVENT_KINDS) {
      Fixture f = fixture(kind);
      walk(f.campaign(), "DROPPED");
      assertMisses(f, f.hit());
    }
  }

  @Test
  void aCampaignNeverStartedLatchesNothingAndAPausedOneStillDoes() {
    for (CriterionKind kind : EVENT_KINDS) {
      Fixture never = fixture(kind, null, false);
      assertMisses(never, never.hit());

      Fixture paused = fixture(kind, Instant.now().minus(HOUR), false);
      assertEquals(
          List.of(paused.membershipId()),
          observe(now(paused.hit())),
          kind + ": a latch records a fact, not a dispatch");
    }
  }

  @Test
  void aLatchedCriterionStaysLatchedWhenTheSameEventArrivesAgain() {
    for (CriterionKind kind : EVENT_KINDS) {
      Fixture f = fixture(kind);
      Observed first = now(f.hit());
      assertEquals(List.of(f.membershipId()), observe(first));
      CampaignCriterion latched = only(f.campaign(), f.membershipId());

      assertTrue(observe(first).isEmpty(), kind + ": the redelivery changes nothing");
      assertTrue(observe(now(f.hit())).isEmpty(), kind + ": nor does a second event of the fact");
      CampaignCriterion after = only(f.campaign(), f.membershipId());
      assertEquals(first.eventId(), after.evidenceEventId);
      assertEquals(latched.satisfiedAt, after.satisfiedAt);
      assertEquals(latched.evidenceSummary, after.evidenceSummary);
    }
  }

  // --- the DNF -------------------------------------------------------------------------------------

  @Test
  void aMemberIsAnsweredOnlyOnceEveryCriterionOfSomeGroupHasLatched() {
    WorkEntity campaign = walk(campaign(), "REFINED");
    String member = campaigns.addMember(campaign.id, ticket("W").id, null, false, WHO).membership().id;
    String app = unique("app");
    String repo = unique("repo");
    campaigns.setCondition(
        campaign.id,
        member,
        List.of(
            group(deployment(app, null, null), release(repo, null, null)),
            group(new CampaignService.CriterionSpec(null, "APPROVAL", Map.of()))),
        WHO);
    start(campaign.id, Instant.now().minus(HOUR), true);

    assertTrue(
        observe(now(new DeploymentWentActive(app, "dev", "1"))).isEmpty(),
        "half of the first group, and the second is an approval nobody gave");
    assertEquals(List.of(member), observe(now(new Released(PROJECT, repo, "1"))));
  }

  // --- the member's own transition -----------------------------------------------------------------

  @Test
  void theSatisfiedUnclaimedMembershipsOfAMemberAreThoseOfStartedLiveCampaigns() {
    WorkEntity a = ticket("A");
    WorkEntity started = walk(campaign(), "REFINED");
    String inStarted = campaigns.addMember(started.id, a.id, null, false, WHO).membership().id;
    start(started.id, Instant.now().minus(HOUR), true);
    WorkEntity notStarted = campaign();
    campaigns.addMember(notStarted.id, a.id, null, false, WHO);
    WorkEntity dropped = walk(campaign(), "REFINED");
    campaigns.addMember(dropped.id, a.id, null, false, WHO);
    start(dropped.id, Instant.now().minus(HOUR), true);
    walk(dropped, "DROPPED");
    WorkEntity claimed = walk(campaign(), "REFINED");
    String inClaimed = campaigns.addMember(claimed.id, a.id, null, false, WHO).membership().id;
    start(claimed.id, Instant.now().minus(HOUR), true);
    QuarkusTransaction.requiringNew()
        .run(() -> memberships.findById(inClaimed).claimedAt = Instant.now());
    // B waits on A in the started campaign, and nothing has latched it.
    WorkEntity b = ticket("B");
    campaigns.addMember(started.id, b.id, null, false, WHO);

    assertEquals(
        List.of(inStarted),
        QuarkusTransaction.requiringNew().call(() -> evaluator.satisfiedUnclaimedMembershipsOf(a.id)));
    assertTrue(
        QuarkusTransaction.requiringNew()
            .call(() -> evaluator.satisfiedUnclaimedMembershipsOf(b.id))
            .isEmpty(),
        "B's condition does not hold");
  }

  @Test
  void anObservationOutsideATransactionIsRefused() {
    Observed observed = now(new Released(PROJECT, "anything", "1"));
    assertThrows(TransactionalException.class, () -> evaluator.observe(observed));
  }

  // --- fixtures ------------------------------------------------------------------------------------

  private static final List<CriterionKind> EVENT_KINDS =
      List.of(CriterionKind.ENTITY_STATUS, CriterionKind.DEPLOYMENT_ACTIVE, CriterionKind.SCM_RELEASE);

  private Fixture fixture(CriterionKind kind) {
    return fixture(kind, Instant.now().minus(HOUR), true);
  }

  /**
   * A REFINED campaign whose one waiting member has one criterion of {@code kind}, started at
   * {@code firstStartedAt} ({@code null}: never started) — and the observation that satisfies it.
   */
  private Fixture fixture(CriterionKind kind, Instant firstStartedAt, boolean active) {
    WorkEntity campaign = walk(campaign(), "REFINED");
    Fixture fixture =
        switch (kind) {
          case ENTITY_STATUS -> {
            WorkEntity target = ticket("Target");
            campaigns.addMember(campaign.id, target.id, null, false, WHO);
            String waiting =
                campaigns.addMember(campaign.id, ticket("Waiting").id, null, false, WHO).membership().id;
            yield new Fixture(
                campaign, waiting, new EntityReached(target.id, PROJECT, "VERIFIED", "IMPLEMENTED"));
          }
          case DEPLOYMENT_ACTIVE -> {
            String app = unique("app");
            String waiting = conditioned(campaign, deployment(app, "dev", "2026.930.10"));
            yield new Fixture(campaign, waiting, new DeploymentWentActive(app, "dev", "2026.930.10"));
          }
          case SCM_RELEASE -> {
            String repo = unique("repo");
            String waiting = conditioned(campaign, release(repo, PROJECT, "2026.1001.93000"));
            yield new Fixture(campaign, waiting, new Released(PROJECT, repo, "2026.1001.93000"));
          }
          case APPROVAL -> throw new IllegalArgumentException("no event latches an approval");
        };
    if (firstStartedAt != null) {
      start(campaign.id, firstStartedAt, active);
    }
    return fixture;
  }

  private String conditioned(WorkEntity campaign, CampaignService.CriterionSpec criterion) {
    String member = campaigns.addMember(campaign.id, ticket("Waiting").id, null, false, WHO).membership().id;
    campaigns.setCondition(campaign.id, member, List.of(group(criterion)), WHO);
    return member;
  }

  private void assertMisses(Fixture f, Observation observation) {
    assertMisses(f, observation, Instant.now());
  }

  private void assertMisses(Fixture f, Observation observation, Instant occurredAt) {
    assertTrue(observe(at(observation, occurredAt)).isEmpty(), "answered for " + observation);
    assertNull(only(f.campaign(), f.membershipId()).satisfiedAt, "latched by " + observation);
  }

  private List<String> observe(Observed observed) {
    return QuarkusTransaction.requiringNew().call(() -> evaluator.observe(observed));
  }

  private static Observed now(Observation observation) {
    return at(observation, Instant.now());
  }

  private static Observed at(Observation observation, Instant occurredAt) {
    String signature =
        switch (observation) {
          case EntityReached r -> "EntityTransitioned";
          case DeploymentWentActive d -> "DeploymentActive";
          case Released r -> "SCMRelease";
        };
    return new Observed(observation, UUID.randomUUID(), signature, occurredAt);
  }

  /** The single criterion of {@code membershipId}'s condition, as committed. */
  private CampaignCriterion only(WorkEntity campaign, String membershipId) {
    CampaignService.Member member =
        campaigns.get(campaign.id).members().stream()
            .filter(m -> m.membership().id.equals(membershipId))
            .findFirst()
            .orElseThrow();
    assertEquals(1, member.groups().size());
    assertEquals(1, member.groups().get(0).criteria().size());
    return member.groups().get(0).criteria().get(0);
  }

  private WorkEntity campaign() {
    return workEntities.createCampaign(PROJECT, "The order", null, WHO);
  }

  private WorkEntity ticket(String title) {
    return workEntities
        .create(
            Archetype.TICKET, PROJECT, EntityWrite.ticket(title, "it occurs", null, "BUG", null), WHO)
        .entity();
  }

  private WorkEntity walk(WorkEntity row, String... statuses) {
    WorkEntity moved = row;
    for (String status : statuses) {
      moved = workEntities.transition(row.archetype, row.id, status, WHO).entity();
    }
    return moved;
  }

  private void start(String campaignId, Instant firstStartedAt, boolean active) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CampaignStartRecord start = new CampaignStartRecord();
              start.campaignId = campaignId;
              start.firstStartedAt = firstStartedAt;
              start.startedAt = firstStartedAt;
              start.startedBy = WHO;
              start.active = active;
              starts.persist(start);
            });
  }

  private static String unique(String prefix) {
    return prefix + "_" + UUID.randomUUID();
  }

  private static CampaignService.GroupSpec group(CampaignService.CriterionSpec... criteria) {
    return new CampaignService.GroupSpec(List.of(criteria));
  }

  private static CampaignService.CriterionSpec deployment(String app, String env, String floor) {
    Map<String, Object> predicate = new java.util.LinkedHashMap<>();
    predicate.put("applicationName", app);
    if (env != null) {
      predicate.put("environmentName", env);
    }
    if (floor != null) {
      predicate.put("minimumVersion", floor);
    }
    return new CampaignService.CriterionSpec(null, "DEPLOYMENT_ACTIVE", predicate);
  }

  private static CampaignService.CriterionSpec release(String repo, String project, String floor) {
    Map<String, Object> predicate = new java.util.LinkedHashMap<>();
    predicate.put("repositoryName", repo);
    if (project != null) {
      predicate.put("projectId", project);
    }
    if (floor != null) {
      predicate.put("minimumVersion", floor);
    }
    return new CampaignService.CriterionSpec(null, "SCM_RELEASE", predicate);
  }
}
