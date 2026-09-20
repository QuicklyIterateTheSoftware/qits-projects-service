package eu.wohlben.qits.projects.mcp;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.MultiMap;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The unified-entity MCP surface: <b>one</b> tool that restates a post-state whole, and the read
 * that lets an agent construct one.
 *
 * <p>What this class is for, as distinct from {@code EntityTransitionServiceTest} and {@code
 * EntityTransitionApiTest}: what the agent on the other end of the socket actually experiences —
 * the feature split arriving as one call, a plain edit being a map of one, the project boundary, and
 * a refusal reading as an instruction rather than as a protocol error. Every rule behind a refusal
 * is asserted in the epics module, where stating one costs no round trip.
 */
@QuarkusTest
@TestProfile(McpStatelessTestProfile.class)
public class EntityMcpToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static RequestSpecification authenticated() {
    return given()
        .header("X-Qits-User", "mcp-test")
        .header("X-Qits-Roles", "qits:admin,qits:system");
  }

  private final String fixtureUrl;

  public EntityMcpToolsTest() throws Exception {
    fixtureUrl = GitFixtures.path("testing-repo.git");
  }

  // --- Fixtures over REST ---------------------------------------------------

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

  private String createRepository(String projectId) {
    return authenticated()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRepositoryRequest(
                fixtureUrl, null, RepositoryArchetype.SERVICE, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  // --- MCP plumbing ---------------------------------------------------------

  /** All text content of a tool response joined — list tools emit one content item per element. */
  private static String text(ToolResponse response) {
    return response.content().stream()
        .map(c -> c.asText().text())
        .collect(Collectors.joining("\n"));
  }

  /** A streamable client on the repository server, scoped to {@code projectId} (or none). */
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

  /** The same server as an unattended read-only run sees it. */
  private McpStreamableTestClient readOnlyClient(String projectId) {
    return McpAssured.newStreamableClient()
        .setStateless()
        .setMcpPath("/projects/mcp?" + ReadOnlyRepositoryToolFilter.READ_ONLY_PARAM + "=true")
        .setAdditionalHeaders(
            msg -> {
              MultiMap headers = MultiMap.caseInsensitiveMultiMap();
              headers.add(ProjectScope.PROJECT_HEADER, projectId);
              return headers;
            })
        .build()
        .connect();
  }

  private void call(String projectId, String tool, Map<String, Object> args, Check check) {
    client(projectId).when().toolsCall(tool, args, check::accept).thenAssertResults();
  }

  private interface Check {
    void accept(ToolResponse response);
  }

  private static JsonNode json(ToolResponse response) {
    try {
      return JSON.readTree(text(response));
    } catch (Exception e) {
      throw new AssertionError("a tool answered something that is not JSON: " + text(response), e);
    }
  }

  /**
   * Every element a list-returning tool answered. The server emits one content item per element, so
   * the joined text is not one JSON document — each item is parsed on its own, and an item that is
   * itself an array is flattened.
   */
  private static java.util.List<JsonNode> items(ToolResponse response) {
    java.util.List<JsonNode> parsed = new java.util.ArrayList<>();
    for (var content : response.content()) {
      JsonNode node;
      try {
        node = JSON.readTree(content.asText().text());
      } catch (Exception e) {
        throw new AssertionError("a tool answered something that is not JSON: " + text(response), e);
      }
      if (node.isArray()) {
        node.forEach(parsed::add);
      } else {
        parsed.add(node);
      }
    }
    return parsed;
  }

  /** The entry for {@code id} in {@code list_entities}' answer, or null. */
  private JsonNode entity(String projectId, String id) {
    JsonNode[] found = new JsonNode[1];
    call(
        projectId,
        "list_entities",
        Map.of(),
        response -> {
          assertFalse(response.isError(), text(response));
          for (JsonNode entry : items(response)) {
            if (id.equals(entry.path("id").asText())) {
              found[0] = entry;
            }
          }
        });
    return found[0];
  }

  // --- Fixtures over the epic tools -----------------------------------------

  private String proposeEpic(String projectId, String title) {
    String[] id = new String[1];
    call(
        projectId,
        "propose_epic",
        Map.of("title", title, "description", "drafted by the agent"),
        response -> {
          assertFalse(response.isError(), text(response));
          id[0] = json(response).path("id").asText();
        });
    return id[0];
  }

  private String addFeature(String projectId, String epicId, String title) {
    String[] id = new String[1];
    call(
        projectId,
        "add_feature",
        Map.of("epicId", epicId, "title", title, "description", "a slice"),
        response -> {
          assertFalse(response.isError(), text(response));
          id[0] = json(response).path("id").asText();
        });
    return id[0];
  }

  private String addTask(String projectId, String featureId, String repoId, String title) {
    String[] id = new String[1];
    call(
        projectId,
        "add_task",
        Map.of(
            "featureId", featureId,
            "repositoryId", repoId,
            "title", title,
            "description", "the work"),
        response -> {
          assertFalse(response.isError(), text(response));
          id[0] = json(response).path("id").asText();
        });
    return id[0];
  }

  /** {@code Map.of} refuses a null value, and an explicit null parent is the statement "a root". */
  private static Map<String, Object> under(String parentId, Integer position) {
    Map<String, Object> membership = new LinkedHashMap<>();
    membership.put("parent", parentId);
    membership.put("position", position);
    return membership;
  }

  // --- The read side --------------------------------------------------------

  @Test
  public void theReadReportsArchetypeAndMembership() {
    String projectId = createProject("Readable");
    String repoId = createRepository(projectId);
    String epicId = proposeEpic(projectId, "The plan");
    String featureId = addFeature(projectId, epicId, "The part");
    String taskId = addTask(projectId, featureId, repoId, "The work");

    // Without these three facts a caller cannot state a correct transition entry at all, and none of
    // get_epic/list_epics/get_ticket/list_tickets reports any of them.
    JsonNode epic = entity(projectId, epicId);
    assertEquals("EPIC", epic.path("archetype").asText());
    assertTrue(epic.path("parent").isNull(), "an epic is a root: " + epic);

    JsonNode feature = entity(projectId, featureId);
    assertEquals("FEATURE", feature.path("archetype").asText());
    assertEquals(epicId, feature.path("parent").asText());
    assertEquals(0, feature.path("position").asInt());

    JsonNode task = entity(projectId, taskId);
    assertEquals("TASK", task.path("archetype").asText());
    assertEquals(featureId, task.path("parent").asText());
  }

  @Test
  public void theReadIsScopedToTheSessionsProject() {
    String projectA = createProject("Owner of the tree");
    String epicInA = proposeEpic(projectA, "Owned");
    String projectB = createProject("Stranger to the tree");

    assertNull(entity(projectB, epicInA), "the other project's tree must not leak");
    assertNotNull(entity(projectA, epicInA));
  }

  // --- The split, in one call -----------------------------------------------

  /**
   * The case the endpoint exists for, driven through the tool: a feature becomes an epic while its
   * tasks become features under it. <b>No ordering of single-entity calls reaches this legally</b> —
   * re-archetype the feature first and there is an epic under an epic, promote a task first and
   * there is a feature under a feature — so this test is the one that fails loudest if the tool is
   * ever split into per-entity moves.
   */
  @Test
  public void aFeatureBecomesAnEpicWhileItsTasksBecomeFeaturesInOneCall() {
    String projectId = createProject("Splitting");
    String repoId = createRepository(projectId);
    String epicId = proposeEpic(projectId, "Too big");
    String featureId = addFeature(projectId, epicId, "Really an epic");
    String first = addTask(projectId, featureId, repoId, "First slice");
    String second = addTask(projectId, featureId, repoId, "Second slice");

    Map<String, Object> entities = new LinkedHashMap<>();
    entities.put(
        featureId,
        Map.of("archetype", "EPIC", "title", "Really an epic", "status", "REFINING"));
    entities.put(
        first,
        Map.of("archetype", "FEATURE", "title", "First slice", "membership", under(featureId, 0)));
    entities.put(
        second,
        Map.of("archetype", "FEATURE", "title", "Second slice", "membership", under(featureId, 1)));

    call(
        projectId,
        "transition_entities",
        Map.of("entities", entities),
        response -> {
          assertFalse(response.isError(), text(response));
          JsonNode written = json(response);
          assertEquals("EPIC", written.path(featureId).path("archetype").asText());
          assertTrue(written.path(featureId).path("parent").isNull(), text(response));
          assertEquals("FEATURE", written.path(first).path("archetype").asText());
          assertEquals(featureId, written.path(first).path("parent").asText());
          assertEquals(1, written.path(second).path("position").asInt());
          // The demotion's cost, asserted rather than described: a FEATURE has no slot for a
          // repository, the entry did not restate one, and the column is cleared.
          assertTrue(written.path(first).path("repositoryId").isNull(), text(response));
        });

    // And it is what the read answers afterwards, which is what the next turn would work from.
    assertEquals("EPIC", entity(projectId, featureId).path("archetype").asText());
    assertEquals(featureId, entity(projectId, second).path("parent").asText());
    assertTrue(
        entity(projectId, epicId).path("parent").isNull(), "the epic it left is still a root");
  }

  // --- A plain edit ---------------------------------------------------------

  /** The ordinary write expressed here is a map of one, and the description says so out loud. */
  @Test
  public void aPlainEditIsAMapOfOne() {
    String projectId = createProject("One entry");
    String epicId = proposeEpic(projectId, "First thoughts");

    call(
        projectId,
        "transition_entities",
        Map.of(
            "entities",
            Map.of(
                epicId,
                Map.of(
                    "archetype", "EPIC",
                    "title", "Second thoughts",
                    "status", "REFINING",
                    "description", "still the same plan"))),
        response -> {
          assertFalse(response.isError(), text(response));
          assertEquals("Second thoughts", json(response).path(epicId).path("title").asText());
        });

    assertEquals("Second thoughts", entity(projectId, epicId).path("title").asText());
  }

  // --- The project boundary -------------------------------------------------

  @Test
  public void refusesAnEntityOutsideTheScopedProject() {
    String projectA = createProject("Home tree");
    String epicInA = proposeEpic(projectA, "Owned");
    String projectB = createProject("Intruding tree");

    call(
        projectB,
        "transition_entities",
        Map.of(
            "entities",
            Map.of(epicInA, Map.of("archetype", "EPIC", "title", "Taken", "status", "REFINING"))),
        response -> {
          assertTrue(response.isError(), "cross-project access must be refused");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });

    // And the row is untouched: the refusal runs before anything is written.
    assertEquals("Owned", entity(projectA, epicInA).path("title").asText());
  }

  /** A parent is an entity the request names too, so the boundary is checked there as well. */
  @Test
  public void refusesAParentOutsideTheScopedProject() {
    String projectA = createProject("Foreign parent");
    String foreignEpic = proposeEpic(projectA, "Not yours");
    String projectB = createProject("Would reparent");
    String ownEpic = proposeEpic(projectB, "Mine");

    call(
        projectB,
        "transition_entities",
        Map.of(
            "entities",
            Map.of(
                ownEpic,
                Map.of(
                    "archetype", "FEATURE",
                    "title", "Mine",
                    "membership", under(foreignEpic, 0)))),
        response -> {
          assertTrue(response.isError(), "a foreign parent must be refused");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });
  }

  // --- Existing ids only ----------------------------------------------------

  /** Nothing is created here. An id that names no entity is a refusal, never a row nobody meant. */
  @Test
  public void refusesAnIdThatNamesNothingRatherThanCreatingIt() {
    String projectId = createProject("No creation");

    call(
        projectId,
        "transition_entities",
        Map.of(
            "entities",
            Map.of(
                "ghost-id",
                Map.of("archetype", "EPIC", "title", "A ghost", "status", "REFINING"))),
        response -> {
          assertTrue(response.isError(), "an unknown id must not become a row");
          assertTrue(text(response).contains("there is no ghost-id"), text(response));
          assertTrue(text(response).contains("creates nothing"), text(response));
        });

    assertNull(entity(projectId, "ghost-id"), "nothing may have been created");
  }

  // --- The archetype gate ---------------------------------------------------

  /**
   * A refusal naming missing properties is the archetype gate, and it reads as an instruction: the
   * fix is to supply what it names, not to retry the same map.
   */
  @Test
  public void aRefusalNamingMissingPropertiesReadsAsTheArchetypeGate() {
    String projectId = createProject("Gated");
    String epicId = proposeEpic(projectId, "The plan");
    String featureId = addFeature(projectId, epicId, "Really a ticket");

    call(
        projectId,
        "transition_entities",
        Map.of(
            "entities",
            Map.of(featureId, Map.of("archetype", "TICKET", "title", "Really a ticket"))),
        response -> {
          assertTrue(response.isError(), "a TICKET states more than a title");
          String body = text(response);
          // Every complaint at once, in the registry's own vocabulary — the model fixes all of them
          // in one corrected call rather than one per round trip.
          assertTrue(body.contains("requires ticket type"), body);
          assertTrue(body.contains("requires status"), body);
        });

    assertEquals(
        "FEATURE",
        entity(projectId, featureId).path("archetype").asText(),
        "nothing is written when anything is refused");
  }

  // --- The surface ----------------------------------------------------------

  /**
   * <b>One tool, a map of entries.</b> A per-entity spelling would reintroduce at the agent surface
   * the sequence of illegal intermediate states this operation exists to avoid, so its absence is
   * asserted beside the presence of the one tool.
   *
   * <p>Note what this does <em>not</em> collide with: {@code EpicMcpToolsTest.exposesNoTransitionTool}
   * asserts that no tool named {@code transition_epic}, {@code supersede_epic} or {@code
   * mark_epic_implemented} exists — an epic <b>lifecycle</b> move, which is still a person's press
   * in the UI. This is an <b>archetype</b> transition, a different operation that shares a word, and
   * it moves no status along any lifecycle.
   */
  @Test
  public void exposesOneTransitionToolTakingAMap() {
    String projectId = createProject("Surface");
    client(projectId)
        .when()
        .toolsList(
            page -> {
              var tools = page.tools();
              var names = tools.stream().map(t -> t.name()).toList();
              assertTrue(names.contains("transition_entities"), names.toString());
              assertTrue(names.contains("list_entities"), names.toString());
              assertFalse(names.contains("transition_entity"), names.toString());
              assertFalse(names.contains("move_entity"), names.toString());
              // Still no epic lifecycle move, which the archetype transition does not become.
              assertFalse(names.contains("transition_epic"), names.toString());

              var transition =
                  tools.stream().filter(t -> t.name().equals("transition_entities")).findFirst();
              assertTrue(transition.isPresent());
              assertTrue(
                  transition.get().inputSchema().getJsonObject("properties").containsKey("entities"),
                  "the argument is the map itself: " + transition.get().inputSchema());
              String description = transition.get().description();
              assertTrue(description.contains("A PLAIN EDIT IS A MAP OF ONE"), description);
              assertTrue(description.contains("OMITTED PROPERTY IS CLEARED"), description);
              assertTrue(description.contains("EVERY ID MUST ALREADY EXIST"), description);
            })
        .thenAssertResults();
  }

  /**
   * The transition is hidden from an unattended read-only run, and the read is not. It restates
   * part of the plan in full in one transaction — every property it does not carry is cleared —
   * which is the strongest case in {@code ReadOnlyRepositoryToolFilter.MUTATING_TOOLS}; seeing the
   * plan it must not rewrite is always allowed.
   */
  @Test
  public void anUnattendedRunSeesTheTreeAndCannotRestateIt() {
    String projectId = createProject("Unattended");
    readOnlyClient(projectId)
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).toList();
              assertFalse(names.contains("transition_entities"), names.toString());
              assertTrue(names.contains("list_entities"), names.toString());
            })
        .thenAssertResults();
  }
}
