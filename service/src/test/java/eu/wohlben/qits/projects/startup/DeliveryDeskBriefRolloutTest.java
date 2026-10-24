package eu.wohlben.qits.projects.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.AgentSurfaceConfigurationService;
import eu.wohlben.qits.projects.control.AgentSurfaceDefaults;
import eu.wohlben.qits.projects.dto.AgentSurfaceConfigurationDto;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-1517, qits-1560, qits-1569: the boot step that rolls the delivery desk's new brief onto the
 * stored {@code project.delivery} row — the one row, since surface configurations are platform-wide
 * — only where the stored brief is still byte for byte one of the previous shipped texts:
 * qits-1440's, qits-1517's or qits-1560's.
 *
 * <p>Driven through {@link DeliveryDeskBriefRollout#rollOut()} directly, as the seed's neighbours
 * are. The row is staged through the store's own write and put back to the shipped default after
 * each test, because the controller suite reads the brief from the same row.
 */
@QuarkusTest
public class DeliveryDeskBriefRolloutTest {

  private static final String SURFACE = AgentSurfaceDefaults.PROJECT_DELIVERY;

  @Inject AgentSurfaceSeed seed;

  @Inject DeliveryDeskBriefRollout rollout;

  @Inject AgentSurfaceConfigurationService surfaces;

  @BeforeEach
  void seeded() {
    seed.seed();
  }

  @AfterEach
  void restored() {
    surfaces.save(SURFACE, AgentSurfaceDefaults.shippedDefault(SURFACE), null);
  }

  /** An unedited stored brief is rewritten, and nothing else on the row moves. */
  @Test
  public void anUneditedStoredBriefIsMigrated() {
    stage(AgentSurfaceDefaults.PREVIOUS_DELIVERY_DESK_PROMPT);
    int revisionsBefore = surfaces.history(SURFACE).size();

    assertTrue(rollout.rollOut(), "the previous shipped brief is rewritten");

    AgentSurfaceConfigurationDto after = surfaces.get(SURFACE);
    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, after.systemPrompt());
    AgentSurfaceConfigurationDto shipped = AgentSurfaceDefaults.shippedDefault(SURFACE);
    assertEquals(shipped.initialPrompt(), after.initialPrompt());
    assertEquals(shipped.harness(), after.harness());
    assertEquals(shipped.remoteControl(), after.remoteControl());
    assertEquals(shipped.mcpServers(), after.mcpServers());
    assertEquals(revisionsBefore + 1, surfaces.history(SURFACE).size(), "the rewrite leaves a trail");
  }

  /** qits-1560: an unedited qits-1517 brief rolls to the current one too. */
  @Test
  public void anUneditedQits1517BriefIsMigrated() {
    stage(AgentSurfaceDefaults.QITS_1517_DELIVERY_DESK_PROMPT);
    int revisionsBefore = surfaces.history(SURFACE).size();

    assertTrue(rollout.rollOut(), "the qits-1517 brief is rewritten");

    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, surfaces.get(SURFACE).systemPrompt());
    assertEquals(revisionsBefore + 1, surfaces.history(SURFACE).size(), "one rewrite, one trail");
  }

  /**
   * qits-1569: an unedited qits-1560 brief rolls to the current one too — the one that no longer
   * counts a blocked item as running.
   */
  @Test
  public void anUneditedQits1560BriefIsMigrated() {
    assertTrue(
        AgentSurfaceDefaults.QITS_1560_DELIVERY_DESK_PROMPT.contains(
            "epics and tickets not DONE or DROPPED with an active agent"),
        "the qits-1560 text is the one that counted blocked items");
    stage(AgentSurfaceDefaults.QITS_1560_DELIVERY_DESK_PROMPT);
    int revisionsBefore = surfaces.history(SURFACE).size();

    assertTrue(rollout.rollOut(), "the qits-1560 brief is rewritten");

    String after = surfaces.get(SURFACE).systemPrompt();
    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, after);
    assertTrue(after.contains("A blocked item holds no slot"), after);
    assertEquals(revisionsBefore + 1, surfaces.history(SURFACE).size(), "one rewrite, one trail");
  }

  /** qits-1569: an edit of the qits-1560 brief is the owner's too. */
  @Test
  public void anOwnerEditedQits1560BriefIsLeftAlone() {
    String edited = AgentSurfaceDefaults.QITS_1560_DELIVERY_DESK_PROMPT + "\nAlso: be brief.";
    stage(edited);

    assertFalse(rollout.rollOut(), "an edit is the owner's");

    assertEquals(edited, surfaces.get(SURFACE).systemPrompt());
  }

  /** A brief the owner edited is left exactly as stored. */
  @Test
  public void anOwnerEditedBriefIsLeftAlone() {
    String edited = AgentSurfaceDefaults.PREVIOUS_DELIVERY_DESK_PROMPT + "\nAlso: be brief.";
    stage(edited);

    assertFalse(rollout.rollOut(), "an edit is the owner's");

    assertEquals(edited, surfaces.get(SURFACE).systemPrompt());
  }

  /** qits-1560: an edit of the qits-1517 brief is the owner's too. */
  @Test
  public void anOwnerEditedQits1517BriefIsLeftAlone() {
    String edited = AgentSurfaceDefaults.QITS_1517_DELIVERY_DESK_PROMPT + "\nAlso: be brief.";
    stage(edited);

    assertFalse(rollout.rollOut(), "an edit is the owner's");

    assertEquals(edited, surfaces.get(SURFACE).systemPrompt());
  }

  /** The boot after the rewrite finds the new text and writes nothing. */
  @Test
  public void aSecondBootIsANoOp() {
    stage(AgentSurfaceDefaults.PREVIOUS_DELIVERY_DESK_PROMPT);
    assertTrue(rollout.rollOut());
    int revisions = surfaces.history(SURFACE).size();

    assertFalse(rollout.rollOut(), "already at the shipped brief");

    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, surfaces.get(SURFACE).systemPrompt());
    assertEquals(revisions, surfaces.history(SURFACE).size(), "no second revision");
  }

  /** Stores {@code systemPrompt} on the row, every other field as shipped. */
  private void stage(String systemPrompt) {
    AgentSurfaceConfigurationDto shipped = AgentSurfaceDefaults.shippedDefault(SURFACE);
    surfaces.save(
        SURFACE,
        new AgentSurfaceConfigurationDto(
            shipped.surface(),
            shipped.harness(),
            shipped.model(),
            shipped.effort(),
            shipped.remoteControl(),
            shipped.permissionMode(),
            shipped.activityTracking(),
            systemPrompt,
            shipped.initialPrompt(),
            shipped.mcpServers(),
            shipped.externalMcpServers(),
            shipped.shipped()),
        "owner@example.test");
  }
}
