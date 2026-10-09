package eu.wohlben.qits.projects.mcp;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.api.TestCriteria;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.MultiMap;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The campaign MCP surface (qits-414): the same round trip {@code WorkCampaignApiTest} pins over HTTP,
 * experienced the way an agent on the other end of the socket sees it — a build that seeds a
 * dependency between members, a move that leaves conditions alone, a condition PUT, and a 409
 * (a claimed member's condition) surfacing as a readable tool error rather than a protocol one. The
 * lifecycle and membership rules themselves are pinned in the entities module; what is tested here
 * is the tool surface: scoping, JSON shape and the read-only filter.
 */
@QuarkusTest
@TestProfile(McpStatelessTestProfile.class)
public class CampaignMcpToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String PROJECT = "campaign-mcp";

  @Inject WorkEntityService workEntities;

  private static RequestSpecification authenticated() {
    return given()
        .header("X-Qits-User", "mcp-test")
        .header("X-Qits-Roles", "qits:admin,qits:system");
  }

  // --- Fixtures --------------------------------------------------------------

  private String createProject(String name) {
    return authenticated()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(
                name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  private WorkEntity ticket(String projectId, String title) {
    return workEntities
        .create(
            Archetype.TICKET,
            projectId,
            EntityWrite.ticket(title, "it occurs", null, "BUG", null).withAcceptanceCriteria(TestCriteria.CRITERIA),
            "t")
        .entity();
  }

  // --- MCP plumbing ------------------------------------------------------------

  private static String text(ToolResponse response) {
    return response.content().stream()
        .map(c -> c.asText().text())
        .collect(Collectors.joining("\n"));
  }

  private static JsonNode json(ToolResponse response) {
    try {
      return JSON.readTree(text(response));
    } catch (Exception e) {
      throw new AssertionError("Not JSON: " + text(response), e);
    }
  }

  private McpStreamableTestClient client(String projectId) {
    return McpAssured.newStreamableClient()
        .setStateless()
        .setMcpPath("/projects/mcp")
        .setAdditionalHeaders(
            msg -> {
              MultiMap headers = MultiMap.caseInsensitiveMultiMap();
              if (projectId != null) {
                headers.add(ProjectScope.PROJECT_HEADER, projectId);
              }
              return headers;
            })
        .build()
        .connect();
  }

  private McpStreamableTestClient readOnlyClient(String projectId) {
    return McpAssured.newStreamableClient()
        .setStateless()
        .setMcpPath("/projects/mcp?" + ReadOnlyRepositoryToolFilter.READ_ONLY_PARAM + "=true")
        .setAdditionalHeaders(
            msg -> {
              MultiMap headers = MultiMap.caseInsensitiveMultiMap();
              if (projectId != null) {
                headers.add(ProjectScope.PROJECT_HEADER, projectId);
              }
              return headers;
            })
        .build()
        .connect();
  }

  private interface Check {
    void accept(ToolResponse response);
  }

  private void call(String projectId, String tool, Map<String, Object> args, Check check) {
    client(projectId).when().toolsCall(tool, args, check::accept).thenAssertResults();
  }

  // --- The round trip ----------------------------------------------------------

  @Test
  public void buildsOrdersConditionsAndTransitionsACampaignThroughItsTools() {
    String projectId = createProject("Campaign round trip");
    WorkEntity first = ticket(projectId, "First");
    WorkEntity second = ticket(projectId, "Second");
    WorkEntity third = ticket(projectId, "Third");

    String[] campaignId = new String[1];
    call(
        projectId,
        "create_campaign",
        Map.of("title", "Rollout", "description", "the order matters"),
        response -> {
          assertFalse(response.isError(), text(response));
          JsonNode campaign = json(response);
          assertEquals("REPORTED", campaign.get("status").asText());
          assertEquals(0, campaign.get("members").size());
          campaignId[0] = campaign.get("id").asText();
        });

    call(
        projectId,
        "add_campaign_member",
        Map.of("campaignId", campaignId[0], "entityId", first.id),
        response -> {
          assertFalse(response.isError(), text(response));
          JsonNode member = json(response);
          assertEquals(0, member.get("position").asInt());
          assertEquals(0, member.get("groups").size(), "no predecessor to seed on");
        });

    String[] secondMembershipId = new String[1];
    call(
        projectId,
        "add_campaign_member",
        Map.of("campaignId", campaignId[0], "entityId", second.id),
        response -> {
          assertFalse(response.isError(), text(response));
          JsonNode member = json(response);
          secondMembershipId[0] = member.get("membershipId").asText();
          JsonNode criterion = member.get("groups").get(0).get("criteria").get(0);
          assertEquals("ENTITY_STATUS", criterion.get("kind").asText());
          assertTrue(criterion.get("seeded").asBoolean(), "the seed on the 2nd member: " + member);
          assertEquals(first.id, criterion.get("predicate").get("entityId").asText());
          assertEquals("VERIFIED", criterion.get("predicate").get("status").asText());
        });

    String[] thirdMembershipId = new String[1];
    call(
        projectId,
        "add_campaign_member",
        Map.of("campaignId", campaignId[0], "entityId", third.id),
        response -> {
          assertFalse(response.isError(), text(response));
          JsonNode member = json(response);
          thirdMembershipId[0] = member.get("membershipId").asText();
          JsonNode criterion = member.get("groups").get(0).get("criteria").get(0);
          assertTrue(criterion.get("seeded").asBoolean(), "the seed on the 3rd member: " + member);
          assertEquals(second.id, criterion.get("predicate").get("entityId").asText());
        });

    // Move the third member to the front — never touches any condition.
    call(
        projectId,
        "move_campaign_member",
        Map.of("campaignId", campaignId[0], "membershipId", thirdMembershipId[0], "position", 0),
        response -> {
          assertFalse(response.isError(), text(response));
          JsonNode campaign = json(response);
          assertEquals(
              thirdMembershipId[0], campaign.get("members").get(0).get("membershipId").asText());
          JsonNode criterion =
              campaign.get("members").get(0).get("groups").get(0).get("criteria").get(0);
          assertEquals(
              second.id,
              criterion.get("predicate").get("entityId").asText(),
              "a move must not touch the condition");
        });

    // PUT semantics: replace the second member's condition outright with an APPROVAL.
    call(
        projectId,
        "set_campaign_member_condition",
        Map.of(
            "campaignId",
            campaignId[0],
            "membershipId",
            secondMembershipId[0],
            "groups",
            List.of(Map.of("criteria", List.of(Map.of("kind", "APPROVAL", "predicate", Map.of()))))),
        response -> {
          assertFalse(response.isError(), text(response));
          JsonNode member = json(response);
          assertEquals(1, member.get("groups").size());
          assertEquals("APPROVAL", member.get("groups").get(0).get("criteria").get(0).get("kind").asText());
        });

    call(
        projectId,
        "get_campaign",
        Map.of("id", campaignId[0]),
        response -> {
          assertFalse(response.isError(), text(response));
          JsonNode campaign = json(response);
          assertEquals(3, campaign.get("members").size());
          assertEquals(
              "APPROVAL",
              findMember(campaign, secondMembershipId[0])
                  .get("groups")
                  .get(0)
                  .get("criteria")
                  .get(0)
                  .get("kind")
                  .asText());
        });

    call(
        projectId,
        "get_campaign_progress",
        Map.of("id", campaignId[0]),
        response -> {
          assertFalse(response.isError(), text(response));
          JsonNode progress = json(response);
          assertEquals(campaignId[0], progress.get("campaign").get("id").asText());
          assertFalse(progress.get("evaluator").get("connected").asBoolean(), "the bus is dark");
          assertEquals(3, progress.get("members").size());
          JsonNode gated =
              findMember(progress, secondMembershipId[0]).get("groups").get(0).get("criteria").get(0);
          assertEquals("a person approves", gated.get("wouldBeSatisfiedBy").asText());
          assertTrue(gated.get("satisfiable").asBoolean());
        });

    call(
        projectId,
        "transition_campaign",
        Map.of("id", campaignId[0], "target", "REFINED"),
        response -> {
          assertFalse(response.isError(), text(response));
          assertEquals("REFINED", json(response).get("status").asText());
        });
    // A campaign never enters IMPLEMENTING (qits-749): REFINED is what running means for one.
    call(
        projectId,
        "transition_campaign",
        Map.of("id", campaignId[0], "target", "IMPLEMENTING"),
        response -> {
          assertTrue(response.isError(), "a campaign must not move to IMPLEMENTING");
          assertTrue(text(response).contains("never moves to IMPLEMENTING"), text(response));
        });
    call(
        projectId,
        "transition_campaign",
        Map.of("id", campaignId[0], "target", "VERIFYING"),
        response -> {
          assertTrue(response.isError(), "a campaign must not move to VERIFYING either");
          assertTrue(text(response).contains("never moves to VERIFYING"), text(response));
        });

    call(
        projectId,
        "list_campaigns",
        Map.of(),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains(campaignId[0]), body);
          assertTrue(body.contains("\"members\":3"), body);
        });
  }

  private static JsonNode findMember(JsonNode campaign, String membershipId) {
    for (JsonNode member : campaign.get("members")) {
      if (member.get("membershipId").asText().equals(membershipId)) {
        return member;
      }
    }
    throw new AssertionError("No member " + membershipId + " in " + campaign);
  }

  // --- Scoping -----------------------------------------------------------------

  @Test
  public void refusesACampaignOutsideTheScopedProject() {
    String owner = createProject("Campaign owner");
    String stranger = createProject("Campaign stranger");
    String[] campaignId = new String[1];
    call(
        owner,
        "create_campaign",
        Map.of("title", "Owned"),
        response -> campaignId[0] = json(response).get("id").asText());

    call(
        stranger,
        "get_campaign",
        Map.of("id", campaignId[0]),
        response -> {
          assertTrue(response.isError(), "cross-project access must be refused");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });
  }

  // --- Either form of an id (qits-954) ------------------------------------------

  /** The qualified id {@code <project-slug>-<n>} of an entity, read off the {@code /work} door. */
  private static String qualifiedIdOf(String entityId) {
    return authenticated()
        .when()
        .get("/projects/api/work/" + entityId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("qualifiedId");
  }

  /**
   * Every campaign tool takes the qualified id {@code get_campaign} answers as readily as the UUID,
   * and so do the member a tool adds and an {@code ENTITY_STATUS} criterion's target — and what
   * reaches the service is the UUID in every case, which the stored predicate and the member row
   * show.
   */
  @Test
  public void takesQualifiedIdsForTheCampaignItsMembersAndItsCriteria() {
    String projectId = createProject("Campaign qualified");
    WorkEntity first = ticket(projectId, "First");
    WorkEntity second = ticket(projectId, "Second");
    String[] campaign = new String[2];
    call(
        projectId,
        "create_campaign",
        Map.of("title", "Named either way"),
        response -> {
          assertFalse(response.isError(), text(response));
          campaign[0] = json(response).get("id").asText();
          campaign[1] = json(response).get("qualifiedId").asText();
        });
    String qualified = campaign[1];
    assertFalse(qualified.equals(campaign[0]), "the qualified id is not the UUID: " + qualified);

    call(
        projectId,
        "add_campaign_member",
        Map.of("campaignId", qualified, "entityId", qualifiedIdOf(first.id)),
        response -> {
          assertFalse(response.isError(), text(response));
          assertEquals(first.id, json(response).get("entity").get("id").asText());
        });
    String[] membershipId = new String[1];
    call(
        projectId,
        "add_campaign_member",
        Map.of("campaignId", qualified, "entityId", qualifiedIdOf(second.id)),
        response -> {
          assertFalse(response.isError(), text(response));
          JsonNode member = json(response);
          membershipId[0] = member.get("membershipId").asText();
          assertEquals(second.id, member.get("entity").get("id").asText());
        });

    call(
        projectId,
        "set_campaign_member_condition",
        Map.of(
            "campaignId",
            qualified,
            "membershipId",
            membershipId[0],
            "groups",
            List.of(
                Map.of(
                    "criteria",
                    List.of(
                        Map.of(
                            "kind",
                            "ENTITY_STATUS",
                            "predicate",
                            Map.of("entityId", qualifiedIdOf(first.id), "status", "DONE")))))),
        response -> {
          assertFalse(response.isError(), text(response));
          JsonNode criterion = json(response).get("groups").get(0).get("criteria").get(0);
          assertEquals(
              first.id,
              criterion.get("predicate").get("entityId").asText(),
              "the target is stored as the UUID: " + criterion);
        });

    // A read by either form is one answer.
    String[] byUuid = new String[1];
    call(
        projectId,
        "get_campaign",
        Map.of("id", campaign[0]),
        response -> {
          assertFalse(response.isError(), text(response));
          byUuid[0] = text(response);
        });
    call(
        projectId,
        "get_campaign",
        Map.of("id", qualified),
        response -> {
          assertFalse(response.isError(), text(response));
          assertEquals(byUuid[0], text(response));
        });
    call(
        projectId,
        "get_campaign_progress",
        Map.of("id", qualified),
        response -> {
          assertFalse(response.isError(), text(response));
          assertEquals(campaign[0], json(response).get("campaign").get("id").asText());
        });

    call(
        projectId,
        "transition_campaign",
        Map.of("id", qualified, "target", "REFINED"),
        response -> {
          assertFalse(response.isError(), text(response));
          assertEquals(campaign[0], json(response).get("id").asText());
          assertEquals("REFINED", json(response).get("status").asText());
        });
  }

  @Test
  public void refusesAQualifiedIdOfAnotherProjectsCampaign() {
    String owner = createProject("Campaign qualified owner");
    String stranger = createProject("Campaign qualified stranger");
    String[] qualified = new String[1];
    call(
        owner,
        "create_campaign",
        Map.of("title", "Owned"),
        response -> qualified[0] = json(response).get("qualifiedId").asText());

    for (String tool : List.of("get_campaign", "get_campaign_progress", "transition_campaign")) {
      Map<String, Object> args =
          tool.equals("transition_campaign")
              ? Map.of("id", qualified[0], "target", "REFINED")
              : Map.of("id", qualified[0]);
      call(
          stranger,
          tool,
          args,
          response -> {
            assertTrue(response.isError(), tool + " must refuse another project's campaign");
            assertTrue(text(response).contains("not found in this project"), text(response));
          });
    }
  }

  // --- The 409 ------------------------------------------------------------------

  /** A claimed member's condition is settled; the service's ConflictException reads as a tool error. */
  @Test
  public void refusesToChangeAClaimedMembersConditionAsABusinessError() {
    String projectId = createProject("Campaign claimed");
    WorkEntity implemented = ticket(projectId, "Already under way");
    workEntities.transition(Archetype.TICKET, implemented.id, "REFINED", "t");
    workEntities.transition(Archetype.TICKET, implemented.id, "READY_FOR_DEV", Mover.person("t"));
    workEntities.transition(Archetype.TICKET, implemented.id, "IMPLEMENTED", "t");

    String[] campaignId = new String[1];
    call(
        projectId,
        "create_campaign",
        Map.of("title", "Claimed member"),
        response -> campaignId[0] = json(response).get("id").asText());

    String[] membershipId = new String[1];
    call(
        projectId,
        "add_campaign_member",
        // Explicit inFlight=true joins it claimed at once, without depending on the workspace port.
        Map.of("campaignId", campaignId[0], "entityId", implemented.id, "inFlight", true),
        response -> {
          assertFalse(response.isError(), text(response));
          JsonNode member = json(response);
          assertTrue(member.get("claimedAt").asText(null) != null, "must be claimed: " + member);
          membershipId[0] = member.get("membershipId").asText();
        });

    call(
        projectId,
        "set_campaign_member_condition",
        Map.of(
            "campaignId",
            campaignId[0],
            "membershipId",
            membershipId[0],
            "groups",
            List.of(Map.of("criteria", List.of(Map.of("kind", "APPROVAL", "predicate", Map.of()))))),
        response -> {
          assertTrue(response.isError(), "a claimed member's condition must not change");
          assertTrue(text(response).contains("is no longer edited"), text(response));
        });
  }

  // --- The read-only filter ------------------------------------------------------

  @Test
  public void readOnlyMarkerHidesEveryCampaignMutatingTool() {
    String projectId = createProject("Campaign read-only");
    readOnlyClient(projectId)
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).toList();
              for (String mutating :
                  List.of(
                      "create_campaign",
                      "transition_campaign",
                      "add_campaign_member",
                      "move_campaign_member",
                      "remove_campaign_member",
                      "set_campaign_member_condition")) {
                assertFalse(
                    names.contains(mutating),
                    "read-only run still exposes mutating tool " + mutating + ": " + names);
              }
              assertTrue(names.contains("list_campaigns"), "read-only tool wrongly hidden: " + names);
              assertTrue(names.contains("get_campaign"), "read-only tool wrongly hidden: " + names);
              assertTrue(
                  names.contains("get_campaign_progress"), "read-only tool wrongly hidden: " + names);
            })
        .thenAssertResults();
  }

  @Test
  public void exposesNoStartOrApproveTool() {
    String projectId = createProject("Campaign no admin tools");
    client(projectId)
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).toList();
              assertTrue(names.contains("create_campaign"), names.toString());
              assertTrue(names.contains("transition_campaign"), names.toString());
              assertFalse(names.contains("start_campaign"), names.toString());
              assertFalse(names.contains("approve_campaign_criterion"), names.toString());
              assertTrue(names.contains("get_campaign_progress"), names.toString());
            })
        .thenAssertResults();
  }
}
