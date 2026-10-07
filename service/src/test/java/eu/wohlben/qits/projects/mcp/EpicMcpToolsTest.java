package eu.wohlben.qits.projects.mcp;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.entities.api.TestCriteria;
import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import io.restassured.http.ContentType;
import io.vertx.core.MultiMap;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The epic-refinement MCP surface: its per-connection project scoping, and the freeze coming back
 * as a readable tool error rather than as a protocol error. The lifecycle rules themselves are
 * pinned in the entities module; what is tested here is what the agent on the other end of the socket
 * actually experiences.
 */
@QuarkusTest
@TestProfile(McpStatelessTestProfile.class)
public class EpicMcpToolsTest {

  private static RequestSpecification authenticated() {
    return given()
        .header("X-Qits-User", "mcp-test")
        .header("X-Qits-Roles", "qits:admin,qits:system");
  }

  private final String fixtureUrl;

  public EpicMcpToolsTest() throws Exception {
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

  /**
   * A second repository needs its own name: a name addresses one repository per project, so cloning
   * the one fixture twice into a project would collide. A blank repository on the platform's own
   * host is the cheap way to a distinctly named sibling.
   */
  private String createBlankRepository(String projectId, String name) {
    return authenticated()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRepositoryRequest(
                null, name, RepositoryArchetype.SERVICE, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  /** Freeze an epic's scope the way the UI does, so the fixture never depends on the tool under test. */
  private void freeze(String epicId) {
    authenticated()
        .contentType(ContentType.JSON)
        .body(Map.of("target", "REFINED"))
        .when()
        .post("/projects/api/work/" + epicId + "/status")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  /** Freeze an epic and schedule it (qits-887: READY_FOR_DEV), a person's two moves. */
  private void schedule(String epicId) {
    freeze(epicId);
    authenticated()
        // Scheduling is a person's (qits-887): the session a browser keeps beside the headers.
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("mcp-test"))
        .contentType(ContentType.JSON)
        .body(Map.of("target", "READY_FOR_DEV"))
        .when()
        .post("/projects/api/work/" + epicId + "/status")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
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
    return client(projectId, null);
  }

  /**
   * The same client, narrowed to {@code repositoryId} — the shape a workspace session has, standing
   * on the one repository it holds a checkout of. Pass null to leave the whole project in scope.
   */
  private McpStreamableTestClient client(String projectId, String repositoryId) {
    return McpAssured.newStreamableClient()
        .setStateless()
        .setMcpPath("/projects/mcp")
        .setAdditionalHeaders(
            msg -> {
              MultiMap headers = MultiMap.caseInsensitiveMultiMap();
              if (projectId != null) {
                headers.add(ProjectScope.PROJECT_HEADER, projectId);
              }
              if (repositoryId != null) {
                headers.add(ProjectScope.REPOSITORY_HEADER, repositoryId);
              }
              return headers;
            })
        .build()
        .connect();
  }

  /** Call one tool and hand its response to {@code check}. */
  private void call(String projectId, String tool, Map<String, Object> args, Check check) {
    client(projectId).when().toolsCall(tool, args, check::accept).thenAssertResults();
  }

  /** The same, from a session narrowed to one repository of the project. */
  private void callNarrowed(
      String projectId, String repositoryId, String tool, Map<String, Object> args, Check check) {
    client(projectId, repositoryId).when().toolsCall(tool, args, check::accept).thenAssertResults();
  }

  /** The single-tool assertion shape, so a call site reads as one statement. */
  private interface Check {
    void accept(ToolResponse response);
  }

  /** Propose an epic through the tools and return its id. */
  private String proposeEpic(String projectId, String title) {
    String[] id = new String[1];
    call(
        projectId,
        "propose_epic",
        Map.of(
            "title",
            title,
            "description",
            "drafted by the agent",
            "acceptanceCriteria",
            TestCriteria.CRITERIA),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains("\"REPORTED\""), "a proposed epic must be a draft: " + body);
          id[0] = idIn(body);
        });
    return id[0];
  }

  /** The {@code "id"} field of a tool's JSON result — the first one, which is the row's own. */
  private static String idIn(String json) {
    int at = json.indexOf("\"id\"");
    int open = json.indexOf('"', json.indexOf(':', at) + 1);
    return json.substring(open + 1, json.indexOf('"', open + 1));
  }

  // --- Scoping --------------------------------------------------------------

  @Test
  public void rejectsEpicToolCallsWithoutAProjectHeader() {
    call(
        null,
        "list_epics",
        Map.of(),
        response -> {
          assertTrue(response.isError(), "an unscoped session must not resolve a project");
          assertTrue(text(response).contains("not scoped to a project"));
        });
  }

  @Test
  public void listsOnlyTheScopedProjectsEpics() {
    String projectA = createProject("Epics A");
    String projectB = createProject("Epics B");
    String inA = proposeEpic(projectA, "Only in A");
    String inB = proposeEpic(projectB, "Only in B");

    call(
        projectA,
        "list_epics",
        Map.of(),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains(inA), "should list its own epic: " + body);
          assertFalse(body.contains(inB), "must not leak the other project's epic: " + body);
        });
  }

  @Test
  public void refusesAnEpicOutsideTheScopedProject() {
    String projectA = createProject("Owner");
    String epicInA = proposeEpic(projectA, "Owned");
    String projectB = createProject("Stranger");

    call(
        projectB,
        "get_epic",
        Map.of("id", epicInA),
        response -> {
          assertTrue(response.isError(), "cross-project access must be refused");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });
  }

  // --- Drafting -------------------------------------------------------------

  @Test
  public void proposesADraftAndFillsInItsTree() {
    String projectId = createProject("Refinery");
    String repoId = createRepository(projectId);
    String epicId = proposeEpic(projectId, "Planning domain");

    String[] featureId = new String[1];
    call(
        projectId,
        "add_feature",
        Map.of("epicId", epicId, "title", "Lifecycle", "description", "statuses"),
        response -> {
          assertFalse(response.isError(), text(response));
          featureId[0] = idIn(text(response));
        });

    call(
        projectId,
        "add_task",
        Map.of(
            "featureId", featureId[0],
            "repositoryId", repoId,
            "title", "Add the status column",
            "description", "V3"),
        response -> assertFalse(response.isError(), text(response)));

    call(
        projectId,
        "get_epic",
        Map.of("id", epicId),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains("Planning domain"), body);
          assertTrue(body.contains("Lifecycle"), "the tree must carry its feature: " + body);
          assertTrue(
              body.contains("Add the status column"), "the tree must carry its task: " + body);
        });
  }

  @Test
  public void filtersTheListByStatus() {
    String projectId = createProject("Filtered");
    String draft = proposeEpic(projectId, "Still drafting");
    String frozen = proposeEpic(projectId, "Under way");
    freeze(frozen);

    call(
        projectId,
        "list_epics",
        Map.of("status", "REPORTED"),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains(draft), "the draft is what the filter is for: " + body);
          assertFalse(body.contains(frozen), "a frozen epic is not a draft: " + body);
        });
  }

  @Test
  public void reportsAnUnknownStatusFilterAsAToolError() {
    String projectId = createProject("Typo");
    call(
        projectId,
        "list_epics",
        Map.of("status", "REFINEING"),
        response -> {
          assertTrue(response.isError(), "a typo must not read as 'no epics'");
          assertTrue(text(response).contains("Unknown epic status"), text(response));
        });
  }

  // --- The freeze -----------------------------------------------------------

  @Test
  public void refusesToEditAFrozenEpicWithAReadableToolError() {
    String projectId = createProject("Frozen");
    String epicId = proposeEpic(projectId, "Shipped scope");
    freeze(epicId);

    // isError, NOT a JSON-RPC protocol error: the model has to be able to read the refusal and
    // move on (propose a new epic) inside the same turn.
    call(
        projectId,
        "update_epic",
        Map.of("id", epicId, "title", "Second thoughts"),
        response -> {
          assertTrue(response.isError(), "a frozen epic must refuse a structural edit");
          assertTrue(text(response).contains("frozen"), text(response));
        });
  }

  @Test
  public void refusesToAddAFeatureToAFrozenEpic() {
    String projectId = createProject("FrozenTree");
    String epicId = proposeEpic(projectId, "Shipped scope");
    freeze(epicId);

    call(
        projectId,
        "add_feature",
        Map.of("epicId", epicId, "title", "Late idea"),
        response -> {
          assertTrue(response.isError(), "a frozen epic must refuse a new feature");
          assertTrue(text(response).contains("frozen"), text(response));
        });
  }

  // --- Cross-boundary checks -----------------------------------------------

  @Test
  public void refusesATaskBoundToAnotherProjectsRepository() {
    String projectA = createProject("Planner");
    String projectB = createProject("Elsewhere");
    String foreignRepo = createRepository(projectB);
    String epicId = proposeEpic(projectA, "Cross-check");

    String[] featureId = new String[1];
    call(
        projectA,
        "add_feature",
        Map.of("epicId", epicId, "title", "Slice"),
        response -> {
          assertFalse(response.isError(), text(response));
          featureId[0] = idIn(text(response));
        });

    call(
        projectA,
        "add_task",
        Map.of(
            "featureId", featureId[0],
            "repositoryId", foreignRepo,
            "title", "Should not bind"),
        response -> {
          assertTrue(response.isError(), "a task must not bind a foreign repository");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });
  }

  /**
   * A refinement session stands on the project's wrapper repository, and the epic it is drafting
   * spans the estate — so a task's repositoryId, which says where the planned work belongs rather
   * than naming a git target to read, must be free to name any sibling of the project.
   */
  @Test
  public void filesATaskAgainstASiblingRepositoryWhenNarrowed() {
    String projectId = createProject("Estate");
    String wrapper = createRepository(projectId);
    String sibling = createBlankRepository(projectId, "estate-sibling");
    String epicId = proposeEpic(projectId, "Across the estate");

    String[] featureId = new String[1];
    callNarrowed(
        projectId,
        wrapper,
        "add_feature",
        Map.of("epicId", epicId, "title", "Slice"),
        response -> {
          assertFalse(response.isError(), text(response));
          featureId[0] = idIn(text(response));
        });

    callNarrowed(
        projectId,
        wrapper,
        "add_task",
        Map.of(
            "featureId", featureId[0],
            "repositoryId", sibling,
            "title", "Work in the sibling"),
        response -> {
          assertFalse(response.isError(), "a plan spans the project: " + text(response));
          assertTrue(
              text(response).contains(sibling), "the task must carry the sibling: " + text(response));
        });

    // And it is what was stored, not merely what the tool echoed back.
    call(
        projectId,
        "get_epic",
        Map.of("id", epicId),
        response -> {
          assertFalse(response.isError(), text(response));
          assertTrue(
              text(response).contains(sibling),
              "the stored task must carry the sibling's id: " + text(response));
        });
  }

  /** The project boundary is the rule that stays — narrowing does not make it stricter or laxer. */
  @Test
  public void refusesATaskBoundToAnotherProjectsRepositoryWhenNarrowed() {
    String projectA = createProject("Narrowed planner");
    String wrapper = createRepository(projectA);
    String foreignRepo = createRepository(createProject("Narrowed elsewhere"));
    String epicId = proposeEpic(projectA, "Cross-check while narrowed");

    String[] featureId = new String[1];
    callNarrowed(
        projectA,
        wrapper,
        "add_feature",
        Map.of("epicId", epicId, "title", "Slice"),
        response -> {
          assertFalse(response.isError(), text(response));
          featureId[0] = idIn(text(response));
        });

    callNarrowed(
        projectA,
        wrapper,
        "add_task",
        Map.of(
            "featureId", featureId[0],
            "repositoryId", foreignRepo,
            "title", "Should not bind"),
        response -> {
          assertTrue(response.isError(), "a task must not bind a foreign repository");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });
  }

  /**
   * <b>Two tools write a TASK's repositoryId and they must agree about which ids are legal.</b>
   * {@code transition_entities} restates a task's full state — repositoryId included — and consults
   * no repository guard at all, so any sibling of the project is fine there. {@code add_task} used
   * to be stricter, refusing every repository but the session's own, which is the defect: the same
   * plan could be written one way and not the other.
   *
   * <p>The pin is over the ids the two can disagree about, which is the project's repositories:
   * ids naming no repository of this project are not a parity case, because the entities module has
   * no repository table to look one up in and {@code transition_entities} therefore cannot judge
   * them. {@code add_task} still refuses those, and {@link
   * #refusesATaskBoundToAnotherProjectsRepositoryWhenNarrowed} is where that is pinned.
   */
  @Test
  public void addTaskAndTransitionEntitiesAcceptTheSameRepositoryIds() {
    String projectId = createProject("Parity");
    String wrapper = createRepository(projectId);
    String sibling = createBlankRepository(projectId, "parity-sibling");
    String epicId = proposeEpic(projectId, "Same ids either way");

    String[] featureId = new String[1];
    callNarrowed(
        projectId,
        wrapper,
        "add_feature",
        Map.of("epicId", epicId, "title", "Slice"),
        response -> {
          assertFalse(response.isError(), text(response));
          featureId[0] = idIn(text(response));
        });

    for (String repositoryId : java.util.List.of(wrapper, sibling)) {
      String[] taskId = new String[1];
      callNarrowed(
          projectId,
          wrapper,
          "add_task",
          Map.of(
              "featureId", featureId[0],
              "repositoryId", repositoryId,
              "title", "Task for " + repositoryId),
          response -> {
            assertFalse(
                response.isError(), "add_task refused " + repositoryId + ": " + text(response));
            taskId[0] = idIn(text(response));
          });

      callNarrowed(
          projectId,
          wrapper,
          "transition_entities",
          Map.of(
              "entities",
              Map.of(
                  taskId[0],
                  Map.of(
                      "archetype", "TASK",
                      "title", "Task for " + repositoryId,
                      // A task holds a status since qits-763, and a transition mints none.
                      "status", "REPORTED",
                      "repositoryId", repositoryId,
                      "membership", Map.of("parent", featureId[0])))),
          response ->
              assertFalse(
                  response.isError(),
                  "transition_entities refused " + repositoryId + ": " + text(response)));
    }
  }

  @Test
  public void refusesAFeatureOfAnotherProjectsEpic() {
    String projectA = createProject("Home");
    String epicInA = proposeEpic(projectA, "Owned");
    String projectB = createProject("Intruder");

    String[] featureId = new String[1];
    call(
        projectA,
        "add_feature",
        Map.of("epicId", epicInA, "title", "Slice"),
        response -> {
          assertFalse(response.isError(), text(response));
          featureId[0] = idIn(text(response));
        });

    // The feature id alone must not be a way past the scope: it is checked back to its epic.
    call(
        projectB,
        "remove_feature",
        Map.of("id", featureId[0]),
        response -> {
          assertTrue(response.isError(), "cross-project feature removal must be refused");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });
  }

  // --- The implemented marker ----------------------------------------------

  /** A feature with one task under {@code epicId}, and the task's id. */
  private String addTask(String projectId, String epicId, String repoId, String title) {
    String[] featureId = new String[1];
    call(
        projectId,
        "add_feature",
        Map.of("epicId", epicId, "title", "Slice for " + title),
        response -> {
          assertFalse(response.isError(), text(response));
          featureId[0] = idIn(text(response));
        });
    String[] taskId = new String[1];
    call(
        projectId,
        "add_task",
        Map.of("featureId", featureId[0], "repositoryId", repoId, "title", title),
        response -> {
          assertFalse(response.isError(), text(response));
          taskId[0] = idIn(text(response));
        });
    return taskId[0];
  }

  @Test
  public void marksATaskImplementedOnceItsEpicIsBeingImplemented() {
    String projectId = createProject("Marking");
    String repoId = createRepository(projectId);
    String epicId = proposeEpic(projectId, "Ship it");
    String taskId = addTask(projectId, epicId, repoId, "Land the column");
    schedule(epicId);

    call(
        projectId,
        "mark_task_implemented",
        Map.of("id", taskId),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains(taskId), "the tool answers about the task it marked: " + body);
          assertTrue(
              body.contains("implementedAt"),
              "and the marker is the field the dedicated result exists to carry: " + body);
        });

    // The stamp itself is read back off the row rather than parsed out of the tool's JSON, so this
    // asserts the write and not a serialization shape.
    authenticated()
        .when()
        .get("/projects/api/work/" + taskId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("implementedAt", org.hamcrest.Matchers.notNullValue());
  }

  /**
   * <b>{@code mark_task_implementing} (qits-749)</b>: it stamps the task, it is idempotent, and on a
   * READY_FOR_DEV epic it moves the epic to IMPLEMENTING — so an agent that starts without a press
   * still shows on the board. Its feature reads as implementing on the wire too.
   */
  @Test
  public void marksATaskImplementingStampsItOnceAndMovesAScheduledEpic() {
    String projectId = createProject("MarkingImplementing");
    String repoId = createRepository(projectId);
    String epicId = proposeEpic(projectId, "Start it");
    String taskId = addTask(projectId, epicId, repoId, "Begin the column");
    schedule(epicId);

    call(
        projectId,
        "mark_task_implementing",
        Map.of("id", taskId),
        response -> {
          assertFalse(response.isError(), text(response));
          assertTrue(text(response).contains("implementingAt"), text(response));
        });
    String first =
        authenticated()
            .when()
            .get("/projects/api/work/" + taskId)
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("implementingAt", org.hamcrest.Matchers.notNullValue())
            .body("implementedAt", org.hamcrest.Matchers.nullValue())
            .extract()
            .path("implementingAt");
    authenticated()
        .when()
        .get("/projects/api/work/" + epicId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("status", org.hamcrest.Matchers.equalTo("IMPLEMENTING"));
    call(
        projectId,
        "get_epic",
        Map.of("id", epicId),
        response -> {
          String body = text(response);
          assertTrue(body.contains("\"implementingOn\":\""), "the feature is implementing: " + body);
        });

    // Idempotent: a second call keeps the first time, and the epic is moved nowhere further.
    call(
        projectId,
        "mark_task_implementing",
        Map.of("id", taskId),
        response -> assertFalse(response.isError(), text(response)));
    authenticated()
        .when()
        .get("/projects/api/work/" + taskId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("implementingAt", org.hamcrest.Matchers.equalTo(first));
    authenticated()
        .when()
        .get("/projects/api/work/" + epicId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("status", org.hamcrest.Matchers.equalTo("IMPLEMENTING"));
  }

  /**
   * <b>{@code mark_task_implemented} while the epic is IMPLEMENTING, and without a prior implementing
   * mark</b>: the skip is legal, and it leaves the implementing marker empty.
   */
  @Test
  public void marksATaskImplementedWhileItsEpicIsImplementingWithOrWithoutTheImplementingMark() {
    String projectId = createProject("MarkingWhileImplementing");
    String repoId = createRepository(projectId);
    String epicId = proposeEpic(projectId, "Under way");
    String started = addTask(projectId, epicId, repoId, "Started first");
    String skipped = addTask(projectId, epicId, repoId, "Never marked started");
    schedule(epicId);
    call(
        projectId,
        "mark_task_implementing",
        Map.of("id", started),
        response -> assertFalse(response.isError(), text(response)));

    for (String taskId : List.of(started, skipped)) {
      call(
          projectId,
          "mark_task_implemented",
          Map.of("id", taskId),
          response -> assertFalse(response.isError(), text(response)));
    }
    authenticated()
        .when()
        .get("/projects/api/work/" + skipped)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("implementedAt", org.hamcrest.Matchers.notNullValue())
        .body("implementingAt", org.hamcrest.Matchers.nullValue());
    authenticated()
        .when()
        .get("/projects/api/work/" + started)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("implementedAt", org.hamcrest.Matchers.notNullValue())
        .body("implementingAt", org.hamcrest.Matchers.notNullValue());
  }

  /**
   * <b>The thread every entity has (qits-551), from the agent's side.</b> A REFINED epic's scope is
   * frozen and its thread is not: a finding goes on the epic by its qualified id, a task-level one
   * on the task's own thread, {@code get_epic} carries the epic's thread and {@code list_comments}
   * reads the task's, and an edit replaces the text and keeps the author.
   */
  @Test
  public void aFrozenEpicAndItsTaskTakeRemarksOnTheirOwnThreads() {
    String projectId = createProject("Threads");
    String repoId = createRepository(projectId);
    String epicId = proposeEpic(projectId, "Threaded");
    String taskId = addTask(projectId, epicId, repoId, "Find the race");
    freeze(epicId);
    String qualified =
        authenticated()
            .when()
            .get("/projects/api/work/" + epicId)
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("qualifiedId");

    String[] remark = new String[1];
    call(
        projectId,
        "add_comment",
        Map.of("entityId", qualified, "body", "The plan misses the index"),
        response -> {
          assertFalse(response.isError(), text(response));
          assertTrue(text(response).contains("\"entityId\":\"" + epicId + "\""), text(response));
          remark[0] = idIn(text(response));
        });
    call(
        projectId,
        "add_comment",
        Map.of("entityId", taskId, "body", "The race is in the claim loop"),
        response -> assertFalse(response.isError(), text(response)));
    call(
        projectId,
        "update_comment",
        Map.of("id", remark[0], "body", "The plan misses the index; added it"),
        response -> {
          assertFalse(response.isError(), text(response));
          assertTrue(text(response).contains("\"author\":\"dev\""), text(response));
        });

    call(
        projectId,
        "get_epic",
        Map.of("id", epicId),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains("The plan misses the index; added it"), body);
          assertFalse(
              body.contains("The race is in the claim loop"),
              "get_epic carries the epic's own thread, not its tasks': " + body);
        });
    call(
        projectId,
        "list_comments",
        Map.of("entityId", taskId),
        response -> {
          assertFalse(response.isError(), text(response));
          assertTrue(text(response).contains("The race is in the claim loop"), text(response));
        });

    String stranger = createProject("Threads Stranger");
    call(
        stranger,
        "add_comment",
        Map.of("entityId", epicId, "body", "not yours"),
        response -> {
          assertTrue(response.isError(), "another project's entity must read as not found");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });
    call(
        stranger,
        "update_comment",
        Map.of("id", remark[0], "body", "not yours"),
        response -> {
          assertTrue(response.isError(), "another project's comment must read as not found");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });
  }

  /**
   * {@code block_entity} and {@code unblock_entity} (qits-592): an epic named by its qualified id and
   * a campaign by its UUID are blocked with the reason on their own thread, the detail and listing
   * tools carry the flag, and the refusals — no reason, a task, another project — arrive as readable
   * tool errors.
   */
  @Test
  public void anEpicAndACampaignAreBlockedAndUnblockedThroughTheTools() {
    String projectId = createProject("Blocking Tools");
    String repoId = createRepository(projectId);
    String epicId = proposeEpic(projectId, "Stuck plan");
    String taskId = addTask(projectId, epicId, repoId, "A step");
    String qualified =
        authenticated()
            .when()
            .get("/projects/api/work/" + epicId)
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("qualifiedId");

    call(
        projectId,
        "block_entity",
        Map.of("id", qualified, "reason", "the idp has to release its audience first"),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains("\"entityId\":\"" + epicId + "\""), body);
          assertTrue(body.contains("\"blocked\":true"), body);
          assertTrue(body.contains("\"REPORTED\""), "a block moves no status: " + body);
        });
    call(
        projectId,
        "get_epic",
        Map.of("id", epicId),
        response -> {
          String body = text(response);
          assertTrue(body.contains("\"blocked\":true"), body);
          assertTrue(body.contains("Blocked: the idp has to release its audience first"), body);
        });
    call(
        projectId,
        "list_epics",
        Map.of(),
        response -> assertTrue(text(response).contains("\"blocked\":true"), text(response)));
    call(
        projectId,
        "unblock_entity",
        Map.of("id", epicId),
        response -> {
          assertFalse(response.isError(), text(response));
          assertTrue(text(response).contains("\"blocked\":false"), text(response));
        });

    String campaignId =
        authenticated()
            .contentType(ContentType.JSON)
            .body(Map.of("archetype", "CAMPAIGN", "project", projectId, "title", "The order"))
            .when()
            .post("/projects/api/work")
            .then()
            .statusCode(Response.Status.CREATED.getStatusCode())
            .extract()
            .path("id");
    call(
        projectId,
        "block_entity",
        Map.of("id", campaignId, "reason", "the pilot has to finish first"),
        response -> {
          assertFalse(response.isError(), text(response));
          assertTrue(text(response).contains("\"archetype\":\"CAMPAIGN\""), text(response));
        });
    call(
        projectId,
        "get_campaign",
        Map.of("id", campaignId),
        response -> {
          String body = text(response);
          assertTrue(body.contains("\"blocked\":true"), body);
          assertTrue(body.contains("Blocked: the pilot has to finish first"), body);
        });
    call(
        projectId,
        "list_campaigns",
        Map.of(),
        response -> assertTrue(text(response).contains("\"blocked\":true"), text(response)));
    call(
        projectId,
        "unblock_entity",
        Map.of("id", campaignId),
        response -> assertTrue(text(response).contains("\"blocked\":false"), text(response)));

    call(
        projectId,
        "block_entity",
        Map.of("id", epicId, "reason", "   "),
        response -> {
          assertTrue(response.isError(), "a block with no stated blocker must be refused");
          assertTrue(text(response).contains("stated blocker"), text(response));
        });
    call(
        projectId,
        "block_entity",
        Map.of("id", taskId, "reason", "waiting on somebody"),
        response -> {
          assertTrue(response.isError(), "a task has no phase of its own to block");
          assertTrue(text(response).contains("runs no phase of its own"), text(response));
        });
    String stranger = createProject("Blocking Tools Stranger");
    call(
        stranger,
        "block_entity",
        Map.of("id", epicId, "reason", "not yours"),
        response -> {
          assertTrue(response.isError(), "another project's entity must read as not found");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });
  }

  @Test
  public void refusesToMarkATaskOfAnEpicThatIsStillADraft() {
    String projectId = createProject("MarkingTooSoon");
    String repoId = createRepository(projectId);
    String epicId = proposeEpic(projectId, "Not started");
    String taskId = addTask(projectId, epicId, repoId, "Nothing has landed");

    // The refusal is EntityLifecycle.requireBeingImplemented's own — this tool adds no second copy of
    // the rule, it lands on WorkEntityService.update's marker arm and lets the lifecycle answer.
    call(
        projectId,
        "mark_task_implemented",
        Map.of("id", taskId),
        response -> {
          assertTrue(response.isError(), "a draft's task has nothing shipped to record");
          assertTrue(
              text(response)
                  .contains(
                      "Task markers move only while an epic is READY_FOR_DEV or IMPLEMENTING"),
              text(response));
        });
    // Its sibling answers a draft the same way: nothing has started on a plan still being written.
    call(
        projectId,
        "mark_task_implementing",
        Map.of("id", taskId),
        response -> {
          assertTrue(response.isError(), "a draft's task has nothing started to record");
          assertTrue(text(response).contains("READY_FOR_DEV or IMPLEMENTING"), text(response));
        });
  }

  // --- The surface ----------------------------------------------------------

  /**
   * <b>The lifecycle move is on the server since qits-394</b>, because the one dispatch path runs an
   * epic through phases each ending in the agent's own claim. Supersede stays off it: an operation on
   * a plan is not a claim about work.
   */
  @Test
  public void exposesTheLifecycleTransitionButNoSupersede() {
    String projectId = createProject("EpicTransitionSurface");
    client(projectId)
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).toList();
              assertTrue(names.contains("transition_epic"), names.toString());
              assertFalse(names.contains("supersede_epic"), names.toString());
              assertFalse(names.contains("mark_epic_implemented"), names.toString());
              assertTrue(names.contains("mark_task_implemented"), names.toString());
              assertTrue(names.contains("mark_task_implementing"), names.toString());
              assertTrue(names.contains("transition_task"), names.toString());
            })
        .thenAssertResults();
  }

  /**
   * <b>{@code transition_task} (qits-763)</b>: a task moves along its own lifecycle once its epic is
   * past REPORTED, the markers move it too, and verifying one task leaves its sibling and its epic
   * where they stand. Anything that is not a feature or a task reads as not found, with the tool to
   * use instead.
   */
  @Test
  public void transitionTaskVerifiesOneTaskOnItsOwn() {
    String projectId = createProject("TransitionTaskTool");
    String repoId = createRepository(projectId);
    String epicId = proposeEpic(projectId, "Verified piece by piece");
    String taskId = addTask(projectId, epicId, repoId, "Verified first");
    String siblingId = addTask(projectId, epicId, repoId, "Still being checked");

    call(
        projectId,
        "transition_task",
        Map.of("id", taskId, "target", "REFINED"),
        response -> {
          assertTrue(response.isError(), "a piece of a draft plan does not move on its own");
          assertTrue(text(response).contains("is REPORTED"), text(response));
        });

    schedule(epicId);
    for (String id : List.of(taskId, siblingId)) {
      call(
          projectId,
          "mark_task_implemented",
          Map.of("id", id),
          response -> {
            assertFalse(response.isError(), text(response));
            assertTrue(
                text(response).contains("\"status\":\"IMPLEMENTED\""),
                "the marker moves the task's status: " + text(response));
          });
    }

    call(
        projectId,
        "transition_task",
        Map.of("id", taskId, "target", "VERIFIED"),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains("\"archetype\":\"TASK\""), body);
          assertTrue(body.contains("\"statusBefore\":\"IMPLEMENTED\""), body);
          assertTrue(body.contains("\"status\":\"VERIFIED\""), body);
        });

    authenticated()
        .get("/projects/api/work/" + taskId)
        .then()
        .body("status", org.hamcrest.Matchers.equalTo("VERIFIED"));
    authenticated()
        .get("/projects/api/work/" + siblingId)
        .then()
        .body("status", org.hamcrest.Matchers.equalTo("IMPLEMENTED"));
    authenticated()
        .get("/projects/api/work/" + epicId)
        .then()
        .body("status", org.hamcrest.Matchers.equalTo("READY_FOR_DEV"));
    call(
        projectId,
        "get_epic",
        Map.of("id", epicId),
        response -> {
          String body = text(response);
          assertTrue(body.contains("\"status\":\"VERIFIED\""), "the tree shows it: " + body);
        });

    call(
        projectId,
        "transition_task",
        Map.of("id", epicId, "target", "IMPLEMENTED"),
        response -> {
          assertTrue(response.isError(), "an epic is not this tool's subject");
          assertTrue(text(response).contains("transition_epic"), text(response));
        });
    String stranger = createProject("TransitionTaskStranger");
    call(
        stranger,
        "transition_task",
        Map.of("id", siblingId, "target", "VERIFIED"),
        response -> {
          assertTrue(response.isError(), "another project's task is not found");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });
  }

  @Test
  public void transitionEpicMovesAlongTheLifecycleAndRefusesSupersede() {
    String projectId = createProject("EpicTransitionTool");
    String epicId = proposeEpic(projectId, "Claimed by its agent");

    call(
        projectId,
        "transition_epic",
        Map.of("id", epicId, "target", "SUPERSEDED"),
        response -> {
          assertTrue(response.isError(), "supersede is not a claim an agent makes");
          assertTrue(text(response).contains("operation on a plan"), text(response));
        });

    call(
        projectId,
        "transition_epic",
        Map.of("id", epicId, "target", "REFINED"),
        response -> {
          assertFalse(response.isError(), text(response));
          assertTrue(text(response).contains("REFINED"), text(response));
        });

    authenticated()
        .when()
        .get("/projects/api/work/" + epicId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("status", org.hamcrest.Matchers.equalTo("REFINED"));

    call(
        projectId,
        "transition_epic",
        Map.of("id", epicId, "target", "VERIFIED"),
        response -> assertTrue(response.isError(), "moves are adjacent only"));
  }

  /**
   * Scheduling is a person's decision (qits-887): the agent surface is a machine, so
   * transition_epic to READY_FOR_DEV is refused naming the gate and the epic stays REFINED, while
   * unscheduling a scheduled epic through the same tool passes.
   */
  @Test
  public void transitionEpicIsRefusedSchedulingAndMayUnschedule() {
    String projectId = createProject("EpicScheduleTool");
    String epicId = proposeEpic(projectId, "Scheduled by a person");
    freeze(epicId);

    call(
        projectId,
        "transition_epic",
        Map.of("id", epicId, "target", "READY_FOR_DEV"),
        response -> {
          assertTrue(response.isError(), "an agent may not schedule");
          assertTrue(text(response).contains("PERSON_APPROVAL"), text(response));
          assertTrue(text(response).contains("is a machine credential"), text(response));
        });
    authenticated()
        .when()
        .get("/projects/api/work/" + epicId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("status", org.hamcrest.Matchers.equalTo("REFINED"));

    authenticated()
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("mcp-test"))
        .contentType(ContentType.JSON)
        .body(Map.of("target", "READY_FOR_DEV"))
        .when()
        .post("/projects/api/work/" + epicId + "/status")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    call(
        projectId,
        "transition_epic",
        Map.of("id", epicId, "target", "REFINED"),
        response -> assertFalse(response.isError(), text(response)));
  }
}
