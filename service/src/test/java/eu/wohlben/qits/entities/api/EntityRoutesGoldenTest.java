package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * <b>The wire of every surviving entity route, pinned byte for byte in golden files</b> (qits-399).
 *
 * <p>Written before the four per-archetype stacks (epic, ticket, feature, task) were folded into
 * one, and committed on its own so the goldens under {@code src/test/resources/golden/entity-routes/}
 * are what the <em>pre-collapse</em> code answered. The collapse is then correct exactly when this
 * test still passes: same paths, same methods, same status codes, same field names, same JSON
 * shapes, same refusal messages. A golden that has to be regenerated to make the collapse pass is
 * the collapse moving the wire, which is the one thing it was not allowed to do.
 *
 * <p>Each scenario walks one archetype's routes in a fixed order against a fresh project and records
 * {@code request / status / body} per call. The request bodies are raw maps, never the controllers'
 * request records, so this file compiles unchanged on both sides of the refactor.
 *
 * <h2>Normalisation</h2>
 *
 * <ul>
 *   <li>Every UUID — in a path, a key, a value or inside a string — becomes {@code <id:N>}, numbered
 *       by first appearance in the scenario, so "the same id twice" survives normalisation.
 *   <li>Every ISO instant becomes {@code <instant>}.
 *   <li>The project's slug becomes {@code <project>}.
 *   <li>An audit listing's own row ids become {@code <audit-id>}, each {@code snapshot} is parsed and
 *       normalised as JSON, and the entries are <b>sorted</b>: rows written in one transaction can
 *       share a {@code changedAt}, and the repository breaks that tie on a random id.
 * </ul>
 *
 * <p>Regenerate with {@code QITS_GOLDEN_UPDATE=true} in the environment (or {@code
 * -Dgolden.update=true}) — and read the diff, because it is the review.
 */
@QuarkusTest
class EntityRoutesGoldenTest {

  private static final Path GOLDEN_DIR = Path.of("src/test/resources/golden/entity-routes");

  private static final ObjectMapper JSON =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private static final Pattern UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private static final Pattern INSTANT =
      Pattern.compile(
          "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})?");

  private final String fixtureUrl;

  EntityRoutesGoldenTest() throws Exception {
    fixtureUrl = GitFixtures.path("testing-repo.git");
  }

  // --- scenarios -------------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void epicFeatureAndTaskRoutes() throws IOException {
    Recorder r = new Recorder();
    String projectId = r.project("Golden Routes Plan");
    String repositoryId = createRepository(projectId);
    String p = "/projects/api/projects/" + projectId;

    // Epic: create, list, read.
    String epicId =
        r.call("POST", p + "/epics", map("title", "The plan", "description", "The spine"))
            .path("epic.id");
    r.call("POST", p + "/epics", map("title", " ", "description", null)); // blank title: 400
    r.call("GET", p + "/epics", null);
    r.call("GET", p + "/epics?status=REPORTED", null);
    r.call("GET", p + "/epics?status=DONE", null);
    r.call("GET", p + "/epics?status=NOPE", null); // 400
    r.call("GET", "/projects/api/projects/no-such-project/epics", null); // 404
    r.call("GET", "/projects/api/epics/" + epicId, null);
    r.call("GET", "/projects/api/epics/no-such-epic", null); // 404

    // Features under the epic.
    String featureA =
        r.call(
                "POST",
                "/projects/api/epics/" + epicId + "/features",
                map("title", "Part A", "description", "First part", "dependsOnFeatureId", null))
            .path("feature.id");
    String featureB =
        r.call(
                "POST",
                "/projects/api/epics/" + epicId + "/features",
                map("title", "Part B", "description", null, "dependsOnFeatureId", featureA))
            .path("feature.id");
    r.call(
        "POST",
        "/projects/api/epics/" + epicId + "/features",
        map("title", "Part C", "dependsOnFeatureId", "no-such-feature")); // 400
    r.call("POST", "/projects/api/epics/no-such-epic/features", map("title", "Orphan")); // 404
    r.call("GET", "/projects/api/epics/" + epicId + "/features", null);
    r.call("GET", "/projects/api/epics/no-such-epic/features", null); // 404
    r.call("GET", "/projects/api/features/" + featureA, null);
    r.call("GET", "/projects/api/features/no-such-feature", null); // 404
    r.call(
        "PUT",
        "/projects/api/features/" + featureB,
        map("title", "Part B, renamed", "description", "Now described"));
    r.call("PUT", "/projects/api/features/" + featureB, map("clearDependsOn", true));
    r.call("PUT", "/projects/api/features/" + featureA, map("dependsOnFeatureId", featureA)); // 400
    r.call(
        "PUT",
        "/projects/api/features/" + featureA,
        map("implementedOn", "2026-01-01T00:00:00Z")); // 409: markers need REFINED

    // Tasks under a feature.
    String task1 =
        r.call(
                "POST",
                "/projects/api/features/" + featureA + "/tasks",
                map(
                    "repositoryId",
                    repositoryId,
                    "title",
                    "Task one",
                    "description",
                    "Do the thing",
                    "dependsOnTaskId",
                    null))
            .path("task.id");
    String task2 =
        r.call(
                "POST",
                "/projects/api/features/" + featureA + "/tasks",
                map("repositoryId", repositoryId, "title", "Task two", "dependsOnTaskId", task1))
            .path("task.id");
    r.call(
        "POST",
        "/projects/api/features/" + featureA + "/tasks",
        map("repositoryId", "no-such-repository", "title", "Task three")); // 404
    r.call("GET", "/projects/api/features/" + featureA + "/tasks", null);
    r.call("GET", "/projects/api/tasks/" + task1, null);
    r.call("GET", "/projects/api/tasks/no-such-task", null); // 404
    r.call("PUT", "/projects/api/tasks/" + task2, map("title", "Task two, renamed"));
    r.call("PUT", "/projects/api/tasks/" + task1, map("dependsOnTaskId", task2)); // 400: cycle

    // The whole-row edit the SPA makes, on the epic.
    Map<String, Object> epicRow = new LinkedHashMap<>();
    epicRow.put("archetype", "EPIC");
    epicRow.put("title", "The plan, restated");
    epicRow.put("description", "The spine, restated");
    epicRow.put("status", "REPORTED");
    epicRow.put("membership", map("parent", null));
    r.call("POST", "/projects/api/entities/transition", Map.of(epicId, epicRow));

    // Lifecycle: freeze, the markers, the refusals, ship.
    r.call("POST", "/projects/api/epics/" + epicId + "/transition", map("target", "NOPE")); // 409
    r.call("POST", "/projects/api/epics/" + epicId + "/transition", map("target", null)); // 400
    r.call("POST", "/projects/api/epics/" + epicId + "/transition", map("target", "DONE")); // 409
    r.call("POST", "/projects/api/epics/" + epicId + "/transition", map("target", "SUPERSEDED"));
    r.call("POST", "/projects/api/epics/" + epicId + "/transition", map("target", "REFINED"));
    r.call(
        "PUT",
        "/projects/api/tasks/" + task1,
        map("implementedAt", "2026-01-01T00:00:00Z")); // markers move at REFINED
    r.call("PUT", "/projects/api/features/" + featureA, map("title", "Frozen")); // 409
    r.call("PUT", "/projects/api/tasks/" + task2, map("title", "Frozen")); // 409
    r.call(
        "POST",
        "/projects/api/features/" + featureA + "/tasks",
        map("repositoryId", repositoryId, "title", "Too late")); // 409
    r.call("DELETE", "/projects/api/tasks/" + task2, null); // 409
    r.call("DELETE", "/projects/api/features/" + featureB, null); // 409
    r.call("POST", "/projects/api/epics/" + epicId + "/transition", map("target", "IMPLEMENTED"));
    r.call("GET", "/projects/api/epics/" + epicId + "/features", null);
    r.call("GET", "/projects/api/features/" + featureA + "/tasks", null);

    // Supersede: the successor draft carries the tree.
    String successorId =
        r.call(
                "POST",
                "/projects/api/epics/" + epicId + "/transition",
                map("target", "SUPERSEDED"))
            .path("successor.id");
    r.call("GET", "/projects/api/epics/" + epicId, null);
    r.call("GET", "/projects/api/epics/" + successorId, null);
    r.call("GET", p + "/epics", null);
    List<String> copies =
        r.call("GET", "/projects/api/epics/" + successorId + "/features", null)
            .path("entries.feature.id");
    r.call("GET", "/projects/api/features/" + copies.get(0) + "/tasks", null);

    // Deletes on the draft successor, bottom up, then an epic with its subtree.
    List<String> copiedTasks =
        r.call("GET", "/projects/api/features/" + copies.get(0) + "/tasks", null)
            .path("entries.task.id");
    r.call("DELETE", "/projects/api/tasks/" + copiedTasks.get(1), null);
    r.call("DELETE", "/projects/api/tasks/no-such-task", null); // 404
    r.call("GET", "/projects/api/features/" + copies.get(0) + "/tasks", null);
    r.call("DELETE", "/projects/api/features/" + copies.get(1), null);
    r.call("DELETE", "/projects/api/features/no-such-feature", null); // 404
    r.call("GET", "/projects/api/epics/" + successorId + "/features", null);
    r.call("DELETE", "/projects/api/epics/" + successorId, null);
    r.call("DELETE", "/projects/api/epics/no-such-epic", null); // 404
    r.call("GET", "/projects/api/epics/" + successorId, null); // 404

    // The audit subtree, readable after the delete.
    r.audit("/projects/api/epics/" + epicId + "/audit");
    r.audit("/projects/api/epics/" + successorId + "/audit");

    r.assertGolden("epic-feature-task.json");
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void ticketRoutes() throws IOException {
    Recorder r = new Recorder();
    String projectId = r.project("Golden Routes Desk");
    String p = "/projects/api/projects/" + projectId;

    // Create, and intake's refusals.
    Map<String, Object> filed = new LinkedHashMap<>();
    filed.put("title", "Login button does nothing");
    filed.put("impetus", "clicking the login button does nothing on the sign-in page");
    filed.put("description", "It just sits there");
    filed.put("type", "BUG");
    filed.put("assignee", "alice");
    String ticketId = r.call("POST", p + "/tickets", filed).path("ticket.id");
    String second =
        r.call(
                "POST",
                p + "/tickets",
                map(
                    "title",
                    "Faster board",
                    "impetus",
                    "the board should load faster",
                    "type",
                    "IMPROVEMENT"))
            .path("ticket.id");
    r.call("POST", p + "/tickets", map("title", "No impetus", "type", "BUG")); // 400
    r.call("POST", p + "/tickets", map("title", "Bad type", "impetus", "x", "type", "NOPE")); // 400
    r.call("POST", "/projects/api/projects/no-such-project/tickets", filed); // 404

    // Reads.
    r.call("GET", p + "/tickets", null);
    r.call("GET", p + "/tickets?status=REPORTED", null);
    r.call("GET", p + "/tickets?status=DONE", null);
    r.call("GET", p + "/tickets?status=NOPE", null); // 400
    r.call("GET", "/projects/api/tickets/" + ticketId, null);
    r.call("GET", "/projects/api/tickets/no-such-ticket", null); // 404

    // The whole-row edit the SPA makes: a retitle, and the impetus cleared by omission.
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("archetype", "TICKET");
    row.put("title", "Login button is inert");
    row.put("description", "It just sits there");
    row.put("status", "REPORTED");
    row.put("ticketType", "IMPROVEMENT");
    row.put("assignee", "bob");
    row.put("membership", map("parent", null));
    r.call("POST", "/projects/api/entities/transition", Map.of(ticketId, row));
    r.call("GET", "/projects/api/tickets/" + ticketId, null);

    // Lifecycle.
    r.call("POST", "/projects/api/tickets/" + ticketId + "/transition", map("target", "NOPE"));
    r.call("POST", "/projects/api/tickets/" + ticketId + "/transition", map("target", null));
    r.call("POST", "/projects/api/tickets/" + ticketId + "/transition", map("target", "DONE"));
    r.call("POST", "/projects/api/tickets/" + ticketId + "/transition", map("target", "REFINED"));
    r.call("POST", "/projects/api/tickets/no-such-ticket/transition", map("target", "REFINED"));

    // Blocking.
    r.call("POST", "/projects/api/tickets/" + ticketId + "/blocked", map("blocked", true)); // 400
    r.call(
        "POST",
        "/projects/api/tickets/" + ticketId + "/blocked",
        map("blocked", true, "reason", "waiting on the idp"));
    r.call("GET", "/projects/api/tickets/" + ticketId, null);
    r.call("POST", "/projects/api/tickets/" + ticketId + "/blocked", map("blocked", false));
    r.call("POST", "/projects/api/tickets/" + second + "/transition", map("target", "DROPPED"));
    r.call(
        "POST",
        "/projects/api/tickets/" + second + "/blocked",
        map("blocked", true, "reason", "nothing to block")); // 409

    // Comments.
    String commentId =
        r.call(
                "POST",
                "/projects/api/tickets/" + ticketId + "/comments",
                map("body", "I can reproduce it"))
            .path("comment.id");
    r.call("POST", "/projects/api/tickets/" + ticketId + "/comments", map("body", " ")); // 400
    r.call("POST", "/projects/api/tickets/no-such-ticket/comments", map("body", "x")); // 404
    r.call(
        "PUT",
        "/projects/api/ticket-comments/" + commentId,
        map("body", "I can reproduce it, in dev"));
    r.call("GET", "/projects/api/tickets/" + ticketId + "/comments", null);
    r.call("GET", "/projects/api/tickets/no-such-ticket/comments", null); // 404
    r.call("DELETE", "/projects/api/ticket-comments/" + commentId, null);
    r.call("DELETE", "/projects/api/ticket-comments/no-such-comment", null); // 404
    r.call("GET", "/projects/api/tickets/" + ticketId + "/comments", null);

    // Delete, then the audit subtree keyed by the ticket's own id.
    r.call("DELETE", "/projects/api/tickets/" + ticketId, null);
    r.call("DELETE", "/projects/api/tickets/no-such-ticket", null); // 404
    r.call("GET", "/projects/api/tickets/" + ticketId, null); // 404
    r.call("GET", p + "/tickets", null);
    r.audit("/projects/api/epics/" + ticketId + "/audit");
    r.audit("/projects/api/epics/" + second + "/audit");

    r.assertGolden("ticket.json");
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void archetypeRegistry() throws IOException {
    Recorder r = new Recorder();
    r.call("GET", "/projects/api/entities/archetypes", null);
    r.assertGolden("archetypes.json");
  }

  // --- fixtures --------------------------------------------------------------------------------

  private String createRepository(String projectId) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRepositoryRequest(
                fixtureUrl, null, RepositoryArchetype.SERVICE, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(200)
        .extract()
        .path("repository.id");
  }

  /** A map that, unlike {@link Map#of}, keeps its order and admits a null value. */
  private static Map<String, Object> map(Object... keysAndValues) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      out.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return out;
  }

  // --- recording and normalisation -------------------------------------------------------------

  /** One scenario's calls, normalised as they are recorded. */
  private static final class Recorder {

    private final ArrayNode calls = JsonNodeFactory.instance.arrayNode();
    private final Map<String, String> ids = new HashMap<>();
    private String projectSlug;

    String project(String name) {
      Response created =
          given()
              .contentType(ContentType.JSON)
              .body(
                  new ProjectController.CreateProjectRequest(
                      name, null, null, null, ProjectRequests.DNS))
              .when()
              .post("/projects/api/projects");
      assertEquals(200, created.statusCode(), created.asString());
      projectSlug = created.path("project.slug");
      String id = created.path("project.id");
      text(id); // the project's id is <id:1> in every scenario
      return id;
    }

    Response call(String method, String path, Object body) throws IOException {
      RequestSpecification request = given();
      if (body != null) {
        request = request.contentType(ContentType.JSON).body(body);
      } else if (!"GET".equals(method) && !"DELETE".equals(method)) {
        request = request.contentType(ContentType.JSON);
      }
      Response response = request.when().request(method, path);
      record(method, path, body, response, false);
      return response;
    }

    void audit(String path) throws IOException {
      Response response = given().when().get(path);
      record("GET", path, null, response, true);
    }

    private void record(String method, String path, Object body, Response response, boolean audit)
        throws IOException {
      ObjectNode call = calls.addObject();
      call.put("request", method + " " + text(path));
      if (body != null) {
        call.set("body", normalise(JSON.valueToTree(body)));
      }
      call.put("status", response.statusCode());
      String raw = response.asString();
      JsonNode answer;
      try {
        answer = raw == null || raw.isBlank() ? null : JSON.readTree(raw);
      } catch (IOException notJson) {
        answer = TextNode.valueOf(raw);
      }
      if (audit && answer != null && answer.has("entries")) {
        answer = normaliseAudit((ObjectNode) answer);
      } else if (answer != null) {
        answer = normalise(answer);
      }
      call.set("response", answer);
    }

    /**
     * An audit listing: own ids masked, snapshots parsed, entries sorted — see the class javadoc.
     * Sorted once on a fully masked key so the numbering pass meets the entries in a fixed order,
     * and again after it, so the output order does not depend on how a tie fell.
     */
    private JsonNode normaliseAudit(ObjectNode answer) throws IOException {
      List<ObjectNode> entries = new ArrayList<>();
      for (JsonNode entry : answer.get("entries")) {
        ObjectNode copy = (ObjectNode) entry.deepCopy();
        copy.put("id", "<audit-id>");
        JsonNode snapshot = copy.get("snapshot");
        if (snapshot != null && snapshot.isTextual()) {
          copy.set("snapshot", JSON.readTree(snapshot.asText()));
        }
        entries.add(copy);
      }
      entries.sort(Comparator.comparing(Recorder::masked));
      ArrayNode normalised = JsonNodeFactory.instance.arrayNode();
      List<JsonNode> sorted = new ArrayList<>();
      for (ObjectNode entry : entries) {
        sorted.add(normalise(entry));
      }
      sorted.sort(Comparator.comparing(JsonNode::toString));
      sorted.forEach(normalised::add);
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      out.set("entries", normalised);
      return out;
    }

    private static String masked(JsonNode node) {
      return INSTANT
          .matcher(UUID.matcher(node.toString()).replaceAll("<id>"))
          .replaceAll("<instant>");
    }

    private JsonNode normalise(JsonNode node) {
      if (node == null || node.isNull()) {
        return node;
      }
      if (node.isTextual()) {
        return TextNode.valueOf(text(node.asText()));
      }
      if (node.isArray()) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (JsonNode element : node) {
          out.add(normalise(element));
        }
        return out;
      }
      if (node.isObject()) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
          Map.Entry<String, JsonNode> field = fields.next();
          out.set(text(field.getKey()), normalise(field.getValue()));
        }
        return out;
      }
      return node;
    }

    private String text(String value) {
      if (value == null) {
        return null;
      }
      Matcher m = UUID.matcher(value);
      StringBuilder out = new StringBuilder();
      while (m.find()) {
        String placeholder = ids.computeIfAbsent(m.group(), k -> "<id:" + (ids.size() + 1) + ">");
        m.appendReplacement(out, Matcher.quoteReplacement(placeholder));
      }
      m.appendTail(out);
      String result = INSTANT.matcher(out.toString()).replaceAll("<instant>");
      if (projectSlug != null && !projectSlug.isEmpty()) {
        result = result.replace(projectSlug, "<project>");
      }
      return result;
    }

    void assertGolden(String name) throws IOException {
      String actual = JSON.writeValueAsString(calls) + "\n";
      Path golden = GOLDEN_DIR.resolve(name);
      boolean update =
          "true".equalsIgnoreCase(System.getenv("QITS_GOLDEN_UPDATE"))
              || Boolean.getBoolean("golden.update");
      if (update) {
        Files.createDirectories(golden.getParent());
        Files.writeString(golden, actual);
        return;
      }
      if (!Files.exists(golden)) {
        fail(
            "No golden at "
                + golden.toAbsolutePath()
                + " — run once with QITS_GOLDEN_UPDATE=true and review what it wrote.");
      }
      assertEquals(Files.readString(golden), actual, "the wire of " + name + " moved");
    }
  }
}
