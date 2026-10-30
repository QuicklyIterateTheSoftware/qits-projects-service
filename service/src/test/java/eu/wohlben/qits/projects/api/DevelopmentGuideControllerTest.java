package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.AgentSurfaceConfigurationService;
import eu.wohlben.qits.projects.control.AgentSurfaceDefaults;
import eu.wohlben.qits.projects.control.DevelopmentGuideDefaults;
import eu.wohlben.qits.projects.control.DevelopmentGuideService;
import eu.wohlben.qits.projects.dto.AgentSurfaceConfigurationDto;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.security.AgentTokens;
import eu.wohlben.qits.projects.startup.DevelopmentGuideSeed;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The development guide (qits-1679): each variant, detection, storage and the rollout. */
@QuarkusTest
public class DevelopmentGuideControllerTest {

  @Inject DevelopmentGuideSeed seed;

  @Inject DevelopmentGuideService guide;

  @Inject AgentSurfaceConfigurationService surfaces;

  @BeforeEach
  void seeded() {
    seed.run();
  }

  @AfterEach
  void restored() {
    for (String key : DevelopmentGuideDefaults.KEYS) {
      surfaces.save(key, AgentSurfaceDefaults.shippedDefault(key), null);
    }
  }

  private static JsonPath read(String variant) {
    return given()
        .queryParam("for", variant)
        .when()
        .get("/projects/api/guide")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }

  @Test
  public void theGuideHashIsSixteenLowercaseHexCharacters() {
    String hash = DevelopmentGuideService.hash("# A guide\n");
    assertTrue(hash.matches("[0-9a-f]{16}"), hash);
  }

  @Test
  public void theGuideHashIsStableForTheSameTextAndMovesWithIt() {
    assertEquals(DevelopmentGuideService.hash("# A guide\n"), DevelopmentGuideService.hash("# A guide\n"));
    assertFalse(
        DevelopmentGuideService.hash("# A guide\n").equals(DevelopmentGuideService.hash("# A guide.\n")));
    // The first sixteen hex characters of SHA-256("abc").
    assertEquals("ba7816bf8f01cfea", DevelopmentGuideService.hash("abc"));
  }

  @Test
  public void everyVariantAnswersTheHashOfTheMarkdownItReturns() {
    for (String variant : DevelopmentGuideDefaults.VARIANTS) {
      JsonPath answer = read(variant);
      String markdown = answer.getString("markdown");
      assertEquals(DevelopmentGuideService.hash(markdown), answer.getString("hash"), variant);
      assertEquals(guide.renderHash(variant), answer.getString("hash"), variant);
    }
  }

  @Test
  public void anEditedPartMovesTheHash() {
    String before = guide.renderHash("workstation");
    String key = DevelopmentGuideDefaults.key("workstation");
    surfaces.save(key, withText(surfaces.get(key), "# Development guide: workstation\n\nEdited."), null);
    String after = read("workstation").getString("hash");
    assertFalse(before.equals(after), "an edit moves the hash");
  }

  @Test
  public void theListingNamesTheFourVariants() {
    JsonPath answer =
        given().when().get("/projects/api/guide/variants").then().statusCode(200).extract().jsonPath();
    assertEquals(
        List.of("workstation", "agent", "desk", "refinement"),
        answer.getList("variants.name", String.class));
  }

  @Test
  public void theWorkstationVariantIsTheWorkstationGuideAlone() {
    JsonPath answer = read("workstation");
    assertEquals("workstation", answer.getString("variant"));
    assertFalse(answer.getBoolean("detected"));
    String markdown = answer.getString("markdown");
    assertTrue(markdown.startsWith("# Development guide: workstation"), markdown);
    assertTrue(markdown.contains("qits registry-login --configure"));
    assertTrue(markdown.contains("--dry-run"));
    assertFalse(markdown.contains("# Development flow"), "a workstation gets no agent flow");
  }

  @Test
  public void theAgentVariantIsItsBriefThenTheFlow() {
    String markdown = read("agent").getString("markdown");
    assertTrue(markdown.startsWith(DevelopmentGuideDefaults.AGENT_GUIDE));
    assertTrue(markdown.contains(AgentSurfaceDefaults.COMPOSED_RUN_PROMPT), "ticket.dispatch's brief");
    assertTrue(markdown.contains(DevelopmentGuideDefaults.DEVELOPMENT_FLOW));
    assertTrue(
        markdown.indexOf(AgentSurfaceDefaults.COMPOSED_RUN_PROMPT)
            < markdown.indexOf("# Development flow"));
  }

  @Test
  public void theDeskVariantIsTheDesksLiveBriefThenTheFlow() {
    String markdown = read("desk").getString("markdown");
    assertTrue(markdown.startsWith(DevelopmentGuideDefaults.DESK_GUIDE));
    assertTrue(markdown.contains(AgentSurfaceDefaults.DELIVERY_DESK_PROMPT));
    assertTrue(markdown.contains(DevelopmentGuideDefaults.DEVELOPMENT_FLOW));
  }

  @Test
  public void theRefinementVariantHasNoEmptyBriefSection() {
    String markdown = read("refinement").getString("markdown");
    assertTrue(markdown.startsWith(DevelopmentGuideDefaults.REFINEMENT_GUIDE));
    assertFalse(markdown.contains("## Your brief"), "project.work ships an empty prompt");
    assertTrue(markdown.contains(DevelopmentGuideDefaults.DEVELOPMENT_FLOW));
  }

  /**
   * qits-1679: live, {@code ticket.dispatch} keeps its brief in the initial prompt and an empty
   * system prompt. The agent variant falls back to it rather than print an intro and no brief.
   */
  @Test
  public void theAgentVariantFallsBackToTheHoldersInitialPrompt() {
    String key = AgentSurfaceDefaults.TICKET_DISPATCH;
    AgentSurfaceConfigurationDto holder = surfaces.get(key);
    try {
      surfaces.save(
          key, withPrompts(holder, "", "The dispatch brief lives in the initial prompt."), "owner");
      String markdown = read("agent").getString("markdown");
      assertTrue(markdown.contains("## Your brief"), markdown);
      assertTrue(markdown.contains("The dispatch brief lives in the initial prompt."), markdown);
      assertTrue(
          markdown.indexOf("## Your brief") < markdown.indexOf("# Development flow"), markdown);
    } finally {
      surfaces.save(key, AgentSurfaceDefaults.shippedDefault(key), null);
    }
  }

  /** qits-1679: no brief renders for a refinement room, and its part promises none. */
  @Test
  public void theRefinementVariantPromisesNoBriefItLacks() {
    String markdown = read("refinement").getString("markdown");
    assertFalse(markdown.contains("## Your brief"), markdown);
    String part = DevelopmentGuideDefaults.REFINEMENT_GUIDE;
    assertFalse(part.toLowerCase().contains("brief"), part);
    assertTrue(part.contains("Your dispatched turn carries the refine instruction"), part);
    assertEquals(
        List.of(DevelopmentGuideDefaults.QITS_1679_REFINEMENT_GUIDE),
        DevelopmentGuideDefaults.PREVIOUS.get(DevelopmentGuideDefaults.key("refinement")),
        "an unedited refinement part rolls forward to the new text");
  }

  /** qits-1679: an unedited refinement row rolls forward on boot; an edited one stays. */
  @Test
  public void anUneditedRefinementPartRollsForward() {
    String key = DevelopmentGuideDefaults.key("refinement");
    AgentSurfaceConfigurationDto part = surfaces.get(key);
    surfaces.save(key, withText(part, DevelopmentGuideDefaults.QITS_1679_REFINEMENT_GUIDE), null);
    assertTrue(guide.rollForward() >= 1);
    assertTrue(
        read("refinement")
            .getString("markdown")
            .startsWith(DevelopmentGuideDefaults.REFINEMENT_GUIDE));

    surfaces.save(key, withText(part, "an owner's refinement part"), "owner");
    guide.rollForward();
    assertTrue(read("refinement").getString("markdown").startsWith("an owner's refinement part"));
  }

  /** qits-1679 follow-up: every variant says how to ask the delivery desk. */
  @Test
  public void everyVariantSaysHowToAskTheDesk() {
    for (String variant : DevelopmentGuideDefaults.VARIANTS) {
      String markdown = read(variant).getString("markdown");
      assertTrue(markdown.contains("`@desk`"), variant);
    }
    String workstation = read("workstation").getString("markdown");
    assertTrue(workstation.contains("qits projects desk prompt --project <project>"));
    assertTrue(workstation.contains("qits projects desk answer --project <project>"));
    String agent = read("agent").getString("markdown");
    assertTrue(agent.contains("## Ask the delivery desk"));
    assertTrue(agent.contains("Wait on your branch, never on a release request id or a fold's sha"));
    assertTrue(agent.contains("qits wait branch"), "qits-1724: the development flow names it");
    assertTrue(read("refinement").getString("markdown").contains("## Ask the delivery desk"));
    assertTrue(
        read("desk").getString("markdown").contains("declare a `qits wait` with a timeout for it"));
  }

  /** qits-1689: the desk variant says what to do with a failed or stalled request and a free slot. */
  @Test
  public void theDeskVariantSaysWhatToDoWithFailedAndStalledRequests() {
    String markdown = read("desk").getString("markdown");
    assertTrue(markdown.contains("only when no active agent was told of it"));
    assertTrue(markdown.contains("`qits ci retry`"));
    assertTrue(markdown.contains("withdraw a dependency bump of a repository that is retired"));
    assertTrue(markdown.contains("ask the\nowner on the item's thread"));
    assertTrue(markdown.contains("you are told a slot opened"));
    assertTrue(
        markdown.contains("reaches you only when no agent owns the branch"),
        "qits-1725: a conflict or a missing source ref reaches the desk only without an owner");
    assertTrue(
        markdown.contains(
            "An agent that has had no turn for 2h reaches you once per idle stretch"),
        "qits-1756: the desk is told of an idle agent");
    assertTrue(markdown.contains("leave a deliberate wait alone"), "qits-1756");
    assertEquals(
        List.of(
            DevelopmentGuideDefaults.QITS_1679_DESK_GUIDE,
            DevelopmentGuideDefaults.QITS_1689_DESK_GUIDE,
            DevelopmentGuideDefaults.QITS_1725_DESK_GUIDE),
        DevelopmentGuideDefaults.PREVIOUS.get(DevelopmentGuideDefaults.key("desk")),
        "an unedited desk part rolls forward to the new text");
  }

  /** qits-1724: the flow waits on the branch, never on a release request id or a fold's sha. */
  @Test
  public void theFlowWaitsOnTheBranch() {
    assertTrue(
        DevelopmentGuideDefaults.DEVELOPMENT_FLOW.contains(
            "## Waiting for your branch or a run: declare it and end your turn"));
    assertTrue(
        DevelopmentGuideDefaults.DEVELOPMENT_FLOW.contains(
            "qits wait branch <your branch> --repository <repository> --until live"));
    assertTrue(
        DevelopmentGuideDefaults.DEVELOPMENT_FLOW.contains(
            "qits wait branch <your branch> --until live"));
    assertFalse(
        DevelopmentGuideDefaults.DEVELOPMENT_FLOW.contains(
            "qits wait release-request <request id>"));
    assertEquals(
        List.of(
            DevelopmentGuideDefaults.QITS_1679_DEVELOPMENT_FLOW,
            DevelopmentGuideDefaults.QITS_1724_PREVIOUS_DEVELOPMENT_FLOW,
            DevelopmentGuideDefaults.QITS_1665_PREVIOUS_DEVELOPMENT_FLOW),
        DevelopmentGuideDefaults.PREVIOUS.get(DevelopmentGuideDefaults.FLOW_KEY),
        "an unedited flow part rolls forward to the new text");
    assertTrue(
        DevelopmentGuideDefaults.QITS_1724_PREVIOUS_DEVELOPMENT_FLOW.contains(
            "qits wait release-request <request id>"),
        "the qits-1724 text is the one before qits wait branch existed");
  }

  /** qits-1724: an unedited flow row holding the qits-1724 text rolls forward on boot. */
  @Test
  public void anUneditedFlowPartRollsForwardFromQits1724() {
    String key = DevelopmentGuideDefaults.FLOW_KEY;
    AgentSurfaceConfigurationDto flow = surfaces.get(key);
    surfaces.save(
        key, withText(flow, DevelopmentGuideDefaults.QITS_1724_PREVIOUS_DEVELOPMENT_FLOW), null);

    assertTrue(guide.rollForward() >= 1);

    assertTrue(read("agent").getString("markdown").contains("qits wait branch"));
  }

  /**
   * qits-1665: the flow says to move a source that alone blocks a shared request to the next one,
   * never to force-push it out; an unedited row of the text before rolls forward. The desk's own
   * brief is left to a follow-up, for the owner to approve on its own.
   */
  @Test
  public void theFlowMovesABlockingSourceToTheNextRequest() {
    String flow = DevelopmentGuideDefaults.DEVELOPMENT_FLOW;
    assertTrue(flow.contains("`qits release-request move --project <project> --repository <repository>"));
    assertTrue(flow.contains("Never force-push a branch to take it out of a request"));
    assertTrue(flow.contains("If your own branch blocks a shared request,\nmove it yourself"));
    assertFalse(
        DevelopmentGuideDefaults.QITS_1665_PREVIOUS_DEVELOPMENT_FLOW.contains("release-request move"),
        "the qits-1665 text is the one before the move existed");

    String key = DevelopmentGuideDefaults.FLOW_KEY;
    surfaces.save(
        key,
        withText(surfaces.get(key), DevelopmentGuideDefaults.QITS_1665_PREVIOUS_DEVELOPMENT_FLOW),
        null);

    assertTrue(guide.rollForward() >= 1);

    assertTrue(read("agent").getString("markdown").contains(DevelopmentGuideDefaults.DEVELOPMENT_FLOW));
  }

  /** qits-1724: the workstation guide waits on the branch too. */
  @Test
  public void theWorkstationGuideWaitsOnTheBranch() {
    assertTrue(
        DevelopmentGuideDefaults.WORKSTATION_GUIDE.contains(
            "Then wait for the outcome\non your branch (`qits wait branch <branch> --until"
                + " live`) or on the item's final state,\nnever on a request id or a fold."));
    assertEquals(
        List.of(
            DevelopmentGuideDefaults.QITS_1679_WORKSTATION_GUIDE,
            DevelopmentGuideDefaults.QITS_1724_PREVIOUS_WORKSTATION_GUIDE),
        DevelopmentGuideDefaults.PREVIOUS.get(DevelopmentGuideDefaults.key("workstation")),
        "an unedited workstation part rolls forward to the new text");
  }

  /** qits-1724: an unedited workstation row holding the qits-1724 text rolls forward on boot. */
  @Test
  public void anUneditedWorkstationPartRollsForwardFromQits1724() {
    String key = DevelopmentGuideDefaults.key("workstation");
    AgentSurfaceConfigurationDto part = surfaces.get(key);
    surfaces.save(
        key, withText(part, DevelopmentGuideDefaults.QITS_1724_PREVIOUS_WORKSTATION_GUIDE), null);

    assertTrue(guide.rollForward() >= 1);

    assertTrue(
        read("workstation")
            .getString("markdown")
            .contains("qits wait branch <branch> --until live"));
  }

  @Test
  public void anUnknownVariantIsA400() {
    given().queryParam("for", "kiosk").when().get("/projects/api/guide").then().statusCode(400);
  }

  @Test
  public void noVariantNamedDetectsItFromTheCredential() {
    String project = "11111111-2222-3333-4444-555555555555";
    assertEquals("agent", detect(AgentTokens.token(Map.of("project", project), "qits:agent")));
    assertEquals(
        "agent", detect(AgentTokens.token(Map.of(), "qits:agent", "qits:admin-agent")));
    assertEquals(
        "desk",
        detect(
            AgentTokens.token(
                Map.of("project", project), "qits:agent", "qits:desk", "qits:desk-admin")));
    assertEquals(
        "refinement",
        detect(
            AgentTokens.token(
                Map.of("project", project, "entity", "e-1"), "qits:agent")));
    assertEquals("workstation", detect(AgentTokens.token(Map.of(), "qits:admin")));
  }

  private String detect(SecurityIdentity caller) {
    DevelopmentGuideController door = new DevelopmentGuideController();
    door.guide = guide;
    door.identity = caller;
    DevelopmentGuideController.GuideResponse answer = door.get(null);
    assertTrue(answer.detected());
    assertTrue(answer.markdown().startsWith("# Development guide: " + headingOf(answer.variant())));
    return answer.variant();
  }

  private static String headingOf(String variant) {
    return switch (variant) {
      case "agent" -> "workspace agent";
      case "desk" -> "delivery desk";
      case "refinement" -> "refinement room";
      default -> "workstation";
    };
  }

  @Test
  public void anEditedPartIsServedAndStaysOutOfTheSurfaceListing() {
    AgentSurfaceConfigurationDto flow = surfaces.get(DevelopmentGuideDefaults.FLOW_KEY);
    surfaces.save(DevelopmentGuideDefaults.FLOW_KEY, withText(flow, "# Edited flow"), "tester");

    assertTrue(read("agent").getString("markdown").contains("# Edited flow"));
    assertFalse(surfaces.history(DevelopmentGuideDefaults.FLOW_KEY).isEmpty(), "versioned");
    assertTrue(
        surfaces.listAll().stream()
            .noneMatch(s -> DevelopmentGuideDefaults.isGuideKey(s.surface())),
        "no guide row reaches the agent configuration document");
  }

  @Test
  public void theRolloutRewritesAnUneditedPreviousTextAndKeepsAnEdit() {
    String key = DevelopmentGuideDefaults.FLOW_KEY;
    AgentSurfaceConfigurationDto flow = surfaces.get(key);
    surfaces.save(key, withText(flow, "old shipped flow"), null);

    // Read back over HTTP: this method's own session would answer its first read again.
    assertTrue(read("agent").getString("markdown").contains("old shipped flow"));
    assertEquals(1, guide.rollForward(Map.of(key, List.of("older flow", "old shipped flow"))));
    assertTrue(
        read("agent").getString("markdown").contains(DevelopmentGuideDefaults.DEVELOPMENT_FLOW));

    surfaces.save(key, withText(flow, "an owner's edit"), "owner");
    assertEquals(0, guide.rollForward(Map.of(key, List.of("old shipped flow"))));
    assertTrue(read("agent").getString("markdown").contains("an owner's edit"));
  }

  @Test
  public void theDeskNeverEditsTheGuide() {
    AgentSurfaceConfigurationController door = new AgentSurfaceConfigurationController();
    door.surfaces = surfaces;
    door.identity =
        AgentTokens.token(Map.of(), "qits:agent", "qits:desk", "qits:desk-admin");
    DomainException refusal =
        assertThrows(
            DomainException.class,
            () ->
                door.update(
                    "guide.desk",
                    new AgentSurfaceConfigurationController.SurfaceUpdateRequest(
                        "CLAUDE", "", "", false, "SKIP_PERMISSIONS", false, "x", "", List.of(),
                        List.of())));
    assertEquals(403, refusal.statusCode());
  }

  private static AgentSurfaceConfigurationDto withPrompts(
      AgentSurfaceConfigurationDto row, String systemPrompt, String initialPrompt) {
    return new AgentSurfaceConfigurationDto(
        row.surface(),
        row.harness(),
        row.model(),
        row.effort(),
        row.remoteControl(),
        row.permissionMode(),
        row.activityTracking(),
        systemPrompt,
        initialPrompt,
        row.mcpServers(),
        row.externalMcpServers(),
        false);
  }

  private static AgentSurfaceConfigurationDto withText(AgentSurfaceConfigurationDto row, String text) {
    return new AgentSurfaceConfigurationDto(
        row.surface(),
        row.harness(),
        row.model(),
        row.effort(),
        row.remoteControl(),
        row.permissionMode(),
        row.activityTracking(),
        text,
        row.initialPrompt(),
        row.mcpServers(),
        row.externalMcpServers(),
        false);
  }
}
