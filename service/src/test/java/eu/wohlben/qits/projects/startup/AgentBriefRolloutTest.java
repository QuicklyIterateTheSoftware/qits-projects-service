package eu.wohlben.qits.projects.startup;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.AgentSurfaceConfigurationService;
import eu.wohlben.qits.projects.control.AgentSurfaceDefaults;
import eu.wohlben.qits.projects.dto.AgentSurfaceConfigurationDto;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The workspace agents' brief on asking the delivery desk (qits-1679) rolls onto unedited rows. */
@QuarkusTest
public class AgentBriefRolloutTest {

  private static final List<String> SURFACES =
      List.of(AgentSurfaceDefaults.WORKSPACE_AGENT, AgentSurfaceDefaults.TICKET_DISPATCH);

  @Inject AgentSurfaceSeed seed;

  @Inject AgentBriefRollout rollout;

  @Inject AgentSurfaceConfigurationService surfaces;

  @BeforeEach
  void seeded() {
    seed.seed();
  }

  @AfterEach
  void restored() {
    for (String surface : SURFACES) {
      surfaces.save(surface, AgentSurfaceDefaults.shippedDefault(surface), null);
    }
  }

  /** Read over HTTP: this method's own session would answer an earlier read again. */
  private static String prompt(String surface) {
    return given()
        .when()
        .get("/projects/api/agent-surfaces/" + surface)
        .then()
        .statusCode(200)
        .extract()
        .path("systemPrompt");
  }

  @Test
  public void unEditedPreviousBriefsRollForward() {
    stage(AgentSurfaceDefaults.WORKSPACE_AGENT, "");
    stage(AgentSurfaceDefaults.TICKET_DISPATCH, AgentSurfaceDefaults.COMPOSED_RUN_PROMPT);

    assertTrue(rollout.rollOut() >= 2, "both staged rows roll");

    assertEquals(AgentSurfaceDefaults.AGENT_DESK_BRIEF, prompt(AgentSurfaceDefaults.WORKSPACE_AGENT));
    assertEquals(
        AgentSurfaceDefaults.COMPOSED_RUN_BRIEF, prompt(AgentSurfaceDefaults.TICKET_DISPATCH));
    assertEquals(0, rollout.rollOut(), "idempotent");
  }

  @Test
  public void anOwnersEditStays() {
    stage(AgentSurfaceDefaults.WORKSPACE_AGENT, "Steer like this.");
    stage(AgentSurfaceDefaults.TICKET_DISPATCH, AgentSurfaceDefaults.COMPOSED_RUN_PROMPT + " Edited.");

    rollout.rollOut();

    assertEquals("Steer like this.", prompt(AgentSurfaceDefaults.WORKSPACE_AGENT));
    assertEquals(
        AgentSurfaceDefaults.COMPOSED_RUN_PROMPT + " Edited.",
        prompt(AgentSurfaceDefaults.TICKET_DISPATCH));
  }

  private void stage(String surface, String systemPrompt) {
    AgentSurfaceConfigurationDto shipped = AgentSurfaceDefaults.shippedDefault(surface);
    surfaces.save(
        surface,
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
