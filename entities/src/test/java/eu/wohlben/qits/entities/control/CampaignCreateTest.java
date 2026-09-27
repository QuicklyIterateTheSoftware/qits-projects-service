package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditEntry;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkEntityService#createCampaign} (qits-411): a campaign is born the way an epic is — a
 * slug minted from the title in the project's root scope, a number from the per-project allocator,
 * the status minted REPORTED by the writer, one audit row — and it is editable at every status
 * because it has no freeze owner.
 *
 * <p>A {@code @QuarkusTest} on {@link EntitiesTestSupport} because the audit row and the number are
 * database facts (and the audit row is refused by {@code ck_audit_entity_type} before V17). It adds
 * <b>no {@code @TestProfile}</b>: this repository's test-profile budget rule.
 */
@QuarkusTest
class CampaignCreateTest extends EntitiesTestSupport {

  private static final String PROJECT = "proj-campaign";

  @Inject WorkEntityService workEntities;
  @Inject AuditService auditService;

  @Test
  void aCampaignIsBornReportedWithASlugANumberAndAnAuditRow() {
    WorkEntity epic =
        workEntities
            .create(Archetype.EPIC, PROJECT, EntityWrite.epic("Spring cleaning", null), "alice")
            .entity();
    WorkEntity campaign =
        workEntities.createCampaign(PROJECT, "Spring cleaning", "the order", "alice");

    assertEquals(Archetype.CAMPAIGN, campaign.archetype);
    assertEquals(PROJECT, campaign.projectId);
    assertEquals(EntityStatus.REPORTED.name(), campaign.status);
    assertEquals("the order", campaign.description);
    // The root slug scope is the project, shared with epics and tickets: the epic keeps the clean
    // slug and the campaign takes the next free one.
    assertEquals(PROJECT, campaign.slugScope);
    assertEquals("spring-cleaning", epic.slug);
    assertEquals("spring-cleaning-2", campaign.slug);
    // One run of integers per project, whatever the kind.
    assertNotEquals(epic.number, campaign.number);
    assertEquals(epic.number + 1, campaign.number);

    inFreshTx(
        () -> {
          List<AuditEntry> log = auditService.listForEntity(AuditEntityType.CAMPAIGN, campaign.id);
          assertEquals(1, log.size());
          assertEquals(AuditOperation.CREATE, log.get(0).operation);
          assertEquals("alice", log.get(0).changedBy);
          assertEquals(campaign.id, log.get(0).epicId, "a root carries its own id as subtree key");
        });
  }

  @Test
  void aCampaignWithoutATitleIsRefused() {
    assertThrows(
        BadRequestException.class, () -> workEntities.createCampaign(PROJECT, " ", null, "alice"));
    assertThrows(
        BadRequestException.class,
        () -> workEntities.createCampaign(null, "No project", null, "alice"));
  }

  @Test
  void aCampaignHasNoFreezeOwnerSoItsTitleIsEditableOnceRefined() {
    WorkEntity campaign = workEntities.createCampaign(PROJECT, "Summer", null, "alice");
    workEntities.transition(
        Archetype.CAMPAIGN, campaign.id, EntityStatus.REFINED.name(), "alice");

    WorkEntity edited =
        workEntities
            .update(
                Archetype.CAMPAIGN,
                campaign.id,
                EntityWrite.campaign("Summer, reordered", "now with a body"),
                "alice")
            .entity();

    assertEquals("Summer, reordered", edited.title);
    assertEquals("now with a body", edited.description);
    assertEquals(EntityStatus.REFINED.name(), edited.status);
    assertEquals("summer", edited.slug, "the slug is minted once and never re-derived");
  }
}
