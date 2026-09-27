package eu.wohlben.qits.entities.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.EntitiesTestSupport;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.ConflictException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-417's entities half: {@link CampaignService#start}, the {@code campaign_start} upsert a press
 * makes, and the hand-over ({@link CampaignMembersSatisfied}) an authoring write makes to the
 * executor — observed here {@code AFTER_SUCCESS}, the way the executor observes it.
 */
@QuarkusTest
class CampaignStartAndHandOverTest extends EntitiesTestSupport {

  private static final String PROJECT = "proj-campaign-start";
  private static final String WHO = "tester";

  @Inject CampaignService campaigns;
  @Inject WorkEntityService workEntities;
  @Inject CampaignStartRecordRepository starts;
  @Inject HandOvers handOvers;

  /** Records every committed hand-over, as the executor would receive it. */
  @ApplicationScoped
  static class HandOvers {
    private final List<CampaignMembersSatisfied> seen = new ArrayList<>();

    void on(@Observes(during = TransactionPhase.AFTER_SUCCESS) CampaignMembersSatisfied event) {
      synchronized (seen) {
        seen.add(event);
      }
    }

    List<String> membershipIds() {
      synchronized (seen) {
        return seen.stream().flatMap(event -> event.membershipIds().stream()).toList();
      }
    }

    void clear() {
      synchronized (seen) {
        seen.clear();
      }
    }
  }

  @BeforeEach
  void forget() {
    handOvers.clear();
  }

  @Test
  void theFirstPressInsertsTheStartAndALaterOneNeverMovesItsFloor() throws Exception {
    WorkEntity campaign = walk(campaign(), "REFINED");

    CampaignStartRecord first = campaigns.start(campaign.id, "dana");
    Thread.sleep(5);
    walk(campaign, "REPORTED", "REFINED");
    assertFalse(inTx(() -> starts.isActive(campaign.id)), "paused by the move");
    CampaignStartRecord second = campaigns.start(campaign.id, "erin");

    CampaignStartRecord stored = inTx(() -> starts.startOf(campaign.id).orElseThrow());
    assertEquals(first.firstStartedAt, stored.firstStartedAt, "the forward-only floor never moves");
    assertEquals(second.startedAt, stored.startedAt);
    assertTrue(stored.startedAt.isAfter(stored.firstStartedAt));
    assertEquals("erin", stored.startedBy);
    assertTrue(stored.active, "a press is the resume");
  }

  @Test
  void aCampaignThatIsNotRefinedIsNotStarted() {
    WorkEntity campaign = campaign();

    ConflictException refused =
        assertThrows(ConflictException.class, () -> campaigns.start(campaign.id, "dana"));
    assertTrue(refused.getMessage().contains("Start a campaign from REFINED"), refused.getMessage());
    assertTrue(inTx(() -> starts.startOf(campaign.id).isEmpty()), "nothing written");
  }

  @Test
  void anApprovalHandsItsNowSatisfiedMemberOver() {
    WorkEntity campaign = campaign();
    CampaignService.Member member =
        campaigns.addMember(campaign.id, ticket("Gated").id, null, false, WHO);
    CampaignService.Member gated =
        campaigns.setCondition(
            campaign.id,
            member.membership().id,
            List.of(
                new CampaignService.GroupSpec(
                    List.of(new CampaignService.CriterionSpec(null, "APPROVAL", null)))),
            WHO);
    assertEquals(List.of(), handOvers.membershipIds(), "not started: an edit hands nothing over");

    campaigns.approve(
        campaign.id,
        gated.membership().id,
        gated.groups().get(0).criteria().get(0).id,
        null,
        "dana");

    assertEquals(List.of(gated.membership().id), handOvers.membershipIds());
  }

  @Test
  void anActiveCampaignHandsOverAMemberAddedWithNothingToWaitOnAndAnEditThatFreesOne() {
    WorkEntity campaign = walk(campaign(), "REFINED");
    campaigns.start(campaign.id, "dana");

    CampaignService.Member first =
        campaigns.addMember(campaign.id, ticket("First").id, null, false, WHO);
    assertEquals(List.of(first.membership().id), handOvers.membershipIds(), "waits on nothing");

    CampaignService.Member second =
        campaigns.addMember(campaign.id, ticket("Second").id, null, false, WHO);
    assertFalse(second.satisfied(), "seeded on the first");
    assertEquals(List.of(first.membership().id), handOvers.membershipIds());

    campaigns.setCondition(campaign.id, second.membership().id, List.of(), WHO);
    assertEquals(
        List.of(first.membership().id, second.membership().id), handOvers.membershipIds());
  }

  // --- fixtures ------------------------------------------------------------------------------------

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

  private static <T> T inTx(java.util.concurrent.Callable<T> read) {
    return QuarkusTransaction.requiringNew().call(read);
  }
}
