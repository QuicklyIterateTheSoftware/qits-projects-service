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
 * qits-1517, qits-1560, qits-1569, qits-1571, qits-1568, qits-1596, qits-1601, qits-1597,
 * qits-1604, qits-1618, qits-1621, qits-1714, qits-1748: the boot step that rolls the delivery
 * desk's new brief onto the stored {@code project.delivery} row — the one row, since surface
 * configurations are platform-wide — only where the stored brief is still byte for byte one of
 * the previous shipped texts: qits-1440's, qits-1517's, qits-1560's, qits-1569's, qits-1571's,
 * qits-1568's, qits-1596's, qits-1601's, qits-1597's, qits-1604's, qits-1618's, qits-1621's,
 * qits-1679's, qits-1724's (the text qits-1714 found shipping) or qits-1748's.
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
  public void anUneditedQits1621BriefIsMigrated() {
    stage(AgentSurfaceDefaults.QITS_1621_DELIVERY_DESK_PROMPT);

    assertTrue(rollout.rollOut(), "the qits-1621 brief is rewritten");

    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, surfaces.get(SURFACE).systemPrompt());
  }

  /** qits-1714: an unedited qits-1679 brief rolls to the current one. */
  @Test
  public void anUneditedQits1679BriefIsMigrated() {
    stage(AgentSurfaceDefaults.QITS_1679_DELIVERY_DESK_PROMPT);

    assertTrue(rollout.rollOut(), "the qits-1679 brief is rewritten");

    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, surfaces.get(SURFACE).systemPrompt());
  }

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

  /**
   * qits-1571, qits-1568: an unedited qits-1569 brief rolls to the current one too — the one whose
   * commands the qits CLI takes and whose step 4 clears no-changes.
   */
  @Test
  public void anUneditedQits1569BriefIsMigrated() {
    assertTrue(
        AgentSurfaceDefaults.QITS_1569_DELIVERY_DESK_PROMPT.contains(
            "(qits workspaces runners list)"),
        "the qits-1569 text is the one that named the retired runner listing");
    stage(AgentSurfaceDefaults.QITS_1569_DELIVERY_DESK_PROMPT);
    int revisionsBefore = surfaces.history(SURFACE).size();

    assertTrue(rollout.rollOut(), "the qits-1569 brief is rewritten");

    String after = surfaces.get(SURFACE).systemPrompt();
    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, after);
    assertTrue(
        after.contains("qits workspaces list --project <this project> --repository"), after);
    assertFalse(after.contains("workspaces runners"), after);
    assertTrue(after.contains("also holds no-changes"), after);
    assertEquals(revisionsBefore + 1, surfaces.history(SURFACE).size(), "one rewrite, one trail");
  }

  /** qits-1571: an edit of the qits-1569 brief is the owner's too. */
  @Test
  public void anOwnerEditedQits1569BriefIsLeftAlone() {
    String edited = AgentSurfaceDefaults.QITS_1569_DELIVERY_DESK_PROMPT + "\nAlso: be brief.";
    stage(edited);

    assertFalse(rollout.rollOut(), "an edit is the owner's");

    assertEquals(edited, surfaces.get(SURFACE).systemPrompt());
  }

  /**
   * qits-1568: an unedited qits-1571 brief rolls to the current one too — the one whose step 4
   * clears no-changes.
   */
  @Test
  public void anUneditedQits1571BriefIsMigrated() {
    assertFalse(
        AgentSurfaceDefaults.QITS_1571_DELIVERY_DESK_PROMPT.contains("no-changes"),
        "the qits-1571 text is the one that did not name no-changes");
    stage(AgentSurfaceDefaults.QITS_1571_DELIVERY_DESK_PROMPT);
    int revisionsBefore = surfaces.history(SURFACE).size();

    assertTrue(rollout.rollOut(), "the qits-1571 brief is rewritten");

    String after = surfaces.get(SURFACE).systemPrompt();
    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, after);
    assertTrue(after.contains("also holds no-changes"), after);
    assertTrue(
        after.contains("qits workspaces list --project <this project> --repository"), after);
    assertEquals(revisionsBefore + 1, surfaces.history(SURFACE).size(), "one rewrite, one trail");
  }

  /** qits-1568: an edit of the qits-1571 brief is the owner's too. */
  @Test
  public void anOwnerEditedQits1571BriefIsLeftAlone() {
    String edited = AgentSurfaceDefaults.QITS_1571_DELIVERY_DESK_PROMPT + "\nAlso: be brief.";
    stage(edited);

    assertFalse(rollout.rollOut(), "an edit is the owner's");

    assertEquals(edited, surfaces.get(SURFACE).systemPrompt());
  }

  /**
   * qits-1596: an unedited qits-1568 brief — the one that still held the desk to a narrow role —
   * rolls to the current one, which gives it the coordinator's rights (scheduling and its
   * approvals still under the owner's rules).
   */
  @Test
  public void anUneditedQits1568BriefIsMigrated() {
    assertTrue(
        AgentSurfaceDefaults.QITS_1568_DELIVERY_DESK_PROMPT.contains(
            "You hold qits:desk for this project only."),
        "the qits-1568 text is the one with the narrow role");
    stage(AgentSurfaceDefaults.QITS_1568_DELIVERY_DESK_PROMPT);
    int revisionsBefore = surfaces.history(SURFACE).size();

    assertTrue(rollout.rollOut(), "the qits-1568 brief is rewritten");

    String after = surfaces.get(SURFACE).systemPrompt();
    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, after);
    assertTrue(after.contains("You hold the owner's coordinator's platform rights"), after);
    assertFalse(after.contains("You hold qits:desk for this project only."), after);
    assertTrue(after.contains("unless its activity is BUSY"), after);
    assertEquals(revisionsBefore + 1, surfaces.history(SURFACE).size(), "one rewrite, one trail");
  }

  /** qits-1596: an edit of the qits-1568 brief is the owner's too. */
  @Test
  public void anOwnerEditedQits1568BriefIsLeftAlone() {
    String edited = AgentSurfaceDefaults.QITS_1568_DELIVERY_DESK_PROMPT + "\nAlso: be brief.";
    stage(edited);

    assertFalse(rollout.rollOut(), "an edit is the owner's");

    assertEquals(edited, surfaces.get(SURFACE).systemPrompt());
  }

  /**
   * qits-1601: an unedited qits-1596 brief rolls to the current one too — the one that excepts a
   * BUSY agent from the stalled-agents step's thirty-minutes-quiet clause and, since qits-1597, is
   * prompted by a person rather than woken by a tick.
   */
  @Test
  public void anUneditedQits1596BriefIsMigrated() {
    assertFalse(
        AgentSurfaceDefaults.QITS_1596_DELIVERY_DESK_PROMPT.contains("BUSY"),
        "the qits-1596 text is the one that did not except a BUSY agent");
    stage(AgentSurfaceDefaults.QITS_1596_DELIVERY_DESK_PROMPT);
    int revisionsBefore = surfaces.history(SURFACE).size();

    assertTrue(rollout.rollOut(), "the qits-1596 brief is rewritten");

    String after = surfaces.get(SURFACE).systemPrompt();
    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, after);
    assertTrue(after.contains("unless its activity is BUSY"), after);
    assertTrue(after.contains("A person prompts you."), after);
    assertFalse(after.contains("You are woken by a turn"), after);
    assertFalse(after.contains("the next tick is the next look"), after);
    assertEquals(revisionsBefore + 1, surfaces.history(SURFACE).size(), "one rewrite, one trail");
  }

  /** qits-1601: an edit of the qits-1596 brief is the owner's too. */
  @Test
  public void anOwnerEditedQits1596BriefIsLeftAlone() {
    String edited = AgentSurfaceDefaults.QITS_1596_DELIVERY_DESK_PROMPT + "\nAlso: be brief.";
    stage(edited);

    assertFalse(rollout.rollOut(), "an edit is the owner's");

    assertEquals(edited, surfaces.get(SURFACE).systemPrompt());
  }

  /**
   * qits-1597: an unedited qits-1601 brief — the one the daemon's tick still woke, with the BUSY
   * exception — rolls to the current one, which a person prompts and which keeps the exception.
   */
  @Test
  public void anUneditedQits1601BriefIsMigrated() {
    assertTrue(
        AgentSurfaceDefaults.QITS_1601_DELIVERY_DESK_PROMPT.contains(
            "the next tick is the next look"),
        "the qits-1601 text is the one with the tick");
    stage(AgentSurfaceDefaults.QITS_1601_DELIVERY_DESK_PROMPT);
    int revisionsBefore = surfaces.history(SURFACE).size();

    assertTrue(rollout.rollOut(), "the qits-1601 brief is rewritten");

    String after = surfaces.get(SURFACE).systemPrompt();
    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, after);
    assertTrue(after.contains("A person prompts you."), after);
    assertTrue(after.contains("unless its activity is BUSY"), after);
    assertFalse(after.contains("You are woken by a turn"), after);
    assertFalse(after.contains("the next tick is the next look"), after);
    assertEquals(revisionsBefore + 1, surfaces.history(SURFACE).size(), "one rewrite, one trail");
  }

  /** qits-1597: an edit of the qits-1601 brief is the owner's too. */
  @Test
  public void anOwnerEditedQits1601BriefIsLeftAlone() {
    String edited = AgentSurfaceDefaults.QITS_1601_DELIVERY_DESK_PROMPT + "\nAlso: be brief.";
    stage(edited);

    assertFalse(rollout.rollOut(), "an edit is the owner's");

    assertEquals(edited, surfaces.get(SURFACE).systemPrompt());
  }

  /**
   * qits-1604: an unedited qits-1597 brief — the one only a person prompted — rolls to the current
   * one, which a desk trigger message prompts too.
   */
  @Test
  public void anUneditedQits1597BriefIsMigrated() {
    assertFalse(
        AgentSurfaceDefaults.QITS_1597_DELIVERY_DESK_PROMPT.contains("desk trigger"),
        "the qits-1597 text is the one without the trigger");
    stage(AgentSurfaceDefaults.QITS_1597_DELIVERY_DESK_PROMPT);
    int revisionsBefore = surfaces.history(SURFACE).size();

    assertTrue(rollout.rollOut(), "the qits-1597 brief is rewritten");

    String after = surfaces.get(SURFACE).systemPrompt();
    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, after);
    assertTrue(after.contains("A person prompts you."), after);
    assertTrue(after.contains("A desk trigger prompts you too"), after);
    assertEquals(revisionsBefore + 1, surfaces.history(SURFACE).size(), "one rewrite, one trail");
  }

  /** qits-1604: an edit of the qits-1597 brief is the owner's too. */
  @Test
  public void anOwnerEditedQits1597BriefIsLeftAlone() {
    String edited = AgentSurfaceDefaults.QITS_1597_DELIVERY_DESK_PROMPT + "\nAlso: be brief.";
    stage(edited);

    assertFalse(rollout.rollOut(), "an edit is the owner's");

    assertEquals(edited, surfaces.get(SURFACE).systemPrompt());
  }

  /**
   * qits-1618: an unedited qits-1604 brief — the one that only said never to loop or sleep inside a
   * turn — rolls to the current one, which tells the desk to wait with qits wait and end its turn.
   */
  @Test
  public void anUneditedQits1604BriefIsMigrated() {
    assertFalse(
        AgentSurfaceDefaults.QITS_1604_DELIVERY_DESK_PROMPT.contains("qits wait"),
        "the qits-1604 text is the one without qits wait");
    stage(AgentSurfaceDefaults.QITS_1604_DELIVERY_DESK_PROMPT);
    int revisionsBefore = surfaces.history(SURFACE).size();

    assertTrue(rollout.rollOut(), "the qits-1604 brief is rewritten");

    String after = surfaces.get(SURFACE).systemPrompt();
    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, after);
    assertTrue(after.contains("A desk trigger prompts you too"), after);
    assertTrue(after.contains("qits wait branch <your branch>"), after);
    assertFalse(after.contains("Never loop or\nsleep inside a turn."), after);
    assertEquals(revisionsBefore + 1, surfaces.history(SURFACE).size(), "one rewrite, one trail");
  }

  /** qits-1618: an edit of the qits-1604 brief is the owner's too. */
  @Test
  public void anOwnerEditedQits1604BriefIsLeftAlone() {
    String edited = AgentSurfaceDefaults.QITS_1604_DELIVERY_DESK_PROMPT + "\nAlso: be brief.";
    stage(edited);

    assertFalse(rollout.rollOut(), "an edit is the owner's");

    assertEquals(edited, surfaces.get(SURFACE).systemPrompt());
  }

  /**
   * qits-1621: an unedited qits-1618 brief — the desk's rights in its project only, waiting with
   * qits wait — rolls to the current one, which names the coordinator's platform rights across the
   * estate.
   */
  @Test
  public void anUneditedQits1618BriefIsMigrated() {
    assertFalse(
        AgentSurfaceDefaults.QITS_1618_DELIVERY_DESK_PROMPT.contains("across the estate"),
        "the qits-1618 text is the one without the platform rights");
    stage(AgentSurfaceDefaults.QITS_1618_DELIVERY_DESK_PROMPT);
    int revisionsBefore = surfaces.history(SURFACE).size();

    assertTrue(rollout.rollOut(), "the qits-1618 brief is rewritten");

    String after = surfaces.get(SURFACE).systemPrompt();
    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, after);
    assertTrue(after.contains("platform rights across the estate"), after);
    assertTrue(after.contains("qits wait branch <your branch>"), after);
    assertEquals(revisionsBefore + 1, surfaces.history(SURFACE).size(), "one rewrite, one trail");
  }

  /** qits-1621: an edit of the qits-1618 brief is the owner's too. */
  @Test
  public void anOwnerEditedQits1618BriefIsLeftAlone() {
    String edited = AgentSurfaceDefaults.QITS_1618_DELIVERY_DESK_PROMPT + "\nAlso: be brief.";
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

  /**
   * qits-1724: an unedited qits-1724 brief — the one that waited on a release request id — rolls
   * to the current one, which waits on the branch with qits wait branch.
   */
  @Test
  public void anUneditedQits1724BriefIsMigrated() {
    assertTrue(
        AgentSurfaceDefaults.QITS_1724_DELIVERY_DESK_PROMPT.contains(
            "qits wait release-request <id> --label '<what you will do>'"),
        "the qits-1724 text is the one before qits wait branch existed");
    stage(AgentSurfaceDefaults.QITS_1724_DELIVERY_DESK_PROMPT);
    int revisionsBefore = surfaces.history(SURFACE).size();

    assertTrue(rollout.rollOut(), "the qits-1724 brief is rewritten");

    String after = surfaces.get(SURFACE).systemPrompt();
    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, after);
    assertTrue(
        after.contains(
            "qits wait branch <your branch> --repository <repository> --until live --label '<what"
                + " you will do>'"),
        after);
    assertFalse(after.contains("qits wait release-request <id>"), after);
    assertEquals(revisionsBefore + 1, surfaces.history(SURFACE).size(), "one rewrite, one trail");
  }

  /** qits-1724: an edit of the qits-1724 brief is the owner's too. */
  @Test
  public void anOwnerEditedQits1724BriefIsLeftAlone() {
    String edited = AgentSurfaceDefaults.QITS_1724_DELIVERY_DESK_PROMPT + "\nAlso: be brief.";
    stage(edited);

    assertFalse(rollout.rollOut(), "an edit is the owner's");

    assertEquals(edited, surfaces.get(SURFACE).systemPrompt());
  }

  /**
   * qits-1748: an unedited qits-1748 brief — the one that still addressed agents with {@code qits
   * workspaces agents} — rolls to the current one, which addresses them with {@code qits agents}
   * and tells the desk how to stop or interrupt a runaway one.
   */
  @Test
  public void anUneditedQits1748BriefIsMigrated() {
    assertTrue(
        AgentSurfaceDefaults.QITS_1748_DELIVERY_DESK_PROMPT.contains("qits workspaces agents"),
        "the qits-1748 text is the one before qits agents existed");
    stage(AgentSurfaceDefaults.QITS_1748_DELIVERY_DESK_PROMPT);
    int revisionsBefore = surfaces.history(SURFACE).size();

    assertTrue(rollout.rollOut(), "the qits-1748 brief is rewritten");

    String after = surfaces.get(SURFACE).systemPrompt();
    assertEquals(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, after);
    assertFalse(after.contains("qits workspaces agents"), after);
    assertTrue(after.contains("qits agents prompt --work <work id>"), after);
    assertTrue(after.contains("qits agents list --state ACTIVE"), after);
    assertTrue(after.contains("qits agents stop --work <work id>"), after);
    assertTrue(after.contains("qits agents interrupt"), after);
    assertEquals(revisionsBefore + 1, surfaces.history(SURFACE).size(), "one rewrite, one trail");
  }

  /** qits-1748: an edit of the qits-1748 brief is the owner's too. */
  @Test
  public void anOwnerEditedQits1748BriefIsLeftAlone() {
    String edited = AgentSurfaceDefaults.QITS_1748_DELIVERY_DESK_PROMPT + "\nAlso: be brief.";
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
