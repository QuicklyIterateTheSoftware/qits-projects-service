package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.MembershipKind;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The delete path of {@link WorkEntityService} with CAMPAIGN edges in the table (qits-412): a
 * campaign's members are <b>not its subtree</b>, so deleting a campaign removes its edges and none
 * of the work it gathers; and deleting a gathered entity (or the epic above it) takes it out of the
 * campaign <b>with the gap closed</b>, where the FK's cascade alone would leave a hole.
 *
 * <p>A {@code @QuarkusTest} on {@link EntitiesTestSupport} with <b>no {@code @TestProfile}</b> — the
 * test-profile budget rule. The campaign edges are inserted directly, since no door writes one yet.
 */
@QuarkusTest
class CampaignMembershipDeleteTest extends EntitiesTestSupport {

  private static final String PROJECT = "proj-campaign-delete";
  private static final String WHO = "tester";

  @Inject WorkEntityService workEntities;
  @Inject WorkEntityRepository entities;
  @Inject EntityMembershipRepository memberships;

  @Test
  void deletingACampaignLeavesTheWorkItGathersStanding() {
    WorkEntity epic =
        workEntities.create(Archetype.EPIC, PROJECT, EntityWrite.epic("Work", null), WHO).entity();
    Nested feature =
        workEntities.create(Archetype.FEATURE, epic.id, EntityWrite.feature("Part", null, null), WHO);
    WorkEntity campaign = workEntities.createCampaign(PROJECT, "The order", null, WHO);
    gather(campaign.id, epic.id);
    gather(campaign.id, feature.entity().id);

    workEntities.delete(Archetype.CAMPAIGN, campaign.id, WHO);

    inFreshTx(
        () -> {
          assertNotNull(entities.findById(epic.id), "a gathered epic is not the campaign's subtree");
          assertNotNull(entities.findById(feature.entity().id));
          assertEquals(
              epic.id,
              memberships.structuralMembershipOf(feature.entity().id).orElseThrow().parentId,
              "the tree is untouched");
          assertTrue(memberships.campaignMembershipsOf(epic.id).isEmpty(), "the edges went");
          assertTrue(memberships.campaignMembershipsOf(feature.entity().id).isEmpty());
        });
  }

  @Test
  void deletingGatheredWorkClosesTheGapsItLeavesInEveryCampaign() {
    WorkEntity first =
        workEntities.create(Archetype.EPIC, PROJECT, EntityWrite.epic("First", null), WHO).entity();
    WorkEntity doomed =
        workEntities.create(Archetype.EPIC, PROJECT, EntityWrite.epic("Doomed", null), WHO).entity();
    Nested doomedPart =
        workEntities.create(
            Archetype.FEATURE, doomed.id, EntityWrite.feature("Doomed part", null, null), WHO);
    WorkEntity last =
        workEntities.create(Archetype.EPIC, PROJECT, EntityWrite.epic("Last", null), WHO).entity();
    WorkEntity one = workEntities.createCampaign(PROJECT, "One", null, WHO);
    WorkEntity two = workEntities.createCampaign(PROJECT, "Two", null, WHO);
    // One: first, doomed, doomed part, last — the epic AND its feature go, so two gaps in one run.
    gather(one.id, first.id);
    gather(one.id, doomed.id);
    gather(one.id, doomedPart.entity().id);
    gather(one.id, last.id);
    // Two: the feature alone, ahead of a survivor.
    gather(two.id, doomedPart.entity().id);
    gather(two.id, first.id);

    workEntities.delete(Archetype.EPIC, doomed.id, WHO);

    inFreshTx(
        () -> {
          assertEquals(List.of(first.id, last.id), memberIds(one.id));
          assertEquals(List.of(0, 1), positionsIn(one.id), "dense and zero-based after two gaps");
          assertEquals(List.of(first.id), memberIds(two.id));
          assertEquals(List.of(0), positionsIn(two.id));
        });
  }

  /** A CAMPAIGN edge from {@code campaignId} to {@code childId}, appended and committed. */
  private void gather(String campaignId, String childId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              EntityMembership edge = new EntityMembership();
              edge.id = UUID.randomUUID().toString();
              edge.kind = MembershipKind.CAMPAIGN;
              edge.parentId = campaignId;
              edge.childId = childId;
              edge.position = memberships.campaignMaxPosition(campaignId) + 1;
              memberships.persist(edge);
            });
  }

  private List<String> memberIds(String campaignId) {
    return memberships.campaignMembers(campaignId).stream().map(edge -> edge.childId).toList();
  }

  private List<Integer> positionsIn(String campaignId) {
    return memberships.campaignMembers(campaignId).stream().map(edge -> edge.position).toList();
  }
}
