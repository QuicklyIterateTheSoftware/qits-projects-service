package eu.wohlben.qits.entities.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.EntitiesTestSupport;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.ConflictException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * {@code MEMBERS_SCHEDULED} (qits-942): a campaign's REFINED → READY_FOR_DEV is refused while a
 * member that is not DROPPED is still before READY_FOR_DEV, the refusal naming each in order with
 * its status; it passes once every member is scheduled, further along, or dropped — and for an
 * empty campaign, which has nobody behind.
 */
@QuarkusTest
class MembersScheduledGateTest extends EntitiesTestSupport {

  private static final String PROJECT = "proj-members-scheduled";
  private static final String WHO = "ada";

  @Inject WorkEntityService workEntities;
  @Inject CampaignService campaigns;

  private final MembersScheduledGate gate = new MembersScheduledGate();

  @Test
  void aCampaignWithAMemberBehindIsRefusedAndTheRefusalNamesEveryOne() {
    WorkEntity scheduled = walk(ticket("Scheduled"), "REFINED", "READY_FOR_DEV");
    WorkEntity refined = walk(epic("Refined"), "REFINED");
    WorkEntity reported = ticket("Reported");
    WorkEntity dropped = walk(ticket("Dropped"), "DROPPED");
    WorkEntity campaign = campaignOf(scheduled, refined, reported, dropped);

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () ->
                workEntities.transition(
                    Archetype.CAMPAIGN, campaign.id, "READY_FOR_DEV", Mover.person(WHO)));

    String message = refusal.getMessage();
    assertTrue(
        message.contains(
            "MEMBERS_SCHEDULED: 2 members are not READY_FOR_DEV yet: #"
                + refined.number
                + " (REFINED), #"
                + reported.number
                + " (REPORTED)"),
        message);
    assertFalse(message.contains("#" + scheduled.number + " "), "a scheduled member is not behind");
    assertFalse(message.contains("#" + dropped.number + " "), "a dropped member is aside");
    assertFalse(message.contains("PERSON_APPROVAL"), "a person moved it: " + message);
    assertEquals("REFINED", status(campaign));
  }

  @Test
  void itPassesOnceEveryMemberIsScheduledFurtherAlongOrDropped() {
    WorkEntity behind = walk(ticket("Behind"), "REFINED");
    WorkEntity further = walk(ticket("Further"), "REFINED", "READY_FOR_DEV", "IMPLEMENTED");
    WorkEntity dropped = walk(ticket("Gone"), "DROPPED");
    WorkEntity campaign = campaignOf(behind, further, dropped);
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.transition(
                Archetype.CAMPAIGN, campaign.id, "READY_FOR_DEV", Mover.person(WHO)));

    walk(behind, "READY_FOR_DEV");
    workEntities.transition(Archetype.CAMPAIGN, campaign.id, "READY_FOR_DEV", Mover.person(WHO));

    assertEquals("READY_FOR_DEV", status(campaign));
  }

  @Test
  void anEmptyCampaignHasNobodyBehindAndPasses() {
    WorkEntity campaign = walk(workEntities.createCampaign(PROJECT, "Empty", null, WHO), "REFINED");
    workEntities.transition(Archetype.CAMPAIGN, campaign.id, "READY_FOR_DEV", Mover.person(WHO));
    assertEquals("READY_FOR_DEV", status(campaign));
  }

  /** Unscheduling a campaign is a BACK move: never judged, whoever is behind. */
  @Test
  void unschedulingIsNeverJudged() {
    WorkEntity member = walk(ticket("Member"), "REFINED", "READY_FOR_DEV");
    WorkEntity campaign = campaignOf(member);
    walk(campaign, "READY_FOR_DEV");
    walk(member, "REFINED");

    workEntities.transition(Archetype.CAMPAIGN, campaign.id, "REFINED", Mover.machine("agent-7"));

    assertEquals("REFINED", status(campaign));
  }

  @Test
  void itJudgesACampaignsSchedulingAndNothingElse() {
    assertTrue(
        gate.appliesTo(Archetype.CAMPAIGN, EntityStatus.REFINED, EntityStatus.READY_FOR_DEV));
    assertFalse(
        gate.appliesTo(Archetype.CAMPAIGN, EntityStatus.READY_FOR_DEV, EntityStatus.REFINED));
    assertFalse(
        gate.appliesTo(Archetype.CAMPAIGN, EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTED));
    assertFalse(gate.appliesTo(Archetype.CAMPAIGN, EntityStatus.REPORTED, EntityStatus.REFINED));
    for (Archetype archetype : Archetype.values()) {
      if (archetype != Archetype.CAMPAIGN) {
        assertFalse(
            gate.appliesTo(archetype, EntityStatus.REFINED, EntityStatus.READY_FOR_DEV),
            archetype.name());
      }
    }
    assertTrue(MembersScheduledGate.isBehind("REPORTED"));
    assertTrue(MembersScheduledGate.isBehind("REFINED"));
    assertFalse(MembersScheduledGate.isBehind("READY_FOR_DEV"));
    assertFalse(MembersScheduledGate.isBehind("IMPLEMENTING"));
    assertFalse(MembersScheduledGate.isBehind("DONE"));
    assertFalse(MembersScheduledGate.isBehind("DROPPED"));
  }

  // --- fixtures ------------------------------------------------------------------------------------

  /** A REFINED campaign holding {@code members} in order. */
  private WorkEntity campaignOf(WorkEntity... members) {
    WorkEntity campaign = workEntities.createCampaign(PROJECT, "The order", null, WHO);
    for (WorkEntity member : members) {
      campaigns.addMember(campaign.id, member.id, null, false, WHO);
    }
    return walk(campaign, "REFINED");
  }

  private WorkEntity ticket(String title) {
    return workEntities
        .create(
            Archetype.TICKET,
            PROJECT,
            EntityWrite.ticket(title, "it occurs", null, "BUG", null)
                .withAcceptanceCriteria(CRITERIA),
            WHO)
        .entity();
  }

  private WorkEntity epic(String title) {
    return workEntities
        .create(
            Archetype.EPIC,
            PROJECT,
            EntityWrite.epic(title, null).withAcceptanceCriteria(CRITERIA),
            WHO)
        .entity();
  }

  private WorkEntity walk(WorkEntity row, String... statuses) {
    WorkEntity moved = row;
    for (String status : statuses) {
      moved = workEntities.transition(row.archetype, row.id, status, Mover.person(WHO)).entity();
    }
    return moved;
  }

  private String status(WorkEntity row) {
    return QuarkusTransaction.requiringNew()
        .call(() -> workEntities.get(row.archetype, row.id))
        .status;
  }
}
