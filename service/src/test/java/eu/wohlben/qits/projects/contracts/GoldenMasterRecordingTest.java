package eu.wohlben.qits.projects.contracts;

import static io.restassured.RestAssured.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.entity.EntityStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Records qits-projects' provider golden masters</b> — {@code golden-masters/} at the repository
 * root, the source of the published golden-master artifact consumers write their pacts against.
 *
 * <p>For each (state, operation) pair in {@link #INTERACTIONS} it runs the state ({@link
 * ProviderStates}), calls the endpoint through REST-assured as the {@code %test} dev user, keeps
 * only the list entries the state created, freezes ids, instants and unique tokens ({@link
 * Freezer}) and renders {@code golden-masters/<state-slug>/<operationId>.json}; then it renders
 * {@code golden-masters/index.json} describing all of them.
 *
 * <p>It <b>compares by default</b> and fails with a unified diff per differing file — including a
 * committed {@code .json} no interaction produces any more. {@code -Dgolden.update=true} (or {@code
 * QITS_GOLDEN_UPDATE=true}) rewrites instead, and deletes such stale files; see {@link
 * GoldenFiles}.
 */
@QuarkusTest
class GoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;
  static final String PROVIDER = "qits-projects";

  /**
   * One recorded interaction.
   *
   * @param listFilteredTo the array (a {@code $.a.b} path) reduced to the entries the state created,
   *     or null — the index's {@code frozen.listFilteredTo}
   * @param sortedBy for an array the provider answers in no guaranteed order: {@code
   *     <$.path-to-array>:<field.path in each entry>}, sorted by that field's (seed-fixed) value
   *     before freezing, so ids are numbered in a stable order. Null when the order is the
   *     provider's own.
   * @param requestBody the JSON a write sends, recorded into the index as the operation's {@code
   *     body} so a consumer's pact sends the same; null for a read
   */
  record Interaction(
      String state,
      String operationId,
      String method,
      String path,
      int status,
      String listFilteredTo,
      String sortedBy,
      String requestBody) {

    /** A read: no request body. */
    Interaction(
        String state,
        String operationId,
        String method,
        String path,
        int status,
        String listFilteredTo,
        String sortedBy) {
      this(state, operationId, method, path, status, listFilteredTo, sortedBy, null);
    }
  }

  static final List<Interaction> INTERACTIONS =
      withWorkActions(
          List.of(
          new Interaction(
              ProviderStates.A_PROJECT_EXISTS,
              "getProject",
              "GET",
              "/projects/api/projects/{projectId}",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_PROJECT_EXISTS,
              "listProjects",
              "GET",
              "/projects/api/projects",
              200,
              "$.entries",
              null),
          // The picker's grid: both projects, sorted by name since the list reads with no ORDER BY.
          // Per-project reads are recorded for the first project ({projectId}) only: a state
          // records one answer per operation.
          new Interaction(
              ProviderStates.TWO_PROJECTS_EXIST,
              "listProjects",
              "GET",
              "/projects/api/projects",
              200,
              "$.entries",
              "$.entries:project.name"),
          new Interaction(
              ProviderStates.TWO_PROJECTS_EXIST,
              "listProjectRepositories",
              "GET",
              "/projects/api/projects/{projectId}/repositories",
              200,
              null,
              "$.entries:repository.name"),
          new Interaction(
              ProviderStates.TWO_PROJECTS_EXIST,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          // The picker's empty grid.
          new Interaction(
              ProviderStates.NO_PROJECTS_EXIST,
              "listProjects",
              "GET",
              "/projects/api/projects",
              200,
              "$.entries",
              null),
          new Interaction(
              ProviderStates.A_PROJECT_WITH_ONE_REPOSITORY,
              "listProjectRepositories",
              "GET",
              "/projects/api/projects/{projectId}/repositories",
              200,
              null,
              "$.entries:repository.name"),
          // listRepositories reads the rows with no ORDER BY, so the entries are sorted by name
          // here (the wrapper's random slug token blanked); a consumer must not depend on the
          // provider's order.
          new Interaction(
              ProviderStates.A_PROJECT_WITH_3_REPOSITORIES,
              "listProjectRepositories",
              "GET",
              "/projects/api/projects/{projectId}/repositories",
              200,
              null,
              "$.entries:repository.name"),
          // The repositories page's tree: components, forge twins and every backup outcome. Sorted
          // by name like the listing above.
          new Interaction(
              ProviderStates.A_PROJECT_WITH_REPOSITORIES_IN_COMPONENTS,
              "listProjectRepositories",
              "GET",
              "/projects/api/projects/{projectId}/repositories",
              200,
              null,
              "$.entries:repository.name"),
          // The whole planning tree, unfiltered: consumers filter and count on their side. Tree
          // order (roots oldest first) is the provider's own and is fixed by the seed order.
          new Interaction(
              ProviderStates.A_PROJECT_WITH_REFINED_WORK,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_PROJECT_WITH_WORK_IN_EVERY_STATUS,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.AN_EPIC_WITH_FEATURES_AND_TASKS,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_ORDERED_DEVELOPMENTS,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_ORDERED_DEVELOPMENTS,
              "getCampaign",
              "GET",
              "/projects/api/campaigns/{campaignId}",
              200,
              null,
              null),
          // The landing app's card screenshots: one state per card case no other state covers.
          new Interaction(
              ProviderStates.A_TICKET_OF_EVERY_TYPE,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_VERIFIED_EPIC_WITH_EVERY_TASK_IMPLEMENTED,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_DONE_EPIC_WITH_EVERY_TASK_IMPLEMENTED,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          // The params name every member, so both answers freeze an entity to the same id.
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_WORK_IN_EVERY_PHASE,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_WORK_IN_EVERY_PHASE,
              "getCampaign",
              "GET",
              "/projects/api/campaigns/{campaignId}",
              200,
              null,
              null),
          // One epic in two campaigns: a state records one answer per operation, so the second
          // campaign is its own state over the same seed and params (same frozen ids).
          new Interaction(
              ProviderStates.AN_EPIC_IN_TWO_CAMPAIGNS,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.AN_EPIC_IN_TWO_CAMPAIGNS,
              "getCampaign",
              "GET",
              "/projects/api/campaigns/{firstCampaignId}",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.THE_SECOND_CAMPAIGN_OF_AN_EPIC_IN_TWO_CAMPAIGNS,
              "getCampaign",
              "GET",
              "/projects/api/campaigns/{secondCampaignId}",
              200,
              null,
              null),
          // Writes: the body is recorded with the answer, and a consumer's pact sends the same.
          new Interaction(
              ProviderStates.A_VERIFIED_EPIC,
              "transitionEpic",
              "POST",
              "/projects/api/epics/{epicId}/transition",
              200,
              null,
              null,
              "{\"target\":\"DONE\"}"),
          new Interaction(
              ProviderStates.A_VERIFIED_TICKET,
              "transitionTicket",
              "POST",
              "/projects/api/tickets/{ticketId}/transition",
              200,
              null,
              null,
              "{\"target\":\"DONE\"}"),
          new Interaction(
              ProviderStates.A_PROJECT_WITH_NO_WORK,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_EXISTS,
              "getRepository",
              "GET",
              "/projects/api/repositories/{repositoryId}",
              200,
              null,
              null),
          // Open requests plus the FINALIZED tail, most recently moved first (fixed by the seed's
          // minutes); a consumer decides itself which states count as pending.
          new Interaction(
              ProviderStates.A_PROJECT_WITH_PENDING_RELEASE_REQUESTS,
              "listProjectReleaseRequests",
              "GET",
              "/projects/api/projects/{projectId}/release-requests",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_PROJECT_WITH_NO_RELEASE_REQUESTS,
              "listProjectReleaseRequests",
              "GET",
              "/projects/api/projects/{projectId}/release-requests",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.NO_PROJECT_WITH_THE_GIVEN_ID,
              "getProject",
              "GET",
              "/projects/api/projects/{projectId}",
              404,
              null,
              null),
          new Interaction(
              ProviderStates.NO_REPOSITORY_WITH_THE_GIVEN_ID,
              "getRepository",
              "GET",
              "/projects/api/repositories/{repositoryId}",
              404,
              null,
              null)));

  /**
   * The landing app's work item page (epic qits-112): the registry it reads the moves and the
   * dispatch phases from, one status move out of every ticket and epic status that has one, a PHASE
   * and a FLOW dispatch for each archetype, and the reads of an implemented ticket.
   *
   * <p>Each recorded move is the first legal one out of the state's status, read off the state
   * machine, so the table restates no move.
   */
  private static List<Interaction> withWorkActions(List<Interaction> base) {
    List<Interaction> all = new ArrayList<>(base);
    all.add(
        new Interaction(
            ProviderStates.THE_ARCHETYPE_REGISTRY,
            "listArchetypes",
            "GET",
            "/projects/api/entities/archetypes",
            200,
            null,
            null));
    moves(all, ProviderStates.TICKET_IN_STATUS, "ticketId");
    moves(all, ProviderStates.EPIC_IN_STATUS, "epicId");
    all.add(dispatch(ProviderStates.AN_IMPLEMENTED_TICKET, "ticketId", "PHASE"));
    all.add(dispatch(ProviderStates.A_REFINED_TICKET, "ticketId", "FLOW"));
    all.add(dispatch(ProviderStates.A_REPORTED_EPIC, "epicId", "PHASE"));
    all.add(dispatch(ProviderStates.A_REFINED_EPIC, "epicId", "FLOW"));
    all.add(
        new Interaction(
            ProviderStates.AN_IMPLEMENTED_TICKET,
            "getEntity",
            "GET",
            "/projects/api/entities/{ticketId}",
            200,
            null,
            null));
    all.add(
        new Interaction(
            ProviderStates.AN_IMPLEMENTED_TICKET,
            "listEntityComments",
            "GET",
            "/projects/api/entities/{ticketId}/comments",
            200,
            null,
            null));
    all.add(
        new Interaction(
            ProviderStates.AN_IMPLEMENTED_TICKET,
            "listProjectEntities",
            "GET",
            "/projects/api/projects/{projectId}/entities",
            200,
            null,
            null));
    return List.copyOf(all);
  }

  private static void moves(
      List<Interaction> all, Map<String, EntityStatus> statesByStatus, String idParam) {
    statesByStatus.forEach(
        (state, status) -> {
          List<EntityStateMachine.Transition> out = EntityStateMachine.transitionsFrom(status);
          if (out.isEmpty()) {
            return;
          }
          all.add(
              new Interaction(
                  state,
                  "moveEntityStatus",
                  "POST",
                  "/projects/api/entities/{" + idParam + "}/status",
                  200,
                  null,
                  null,
                  "{\"target\":\"" + out.get(0).to().name() + "\"}"));
        });
  }

  private static Interaction dispatch(String state, String idParam, String mode) {
    return new Interaction(
        state,
        "dispatchEntity",
        "POST",
        "/projects/api/entities/{" + idParam + "}/dispatch",
        200,
        null,
        null,
        "{\"mode\":\"" + mode + "\"}");
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\{([^}]+)}");

  @Inject ProviderStates states;

  @Test
  void goldenMastersMatchTheProvider() throws IOException {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();

    // slug -> recorded state, sorted by slug; operations sorted by operationId below
    Map<String, ObjectNode> indexStates = new TreeMap<>();
    Map<String, Map<String, ObjectNode>> indexOperations = new TreeMap<>();

    for (Interaction interaction : INTERACTIONS) {
      Recorded recorded = record(interaction);
      String slug = ProviderStates.slug(interaction.state());
      String file = slug + "/" + interaction.operationId() + ".json";

      ObjectNode state = indexStates.get(slug);
      if (state == null) {
        state = JsonNodeFactory.instance.objectNode();
        state.put("name", interaction.state());
        state.put("slug", slug);
        state.set("params", recorded.params());
        state.set("dependsOn", JsonNodeFactory.instance.arrayNode());
        indexStates.put(slug, state);
      } else if (!state.get("params").equals(recorded.params())) {
        failures.add("State '" + interaction.state() + "' froze to different params per operation");
      }

      ObjectNode operation = JsonNodeFactory.instance.objectNode();
      operation.put("operationId", interaction.operationId());
      operation.put("method", interaction.method());
      operation.put("path", interaction.path());
      if (interaction.requestBody() != null) {
        operation.set("body", JSON.readTree(interaction.requestBody()));
      }
      operation.put("status", interaction.status());
      operation.put("file", file);
      ObjectNode frozen = operation.putObject("frozen");
      frozen.set("ids", strings(recorded.freezer().idPaths()));
      frozen.set("instants", strings(recorded.freezer().instantPaths()));
      frozen.set("strings", strings(recorded.freezer().stringPaths()));
      if (interaction.listFilteredTo() == null) {
        frozen.putNull("listFilteredTo");
      } else {
        frozen.put("listFilteredTo", interaction.listFilteredTo());
      }
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }

      written.add(file);
      check(dir.resolve(file), GoldenJson.render(recorded.body()), update, failures);
    }

    ObjectNode index = JsonNodeFactory.instance.objectNode();
    index.put("formatVersion", FORMAT_VERSION);
    index.put("provider", PROVIDER);
    ArrayNode stateArray = index.putArray("states");
    indexStates.forEach(
        (slug, state) -> {
          ArrayNode operations = state.putArray("operations");
          indexOperations.get(slug).values().forEach(operations::add);
          stateArray.add(state);
        });
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenJson.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no interaction records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }

    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures) {
    String failure = GoldenFiles.check(golden, actual, update, UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  /** One interaction's frozen answer, its frozen params and what was frozen where. */
  record Recorded(JsonNode body, ObjectNode params, Freezer freezer) {}

  private Recorded record(Interaction interaction) throws IOException {
    ProviderStates.Setup setup = states.setUp(interaction.state());
    Map<String, String> params = setup.params();

    Response response;
    try {
      var request = given();
      if (interaction.requestBody() != null) {
        request = request.contentType("application/json").body(interaction.requestBody());
      }
      response = request.when().request(interaction.method(), expand(interaction.path(), params));
    } finally {
      states.cleanUp();
    }
    String raw = response.asString();
    if (response.statusCode() != interaction.status()) {
      throw new AssertionError(
          interaction.method()
              + " "
              + interaction.path()
              + " in state '"
              + interaction.state()
              + "' answered "
              + response.statusCode()
              + ", expected "
              + interaction.status()
              + ": "
              + raw);
    }
    JsonNode body = JSON.readTree(raw);
    body = recordable(body, interaction, params.values(), setup.uniqueTokens());

    Freezer freezer = new Freezer().seed(params.values()).uniqueTokens(setup.uniqueTokens());
    ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
    params.forEach((k, v) -> frozenParams.put(k, freezer.freezeParam(v)));
    return new Recorded(freezer.freeze(body), frozenParams, freezer);
  }

  /**
   * The answer reduced to what the state controls: the {@code listFilteredTo} array keeps only the
   * entries mentioning an id the state created (its param values), and a {@code sortedBy} array is
   * put in seed order — by the field's value with the state's unique tokens blanked out, since a
   * random token would otherwise decide where its entry sorts. Package-private for the machinery
   * test.
   */
  static JsonNode recordable(
      JsonNode body,
      Interaction interaction,
      Collection<String> createdIds,
      Collection<String> uniqueTokens) {
    JsonNode out = body.deepCopy();
    if (interaction.listFilteredTo() != null) {
      ArrayNode list = array(out, interaction.listFilteredTo());
      ArrayNode kept = JsonNodeFactory.instance.arrayNode();
      for (JsonNode entry : list) {
        String text = entry.toString();
        if (createdIds.stream().anyMatch(text::contains)) {
          kept.add(entry);
        }
      }
      list.removeAll();
      list.addAll(kept);
    }
    if (interaction.sortedBy() != null) {
      String[] parts = interaction.sortedBy().split(":", 2);
      ArrayNode list = array(out, parts[0]);
      List<JsonNode> entries = new ArrayList<>();
      list.forEach(entries::add);
      String[] field = parts[1].split("\\.");
      entries.sort(
          Comparator.comparing(
              entry -> {
                JsonNode node = entry;
                for (String f : field) {
                  node = node.path(f);
                }
                String key = node.asText();
                for (String token : uniqueTokens) {
                  key = key.replace(token, "");
                }
                return key;
              }));
      list.removeAll();
      list.addAll(entries);
    }
    return out;
  }

  /** The array at a {@code $.a.b} path — the only JSONPath shape the table uses. */
  private static ArrayNode array(JsonNode root, String path) {
    if (!path.startsWith("$.")) {
      throw new IllegalArgumentException("Only $.a.b paths are supported: " + path);
    }
    JsonNode node = root;
    for (String segment : path.substring(2).split("\\.")) {
      node = node.path(segment);
    }
    if (!node.isArray()) {
      throw new IllegalStateException(path + " is not an array in " + root);
    }
    return (ArrayNode) node;
  }

  private static String expand(String template, Map<String, String> params) {
    Matcher m = TEMPLATE_PARAM.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = params.get(m.group(1));
      if (value == null) {
        throw new IllegalStateException(
            "Path " + template + " names {" + m.group(1) + "}, which the state does not return");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    values.forEach(out::add);
    return out;
  }

  /** Every committed {@code .json} under the directory, relative and {@code /}-separated. */
  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }
}
