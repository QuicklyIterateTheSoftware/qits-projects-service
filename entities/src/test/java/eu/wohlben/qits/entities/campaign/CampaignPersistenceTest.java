package eu.wohlben.qits.entities.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.EntitiesTestSupport;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.MembershipKind;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every V19 column round-trips through its entity class. Flyway owns the DDL and {@code
 * database.generation} is {@code none}, so a column name that disagrees with its field would go
 * unnoticed until the first query — this is that first query, for all four tables. A {@code
 * @QuarkusTest} with <b>no {@code @TestProfile}</b> (the test-profile budget rule).
 */
@QuarkusTest
class CampaignPersistenceTest extends EntitiesTestSupport {

  private static final String PROJECT = "proj-campaign-persistence";
  private static final Instant T = Instant.parse("2026-09-27T10:11:12.345678Z");

  @Inject WorkEntityService workEntities;
  @Inject EntityMembershipRepository memberships;
  @Inject CampaignCriterionGroupRepository groups;
  @Inject CampaignCriterionRepository criteria;
  @Inject CampaignStartRecordRepository starts;
  @Inject CampaignService campaigns;

  @Test
  void theRunRecordTheConditionAndTheStartRoundTrip() {
    WorkEntity campaign = workEntities.createCampaign(PROJECT, "The order", null, "t");
    WorkEntity ticket =
        workEntities
            .create(
                Archetype.TICKET, PROJECT, EntityWrite.ticket("Work", "it occurs", null, "BUG", null), "t")
            .entity();
    String membershipId = UUID.randomUUID().toString();
    String groupId = UUID.randomUUID().toString();
    String criterionId = UUID.randomUUID().toString();
    UUID eventId = UUID.randomUUID();

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              EntityMembership edge = new EntityMembership();
              edge.id = membershipId;
              edge.kind = MembershipKind.CAMPAIGN;
              edge.parentId = campaign.id;
              edge.childId = ticket.id;
              edge.position = 0;
              edge.claimedAt = T;
              edge.joinedRunning = true;
              edge.dispatchedAt = T.plusSeconds(1);
              edge.dispatchWorkspaceId = "42";
              edge.dispatchBranch = "ticket/work";
              edge.dispatchAgentLaunch = "SCHEDULED";
              edge.dispatchRefusal = "not REFINED";
              edge.dispatchRefusedAt = T.plusSeconds(2);
              edge.dispatchError = "java.io.IOException: gone";
              memberships.persist(edge);

              CampaignCriterionGroup group = new CampaignCriterionGroup();
              group.id = groupId;
              group.membershipId = membershipId;
              group.position = 0;
              groups.persist(group);

              CampaignCriterion criterion = new CampaignCriterion();
              criterion.id = criterionId;
              criterion.groupId = groupId;
              criterion.position = 0;
              criterion.kind = CriterionKind.DEPLOYMENT_ACTIVE;
              criterion.predicate =
                  new CriterionPredicate.DeploymentActive("qits-projects", "dev", "2026.930.1")
                      .canonicalJson();
              criterion.seeded = true;
              criterion.satisfiedAt = T;
              criterion.evidenceEventId = eventId;
              criterion.evidenceSignature = "DeploymentActive";
              criterion.evidenceSummary = "DeploymentActive: qits-projects 2026.930.1 in dev";
              criterion.approvedBy = "nobody";
              criterion.approvalNote = "a note";
              criteria.persist(criterion);

              CampaignStartRecord start = new CampaignStartRecord();
              start.campaignId = campaign.id;
              start.firstStartedAt = T;
              start.startedAt = T.plusSeconds(3);
              start.startedBy = "xion";
              start.active = true;
              starts.persist(start);
            });

    inFreshTx(
        () -> {
          EntityMembership edge = memberships.findById(membershipId);
          assertEquals(MembershipKind.CAMPAIGN, edge.kind);
          assertEquals(micros(T), micros(edge.claimedAt));
          assertTrue(edge.joinedRunning);
          assertEquals(micros(T.plusSeconds(1)), micros(edge.dispatchedAt));
          assertEquals("42", edge.dispatchWorkspaceId);
          assertEquals("ticket/work", edge.dispatchBranch);
          assertEquals("SCHEDULED", edge.dispatchAgentLaunch);
          assertEquals("not REFINED", edge.dispatchRefusal);
          assertEquals(micros(T.plusSeconds(2)), micros(edge.dispatchRefusedAt));
          assertEquals("java.io.IOException: gone", edge.dispatchError);

          CampaignCriterionGroup group = groups.groupsOf(membershipId).get(0);
          assertEquals(groupId, group.id);
          assertEquals(0, group.position);
          assertTrue(group.createdAt != null);

          CampaignCriterion criterion = criteria.findById(criterionId);
          assertEquals(groupId, criterion.groupId);
          assertEquals(CriterionKind.DEPLOYMENT_ACTIVE, criterion.kind);
          assertEquals(
              new CriterionPredicate.DeploymentActive("qits-projects", "dev", "2026.930.1"),
              criterion.decoded());
          assertTrue(criterion.seeded);
          assertEquals(micros(T), micros(criterion.satisfiedAt));
          assertEquals(eventId, criterion.evidenceEventId);
          assertEquals("DeploymentActive", criterion.evidenceSignature);
          assertEquals(
              "DeploymentActive: qits-projects 2026.930.1 in dev", criterion.evidenceSummary);
          assertEquals("nobody", criterion.approvedBy);
          assertEquals("a note", criterion.approvalNote);
          assertTrue(criterion.createdAt != null);

          CampaignStartRecord start = starts.startOf(campaign.id).orElseThrow();
          assertEquals(micros(T), micros(start.firstStartedAt));
          assertEquals(micros(T.plusSeconds(3)), micros(start.startedAt));
          assertEquals("xion", start.startedBy);
          assertTrue(start.active);
        });
  }

  @Test
  void deletingTheCampaignTakesItsStartMembershipsAndCriteriaWithIt() {
    WorkEntity campaign = workEntities.createCampaign(PROJECT, "Doomed", null, "t");
    WorkEntity ticket =
        workEntities
            .create(
                Archetype.TICKET, PROJECT, EntityWrite.ticket("Work", "it occurs", null, "BUG", null), "t")
            .entity();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CampaignStartRecord start = new CampaignStartRecord();
              start.campaignId = campaign.id;
              start.firstStartedAt = T;
              start.startedAt = T;
              start.startedBy = "xion";
              start.active = true;
              starts.persist(start);
            });
    String membershipId = campaigns.addMember(campaign.id, ticket.id, null, false, "t").membership().id;
    campaigns.setCondition(
        campaign.id,
        membershipId,
        List.of(
            new CampaignService.GroupSpec(
                List.of(new CampaignService.CriterionSpec(null, "APPROVAL", Map.of())))),
        "t");

    workEntities.delete(Archetype.CAMPAIGN, campaign.id, "t");

    inFreshTx(
        () -> {
          assertTrue(starts.startOf(campaign.id).isEmpty());
          assertEquals(0, groups.count());
          assertEquals(0, criteria.count());
          assertTrue(memberships.campaignMembershipsOf(ticket.id).isEmpty());
        });
  }

  private static Instant micros(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS);
  }
}
