package eu.wohlben.qits.projects.contracts;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
   *     body} so a consumer's pact sends the same; null for a read and for a write whose operation
   *     takes no body (the openapi says which — the recording fails on a mismatch). A {@code {param}} in it — a
   *     string value or a member name that is exactly a param's name in braces — is expanded from
   *     the state's params as a path's is, and recorded unexpanded
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

  /** contract-service's "Add the CSV export", as SeededGit makes it on every run. */
  private static final String ADD_THE_CSV_EXPORT = "019f0d272475dfef0ace655cc6d8672c8cd9c80c";

  /** contract-suite-app's "Say who approves a suite release", as SeededGit makes it. */
  private static final String SAY_WHO_APPROVES = "e447df8a0cabaeafedbf4c0235e013bb29697cd9";

  /**
   * The whole state of {@link ProviderStates#A_REPORTED_TICKET}'s ticket, restated with a new title
   * and description — the body of a PUT, and an entry of the bulk transition.
   */
  private static final String REPORTED_TICKET_STATE =
      "{\"archetype\":\"TICKET\","
          + "\"title\":\"Database refuses connections during deploys\","
          + "\"description\":\"Raise the connection limit so two pools fit during a rolling"
          + " deploy.\","
          + "\"status\":\"REPORTED\","
          + "\"ticketType\":\"BUG\","
          + "\"impetus\":\"A new container fails its migration at boot: the database refuses the"
          + " connection.\","
          + "\"acceptanceCriteria\":[\"It does what it says.\"]}";

  static final List<Interaction> INTERACTIONS =
      withServiceCallers(
      withReleaseRequests(
      withWorkFamily(
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
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
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
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_PROJECT_WITH_WORK_IN_EVERY_STATUS,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.AN_EPIC_WITH_FEATURES_AND_TASKS,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.AN_EPIC_WITH_TASKS_IN_EVERY_STATUS,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.AN_EPIC_WITH_A_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.AN_EPIC_WITH_A_VERIFIED_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.AN_IMPLEMENTING_EPIC_WITH_FEATURES_IN_MIXED_STATUSES,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_ORDERED_DEVELOPMENTS,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_ORDERED_DEVELOPMENTS,
              "listWorkMembers",
              "GET",
              "/projects/api/work/{campaignQualifiedId}/members",
              200,
              null,
              null),
          // The landing app's card screenshots: one state per card case no other state covers.
          new Interaction(
              ProviderStates.A_TICKET_OF_EVERY_TYPE,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_VERIFIED_EPIC_WITH_EVERY_TASK_IMPLEMENTED,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_DONE_EPIC_WITH_EVERY_TASK_IMPLEMENTED,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
              200,
              null,
              null),
          // The params name every member, so both answers freeze an entity to the same id.
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_WORK_IN_EVERY_PHASE,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_WORK_IN_EVERY_PHASE,
              "listWorkMembers",
              "GET",
              "/projects/api/work/{campaignQualifiedId}/members",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_A_DONE_A_VERIFIED_AND_AN_IMPLEMENTING_EPIC,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_A_DONE_A_VERIFIED_AND_AN_IMPLEMENTING_EPIC,
              "listWorkMembers",
              "GET",
              "/projects/api/work/{campaignQualifiedId}/members",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_A_DONE_A_VERIFIED_AND_AN_IMPLEMENTING_EPIC,
              "getWork",
              "GET",
              "/projects/api/work/{qualifiedId}",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_A_DONE_A_VERIFIED_AND_AN_IMPLEMENTING_EPIC,
              "listWorkComments",
              "GET",
              "/projects/api/work/{qualifiedId}/comments",
              200,
              null,
              null),
          // One epic in two campaigns: a state records one answer per operation, so the second
          // campaign is its own state over the same seed and params (same frozen ids).
          new Interaction(
              ProviderStates.AN_EPIC_IN_TWO_CAMPAIGNS,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.AN_EPIC_IN_TWO_CAMPAIGNS,
              "listWorkMembers",
              "GET",
              "/projects/api/work/{firstCampaignQualifiedId}/members",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.THE_SECOND_CAMPAIGN_OF_AN_EPIC_IN_TWO_CAMPAIGNS,
              "listWorkMembers",
              "GET",
              "/projects/api/work/{secondCampaignQualifiedId}/members",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_PROJECT_WITH_NO_WORK,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work",
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
              null))))));

  /**
   * The landing app's release-request pages (epic qits-112): every read the detail page makes, in
   * every detail state ({@link ProviderStates#RELEASE_REQUEST_DETAILS}), and each door it offers,
   * recorded where it answers: decline, withdraw and the source priority on the request awaiting
   * approval; approve where the build still runs (a green one would start a release inside the
   * call); a pipeline rerun per phase; the automation rerun and the waiver where an automation
   * failed. Plus the project-wide list and its withdraw.
   */
  private static List<Interaction> withReleaseRequests(List<Interaction> base) {
    List<Interaction> all = new ArrayList<>(base);
    String repository = "/projects/api/repositories/{repositoryId}";
    String request = repository + "/release-requests/{requestId}";
    for (String state : ProviderStates.RELEASE_REQUEST_DETAILS) {
      boolean estate = ProviderStates.RELEASE_REQUEST_ESTATE_DETAILS.contains(state);
      boolean folded = !state.equals(ProviderStates.A_CONFLICTED_RELEASE_REQUEST);
      all.add(read(state, "getReleaseRequest", request));
      all.add(read(state, "listReleaseRequestCommits", request + "/commits"));
      all.add(read(state, "listReleaseRequestChanges", request + "/changes"));
      all.add(read(state, "getReleaseRequestArtifacts", request + "/artifacts"));
      all.add(read(state, "listReleaseRequestApprovals", request + "/approvals"));
      if (folded) {
        all.add(read(state, "listCommitBuilds", repository + "/commits/{mergedSha}/builds"));
        all.add(
            read(
                state,
                "getReleaseRequestChangeDiff",
                request + "/changes/diff?path=" + (estate ? "README.md" : "src/invoice.txt")));
      }
      if (estate) {
        String submodule = "?path=" + ProviderStates.ESTATE_SUBMODULE;
        all.add(
            read(
                state,
                "getReleaseRequestSubmoduleChanges",
                request + "/changes/submodule" + submodule));
        all.add(
            read(
                state,
                "getReleaseRequestSubmoduleChangeDiff",
                request + "/changes/submodule/diff" + submodule + "&file=src/export.txt"));
      }
    }
    String awaiting = ProviderStates.A_RELEASE_REQUEST_AWAITING_APPROVAL;
    all.add(read(awaiting, "listRepositoryReleaseRequests", repository + "/release-requests"));
    // Every automation kind is listed, the one that does not apply included — on both answers.
    String notApplicable = ProviderStates.A_RELEASE_REQUEST_WITH_AN_AUTOMATION_THAT_DOES_NOT_APPLY;
    all.add(read(notApplicable, "getReleaseRequest", request));
    all.add(
        read(notApplicable, "listRepositoryReleaseRequests", repository + "/release-requests"));
    all.add(
        write(
            awaiting,
            "declineReleaseRequest",
            "POST",
            request + "/decline",
            "{\"mergedSha\":\"{mergedSha}\","
                + "\"note\":\"The README promises an approval policy nobody has signed off yet.\"}"));
    all.add(
        write(
            awaiting,
            "withdrawReleaseRequest",
            "POST",
            request + "/withdraw",
            "{\"reason\":\"The export moves to next quarter's release.\"}"));
    all.add(
        write(
            awaiting,
            "setReleaseSourcePriority",
            "POST",
            request + "/sources/priority",
            "{\"branch\":\"docs/approval\",\"priority\":\"BLOCKING\"}"));
    all.add(
        write(
            ProviderStates.A_RELEASE_REQUEST_AWAITING_APPROVAL_WHILE_ITS_BUILD_RUNS,
            "approveReleaseRequest",
            "POST",
            request + "/approve",
            "{\"mergedSha\":\"{mergedSha}\","
                + "\"note\":\"Read the diff: the pin moves to the export release.\"}"));
    all.add(
        write(
            ProviderStates.A_RELEASED_RELEASE_REQUEST,
            "rerunReleasePipelinePhase",
            "POST",
            request + "/pipeline/DEPLOY/rerun",
            null));
    all.add(
        write(
            ProviderStates.A_RELEASE_REQUEST_WHOSE_PUBLISH_FAILED,
            "rerunReleasePipelinePhase",
            "POST",
            request + "/pipeline/PUBLISH/rerun",
            null));
    all.add(
        write(
            ProviderStates.A_RELEASE_REQUEST_REJECTED_BY_ITS_BUILD,
            "rerunReleasePipelinePhase",
            "POST",
            request + "/pipeline/QA/rerun",
            null));
    String held = ProviderStates.A_RELEASE_REQUEST_HELD_BY_A_FAILED_AUTOMATION;
    all.add(
        new Interaction(
            held,
            "rerunReleaseRequestAutomation",
            "POST",
            request + "/automations/estate-pins/runs",
            202,
            null,
            null,
            null));
    all.add(
        write(
            held,
            "waiveReleaseRequestAutomations",
            "POST",
            request + "/automations/waivers",
            "{\"foldSha\":\"{mergedSha}\","
                + "\"reason\":\"qits-maintenance cannot read the suite; the pin was checked by"
                + " hand.\"}"));
    // One commit of "What this release folds in", opened. The fold is a merge, whose changes
    // against no named parent are empty, so a commit it brought in is opened instead: shas are
    // stable because the states seed through SeededGit. contract-service's "Add the CSV export"
    // adds a file; contract-suite-app's "Say who approves a suite release" changes one.
    String commits = repository + "/commits/";
    all.add(
        read(
            ProviderStates.A_RELEASED_RELEASE_REQUEST,
            "listCommitChanges",
            commits + ADD_THE_CSV_EXPORT + "/changes"));
    all.add(
        read(
            ProviderStates.A_RELEASED_RELEASE_REQUEST,
            "getCommitFileDiff",
            commits + ADD_THE_CSV_EXPORT + "/diff?path=src/export.txt"));
    all.add(read(awaiting, "listCommitChanges", commits + SAY_WHO_APPROVES + "/changes"));
    all.add(
        read(awaiting, "getCommitFileDiff", commits + SAY_WHO_APPROVES + "/diff?path=README.md"));
    String list = ProviderStates.A_PROJECT_WITH_RELEASE_REQUESTS_IN_EVERY_STATE;
    all.add(
        read(list, "listProjectReleaseRequests", "/projects/api/projects/{projectId}/release-requests"));
    all.add(
        write(
            list,
            "withdrawReleaseRequest",
            "POST",
            request + "/withdraw",
            "{\"reason\":\"The export moves to next quarter's release.\"}"));
    return List.copyOf(all);
  }


  /**
   * The work family's own writes and thread operations ({@code /projects/api/work}, qits-969, epic
   * qits-965), recorded once each. Since qits-976 the work-entity surface is this family alone: the
   * per-archetype and {@code /entities} operations this table once recorded beside their {@code
   * /work} twins are deleted, and only the twins remain.
   */
  private static List<Interaction> withWorkFamily(List<Interaction> base) {
    List<Interaction> all = new ArrayList<>(base);
    all.add(
        read(
            ProviderStates.THE_ARCHETYPE_REGISTRY,
            "getWorkArchetypeSchema",
            "/projects/api/work/archetypes/TICKET/schemas/create"));
    all.add(
        new Interaction(
            ProviderStates.A_PROJECT_WITH_NO_WORK,
            "createWork",
            "POST",
            "/projects/api/work",
            201,
            null,
            null,
            "{\"archetype\":\"TICKET\",\"project\":\"{projectId}\","
                + "\"title\":\"Export fails for an empty quarter\","
                + "\"impetus\":\"The CSV export answers 500 when the range holds no invoice.\","
                + "\"ticketType\":\"BUG\"}"));
    all.add(
        write(
            ProviderStates.A_REPORTED_TICKET,
            "patchWork",
            "PATCH",
            "/projects/api/work/{qualifiedId}",
            "{\"title\":\"Database refuses connections during deploys\"}"));
    all.add(
        write(
            ProviderStates.A_REPORTED_TICKET,
            "putWork",
            "PUT",
            "/projects/api/work/{qualifiedId}",
            REPORTED_TICKET_STATE));
    all.add(
        write(
            ProviderStates.A_REPORTED_TICKET,
            "transitionWork",
            "POST",
            "/projects/api/work/transition",
            "{\"{qualifiedId}\":" + REPORTED_TICKET_STATE + "}"));
    all.add(
        write(
            ProviderStates.A_REPORTED_TICKET,
            "setWorkBlocked",
            "POST",
            "/projects/api/work/{qualifiedId}/blocked",
            "{\"blocked\":true,"
                + "\"reason\":\"Waiting for the database team to confirm the new limit.\"}"));
    // The derived block (qits-895): blocked, AGENT_WAITING, the fixed sentence, and still
    // dispatchable — no gate reads it.
    all.add(
        read(
            ProviderStates.A_TICKET_ITS_AGENT_IS_WAITING_ON,
            "getWork",
            "/projects/api/work/{qualifiedId}"));
    all.add(
        read(
            ProviderStates.A_TICKET_ITS_AGENT_IS_WAITING_ON,
            "getWorkDispatch",
            "/projects/api/work/{qualifiedId}/dispatch"));
    all.add(read(ProviderStates.A_TICKET_WITH_A_COMMENT, "getWork", "/projects/api/work/{qualifiedId}"));
    all.add(
        read(
            ProviderStates.A_TICKET_WITH_A_COMMENT,
            "listWorkComments",
            "/projects/api/work/{qualifiedId}/comments"));
    all.add(
        write(
            ProviderStates.A_TICKET_WITH_A_COMMENT,
            "addWorkComment",
            "POST",
            "/projects/api/work/{qualifiedId}/comments",
            "{\"body\":\"The range 2026-07-01 to 2026-09-30 reproduces it.\"}"));
    all.add(
        write(
            ProviderStates.A_TICKET_WITH_A_COMMENT,
            "editWorkComment",
            "PATCH",
            "/projects/api/work/{qualifiedId}/comments/{commentId}",
            "{\"body\":\"It answers 500 when the range holds no invoice at all.\"}"));
    all.add(
        new Interaction(
            ProviderStates.A_TICKET_WITH_A_COMMENT,
            "deleteWorkComment",
            "DELETE",
            "/projects/api/work/{qualifiedId}/comments/{commentId}",
            200,
            null,
            null));
    return withWorkSubresources(all);
  }

  /**
   * The work family's sub-resources (qits-970): every operation recorded once at least — the
   * dossier's reads and writes (a ticket's, writable at every status; pages by slug), the epic's
   * figures, the children, the history, a campaign's progress and membership writes, the dispatch
   * read, the workspaces, the refinement room and the delete. {@code getWorkDossierAssetContent}
   * serves bytes and has no golden master.
   */
  private static List<Interaction> withWorkSubresources(List<Interaction> base) {
    List<Interaction> all = new ArrayList<>(base);
    all.add(
        read(
            ProviderStates.AN_EPIC_IN_DETAIL,
            "getWorkDossierPage",
            "/projects/api/work/{qualifiedId}/dossier/data-flow"));
    all.add(
        new Interaction(
            ProviderStates.A_BUG_TICKET_IN_DETAIL,
            "createWorkDossierPage",
            "POST",
            "/projects/api/work/{qualifiedId}/dossier",
            201,
            null,
            null,
            "{\"title\":\"Rollback plan\","
                + "\"body\":\"Revert billing-service to the previous version; no data moves.\"}"));
    all.add(
        write(
            ProviderStates.A_BUG_TICKET_IN_DETAIL,
            "putWorkDossierPage",
            "PUT",
            "/projects/api/work/{qualifiedId}/dossier/reproduction",
            "{\"body\":\"1. Create an invoice with three lines of 0.335 EUR each.\\n"
                + "2. Read its total: 1.02 EUR.\",\"version\":0}"));
    all.add(
        write(
            ProviderStates.A_BUG_TICKET_IN_DETAIL,
            "moveWorkDossierPage",
            "POST",
            "/projects/api/work/{qualifiedId}/dossier/affected-invoices/move",
            "{\"position\":0}"));
    all.add(
        new Interaction(
            ProviderStates.A_BUG_TICKET_IN_DETAIL,
            "deleteWorkDossierPage",
            "DELETE",
            "/projects/api/work/{qualifiedId}/dossier/affected-invoices",
            200,
            null,
            null));
    all.add(
        write(
            ProviderStates.AN_EPIC_WITH_A_SKETCH_TO_INLINE,
            "inlineWorkDossierAsset",
            "POST",
            "/projects/api/work/{qualifiedId}/dossier-assets",
            "{\"sourceId\":\"{sketchId}\",\"kind\":\"IMAGE\"}"));
    all.add(
        read(
            ProviderStates.AN_EPIC_IN_DETAIL,
            "listWorkChildren",
            "/projects/api/work/{qualifiedId}/children"));
    all.add(
        read(
            ProviderStates.A_FEATURE_IN_DETAIL,
            "listWorkChildren",
            "/projects/api/work/{qualifiedId}/children"));
    all.add(
        new Interaction(
            ProviderStates.A_REPORTED_EPIC,
            "createWorkChild",
            "POST",
            "/projects/api/work/{qualifiedId}/children",
            201,
            null,
            null,
            "{\"title\":\"CSV export\","
                + "\"description\":\"Stream a date range of invoices as CSV.\"}"));
    all.add(
        read(ProviderStates.A_REPORTED_TICKET, "getWorkAudit", "/projects/api/work/{qualifiedId}/audit"));
    all.add(
        read(
            ProviderStates.A_CAMPAIGN_IN_DETAIL,
            "getWorkProgress",
            "/projects/api/work/{qualifiedId}/progress"));
    String members = "/projects/api/work/{qualifiedId}/members";
    all.add(read(ProviderStates.A_CAMPAIGN_WITH_MEMBERS_TO_EDIT, "listWorkMembers", members));
    all.add(
        new Interaction(
            ProviderStates.A_CAMPAIGN_WITH_MEMBERS_TO_EDIT,
            "addWorkMember",
            "POST",
            members,
            201,
            null,
            null,
            "{\"entityId\":\"{outsideTicketQualifiedId}\"}"));
    all.add(
        write(
            ProviderStates.A_CAMPAIGN_WITH_MEMBERS_TO_EDIT,
            "moveWorkMember",
            "PUT",
            members + "/{lastMembershipId}/position",
            "{\"position\":0}"));
    all.add(
        new Interaction(
            ProviderStates.A_CAMPAIGN_WITH_MEMBERS_TO_EDIT,
            "removeWorkMember",
            "DELETE",
            members + "/{lastMembershipId}",
            200,
            null,
            null));
    all.add(
        write(
            ProviderStates.A_CAMPAIGN_WITH_MEMBERS_TO_EDIT,
            "setWorkMemberCondition",
            "PUT",
            members + "/{firstMembershipId}/condition",
            "{\"groups\":[{\"criteria\":[{\"kind\":\"ENTITY_STATUS\","
                + "\"predicate\":{\"entityId\":\"{epicQualifiedId}\",\"status\":\"VERIFIED\"}}]}]}"));
    all.add(
        write(
            ProviderStates.A_CAMPAIGN_WITH_MEMBERS_TO_EDIT,
            "approveWorkMemberCriterion",
            "POST",
            members + "/{lastMembershipId}/criteria/{approvalCriterionId}/approve",
            "{\"note\":\"Finance signed the totals off.\"}"));
    // A campaign shown anywhere can have its description read (qits-965): every state recording a
    // campaign's members reads the campaign itself too, by the same qualified id. The detail
    // states that focus another entity already record getWork for it — one answer per operation —
    // and read the campaign as "a campaign in detail", over the same seed and frozen ids.
    all.add(
        read(
            ProviderStates.A_CAMPAIGN_WITH_MEMBERS_TO_EDIT,
            "getWork",
            "/projects/api/work/{qualifiedId}"));
    for (Map.Entry<String, String> campaign :
        List.of(
            Map.entry(ProviderStates.A_CAMPAIGN_WITH_ORDERED_DEVELOPMENTS, "campaignQualifiedId"),
            Map.entry(ProviderStates.A_CAMPAIGN_WITH_WORK_IN_EVERY_PHASE, "campaignQualifiedId"),
            Map.entry(ProviderStates.AN_EPIC_IN_TWO_CAMPAIGNS, "firstCampaignQualifiedId"),
            Map.entry(
                ProviderStates.THE_SECOND_CAMPAIGN_OF_AN_EPIC_IN_TWO_CAMPAIGNS,
                "secondCampaignQualifiedId"))) {
      all.add(
          read(campaign.getKey(), "getWork", "/projects/api/work/{" + campaign.getValue() + "}"));
    }
    all.add(
        read(
            ProviderStates.A_REPORTED_TICKET,
            "getWorkDispatch",
            "/projects/api/work/{qualifiedId}/dispatch"));
    all.add(
        read(
            ProviderStates.A_REPORTED_TICKET,
            "listWorkWorkspaces",
            "/projects/api/work/{qualifiedId}/workspaces"));
    all.add(
        read(
            ProviderStates.A_REPORTED_TICKET,
            "getWorkRefinement",
            "/projects/api/work/{qualifiedId}/refinement"));
    // A room's row id is a database sequence no freezing reaches, so the open is recorded where it
    // is refused: a REFINED ticket's refine phase has run.
    all.add(
        new Interaction(
            ProviderStates.A_REFINED_TICKET,
            "startWorkRefinement",
            "POST",
            "/projects/api/work/{qualifiedId}/refinement",
            409,
            null,
            null));
    all.add(
        new Interaction(
            ProviderStates.A_REPORTED_TICKET,
            "deleteWork",
            "DELETE",
            "/projects/api/work/{qualifiedId}",
            200,
            null,
            null));
    return List.copyOf(all);
  }

  /**
   * What other services, the bootstrap and the CLI call (round 2 of qits-1149): the catalogue, the
   * launch pins, the name lookup and the adoption, the repository create, the release-request
   * create, join and filtered lists, the archetype registry's other schemas, the agent-waiting
   * frame and the tag collection.
   */
  private static List<Interaction> withServiceCallers(List<Interaction> base) {
    List<Interaction> all = new ArrayList<>(base);
    // Every repository in the database: filtered to the state's own, sorted by name since the
    // wrapper's random token would otherwise decide its place.
    all.add(
        new Interaction(
            ProviderStates.A_PROJECT_WITH_3_REPOSITORIES,
            "listRepositories",
            "GET",
            "/projects/api/repositories",
            200,
            "$.repositories",
            "$.repositories:name"));
    all.add(read(ProviderStates.AN_AGENT_LAUNCH_IMAGE_IN_USE, "listLaunchPins", "/projects/api/pins"));
    String byName = "/projects/api/projects/{projectId}/repositories/by-name/{repoName}";
    all.add(read(ProviderStates.A_REPOSITORY_EXISTS, "resolveRepositoryName", byName));
    all.add(
        new Interaction(
            ProviderStates.NO_PROJECT_WITH_THE_GIVEN_ID,
            "resolveRepositoryName",
            "GET",
            byName,
            404,
            null,
            null));
    all.add(
        write(
            ProviderStates.A_PROJECT_AND_AN_UNADOPTED_REPOSITORY_ON_THE_GIT_HOST,
            "adoptRepository",
            "POST",
            "/projects/api/projects/{projectId}/repositories/adopt",
            "{\"repositoryId\":\"{repositoryId}\",\"name\":\"{repoName}\","
                + "\"archetype\":\"SERVICE\"}"));
    all.add(
        write(
            ProviderStates.A_PROJECT_TO_CREATE_A_REPOSITORY_IN,
            "createProjectRepository",
            "POST",
            "/projects/api/projects/{projectId}/repositories",
            "{\"name\":\"contract-service\",\"component\":\"contract\"}"));
    String requests = "/projects/api/repositories/{repositoryId}/release-requests";
    all.add(
        write(
            ProviderStates.A_REPOSITORY_WITH_A_BRANCH_TO_RELEASE,
            "createReleaseRequest",
            "POST",
            requests,
            "{\"branch\":\"feature/export\",\"summary\":\"Release the CSV export\","
                + "\"priority\":\"LOWEST\"}"));
    all.add(
        write(
            ProviderStates.A_RELEASE_REQUEST_OPEN_TO_ANOTHER_BRANCH,
            "addReleaseRequestSource",
            "POST",
            requests + "/{requestId}/sources",
            "{\"branch\":\"fix/rounding\",\"priority\":\"LOWEST\"}"));
    all.add(
        read(
            ProviderStates.A_REPOSITORY_WITH_RELEASED_RELEASE_REQUESTS,
            "listRepositoryReleaseRequests",
            requests + "?state=RELEASED"));
    all.add(
        read(
            ProviderStates.A_REPOSITORY_WITH_A_RELEASE_NOT_MERGED_TO_MAIN,
            "listRepositoryReleaseRequests",
            requests + "?state=all"));
    all.add(
        read(
            ProviderStates.THE_ARCHETYPE_REGISTRYS_UPDATE_SCHEMA,
            "getWorkArchetypeSchema",
            "/projects/api/work/archetypes/TICKET/schemas/update"));
    all.add(
        read(
            ProviderStates.THE_ARCHETYPE_REGISTRYS_TRANSITION_SCHEMA,
            "getWorkArchetypeSchema",
            "/projects/api/work/archetypes/TICKET/schemas/transition"));
    // Keyed by the entity id, as qits-workspaces sends it; 204 and no body.
    all.add(
        new Interaction(
            ProviderStates.A_TICKET_WITH_A_DISPATCHED_AGENT,
            "reportWorkAgentWaiting",
            "POST",
            "/projects/api/work/{ticketId}/agent-waiting",
            204,
            null,
            null,
            "{\"waiting\":true,\"cause\":\"Stop\",\"sessionId\":\"contract-session\"}"));
    // A real run, as the orchestrator's schedule makes it; the six pin sources pin nothing.
    all.add(
        write(
            ProviderStates.REPOSITORIES_WITH_DECOMMISSIONABLE_TAGS,
            "collectTags",
            "POST",
            "/projects/api/gc/tags",
            "{\"dryRun\":false,\"pins\":{\"deployments\":{\"pins\":[]},\"ciDaemon\":{},"
                + "\"dependencies\":{\"pins\":[]},\"configuredImages\":{\"pins\":[]},"
                + "\"workspaceLaunches\":{\"pins\":[]},\"projectLaunches\":{\"pins\":[]}}}"));
    return List.copyOf(all);
  }

  private static Interaction write(
      String state, String operationId, String method, String path, String body) {
    return new Interaction(state, operationId, method, path, 200, null, null, body);
  }

  /**
   * The landing app's work item page (epic qits-112): the registry it reads the moves and the
   * dispatch phases from, one status move out of every ticket and epic status that has one, a PHASE
   * and a FLOW dispatch for each archetype, and the reads of an implemented ticket.
   *
   * <p>Each recorded move is the first legal one out of the state's status, read off the state
   * machine, so the table restates no move. {@link #withWorkDetail} adds the detail page's reads.
   */
  private static List<Interaction> withWorkActions(List<Interaction> base) {
    return withWorkDetail(withWorkActionsOnly(base));
  }

  /**
   * The landing app's detail page (epic qits-112), one state per archetype and ticket type over one
   * seeded project ({@link ProviderStates#IN_DETAIL}). Each records the entity and its thread by
   * the qualified id the page's route carries, and the project's entity list, which holds every
   * child (parent, qualified id, title, archetype, status) and the shell's breadcrumbs. The epic and
   * the tickets add their dossier — the epic its figures too — and every campaign member, and the
   * campaign itself, add the campaign.
   */
  private static List<Interaction> withWorkDetail(List<Interaction> base) {
    List<Interaction> all = new ArrayList<>(base);
    ProviderStates.IN_DETAIL.forEach(
        (state, focus) -> {
          all.add(read(state, "getWork", "/projects/api/work/{qualifiedId}"));
          all.add(read(state, "listWorkComments", "/projects/api/work/{qualifiedId}/comments"));
          all.add(read(state, "listProjectWork", "/projects/api/projects/{projectId}/work"));
        });
    for (String member :
        List.of(
            ProviderStates.AN_EPIC_IN_DETAIL,
            ProviderStates.A_BUG_TICKET_IN_DETAIL,
            ProviderStates.AN_IMPROVEMENT_TICKET_IN_DETAIL,
            ProviderStates.A_CAMPAIGN_IN_DETAIL)) {
      all.add(read(member, "listWorkMembers", "/projects/api/work/{campaignQualifiedId}/members"));
    }
    for (String owner :
        List.of(
            ProviderStates.AN_EPIC_IN_DETAIL,
            ProviderStates.A_BUG_TICKET_IN_DETAIL,
            ProviderStates.AN_IMPROVEMENT_TICKET_IN_DETAIL,
            ProviderStates.A_MAINTENANCE_TICKET_IN_DETAIL)) {
      all.add(read(owner, "listWorkDossier", "/projects/api/work/{qualifiedId}/dossier"));
    }
    all.add(
        read(
            ProviderStates.AN_EPIC_IN_DETAIL,
            "listWorkDossierAssets",
            "/projects/api/work/{qualifiedId}/dossier-assets"));
    return List.copyOf(all);
  }

  private static Interaction read(String state, String operationId, String path) {
    return new Interaction(state, operationId, "GET", path, 200, null, null);
  }

  private static List<Interaction> withWorkActionsOnly(List<Interaction> base) {
    List<Interaction> all = new ArrayList<>(base);
    all.add(
        new Interaction(
            ProviderStates.THE_ARCHETYPE_REGISTRY,
            "listWorkArchetypes",
            "GET",
            "/projects/api/work/archetypes",
            200,
            null,
            null));
    moves(all, ProviderStates.TICKET_IN_STATUS);
    moves(all, ProviderStates.EPIC_IN_STATUS);
    all.add(dispatch(ProviderStates.AN_IMPLEMENTED_TICKET, "PHASE"));
    // qits-887: implement runs from READY_FOR_DEV; a REFINED entity waits for a person to schedule
    // it — and since qits-1075 a person's Dispatch press is that scheduling: the recording rides a
    // person's session, so the press schedules the entity and starts implement (a 200). A machine's
    // press there is still the 409, which no consumer's caller makes.
    all.add(dispatch(ProviderStates.A_REFINED_TICKET, "FLOW"));
    all.add(dispatch(ProviderStates.A_READY_FOR_DEV_TICKET, "FLOW"));
    all.add(dispatch(ProviderStates.A_REPORTED_EPIC, "PHASE"));
    all.add(dispatch(ProviderStates.A_REFINED_EPIC, "FLOW"));
    all.add(dispatch(ProviderStates.A_READY_FOR_DEV_EPIC, "FLOW"));
    all.add(
        new Interaction(
            ProviderStates.AN_IMPLEMENTED_TICKET,
            "getWork",
            "GET",
            "/projects/api/work/{qualifiedId}",
            200,
            null,
            null));
    all.add(
        new Interaction(
            ProviderStates.AN_IMPLEMENTED_TICKET,
            "listWorkComments",
            "GET",
            "/projects/api/work/{qualifiedId}/comments",
            200,
            null,
            null));
    all.add(
        new Interaction(
            ProviderStates.AN_IMPLEMENTED_TICKET,
            "listProjectWork",
            "GET",
            "/projects/api/projects/{projectId}/work",
            200,
            null,
            null));
    return List.copyOf(all);
  }

  private static void moves(
      List<Interaction> all, Map<String, EntityStatus> statesByStatus) {
    statesByStatus.forEach(
        (state, status) -> {
          List<EntityStateMachine.Transition> out = EntityStateMachine.transitionsFrom(status);
          if (out.isEmpty()) {
            return;
          }
          all.add(
              new Interaction(
                  state,
                  "setWorkStatus",
                  "POST",
                  "/projects/api/work/{qualifiedId}/status",
                  200,
                  null,
                  null,
                  "{\"target\":\"" + out.get(0).to().name() + "\"}"));
        });
  }

  private static Interaction dispatch(String state, String mode) {
    return dispatch(state, mode, 200);
  }

  private static Interaction dispatch(String state, String mode, int status) {
    return new Interaction(
        state,
        "dispatchWork",
        "POST",
        "/projects/api/work/{qualifiedId}/dispatch",
        status,
        null,
        null,
        "{\"mode\":\"" + mode + "\"}");
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\{([^}]+)}");

  /**
   * A param in a request body: a quoted {@code "{name}"}, so the braces of the JSON itself never
   * read as one.
   */
  private static final Pattern BODY_PARAM = Pattern.compile("\"\\{([A-Za-z][A-Za-z0-9]*)}\"");

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

    Set<String> takesBody = operationsTakingABody();
    for (Interaction interaction : INTERACTIONS) {
      if ((interaction.requestBody() != null) != takesBody.contains(interaction.operationId())) {
        failures.add(
            interaction.operationId()
                + (interaction.requestBody() != null
                    ? " takes no request body, but the recording sends one: record null."
                    : " takes a request body, but the recording sends none."));
      }
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
      // The path is the route alone and the query its own object, as the consumers' pacts send it.
      int at = interaction.path().indexOf('?');
      operation.put("path", at < 0 ? interaction.path() : interaction.path().substring(0, at));
      if (at >= 0) {
        ObjectNode query = operation.putObject("query");
        for (String pair : interaction.path().substring(at + 1).split("&")) {
          int eq = pair.indexOf('=');
          query.put(
              URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
              eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
      }
      if (interaction.requestBody() != null) {
        operation.set("body", JSON.readTree(interaction.requestBody()));
      }
      operation.put("status", interaction.status());
      operation.put("file", file);
      ObjectNode frozen = operation.putObject("frozen");
      frozen.set("ids", strings(recorded.freezer().idPaths()));
      frozen.set("instants", strings(recorded.freezer().instantPaths()));
      frozen.set("strings", strings(recorded.freezer().stringPaths()));
      if (!recorded.freezer().keyPaths().isEmpty()) {
        // Additive, and only where a member name was frozen: every other entry stays as it was.
        frozen.set("keys", strings(recorded.freezer().keyPaths()));
      }
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
      // The consumers are browsers: a session rides with every call, which is what lets a person's
      // door — the scheduling move (qits-887) — answer as it does for them.
      var request =
          given().cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev"));
      if (interaction.requestBody() != null) {
        request =
            request
                .contentType("application/json")
                .body(expand(interaction.requestBody(), params, BODY_PARAM));
      } else {
        // As a browser sends a body-less call: RestAssured would otherwise add a form content type.
        request = request.noContentType();
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
    // An answer with no body (204) is recorded as JSON null: the status is the whole contract.
    JsonNode body = raw.isEmpty() ? JsonNodeFactory.instance.nullNode() : JSON.readTree(raw);
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
    return expand(template, params, TEMPLATE_PARAM);
  }

  private static String expand(String template, Map<String, String> params, Pattern param) {
    Matcher m = param.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = params.get(m.group(1));
      if (value == null) {
        throw new IllegalStateException(
            "Path " + template + " names {" + m.group(1) + "}, which the state does not return");
      }
      String replacement =
          param == BODY_PARAM ? JSON.getNodeFactory().textNode(value).toString() : value;
      m.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** The operationIds whose operation declares a request body, read off the served openapi. */
  private static Set<String> operationsTakingABody() throws IOException {
    JsonNode paths =
        JSON.readTree(
                given()
                    .when()
                    .get("/projects/q/openapi?format=json")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString())
            .path("paths");
    Set<String> ids = new TreeSet<>();
    paths.forEach(
        path ->
            path.forEach(
                operation -> {
                  if (operation.has("operationId") && operation.has("requestBody")) {
                    ids.add(operation.get("operationId").asText());
                  }
                }));
    return ids;
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
