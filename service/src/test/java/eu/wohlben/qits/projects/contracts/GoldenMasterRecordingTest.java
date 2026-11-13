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
import java.util.Locale;
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
 * ProviderStates}), calls the endpoint through REST-assured as {@link
 * ContractBearers#consumerCaller()}, keeps only the list entries the state created, freezes ids,
 * instants and unique tokens ({@link Freezer}) and renders {@code golden-masters/<state-slug>/<operationId>.json}; then it renders
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
   * @param headers request headers the consumer sends because the answer depends on them — a
   *     bearer a door judges. Values are templates expanded from the state's params as a path's
   *     are, and recorded unexpanded as the index's {@code headers}. An interaction that sends one
   *     is a machine's call and carries no person's session
   * @param responseHeaders null for a JSON answer (or none), the common case. Otherwise the answer
   *     is bytes, not JSON (qits-1934): no golden file is written, the index records {@code "file":
   *     null}, and these response headers — exactly the ones named, valued as answered — are what
   *     the recording holds instead, as the index's {@code responseHeaders} and {@code
   *     frozen.responseHeaders}. The shape is qits-githost's, which qits-integrations' {@code
   *     GoldenMasters} reads
   */
  record Interaction(
      String state,
      String operationId,
      String method,
      String path,
      int status,
      String listFilteredTo,
      String sortedBy,
      String requestBody,
      Map<String, String> headers,
      List<String> responseHeaders) {

    /** A JSON answer (or none). */
    Interaction(
        String state,
        String operationId,
        String method,
        String path,
        int status,
        String listFilteredTo,
        String sortedBy,
        String requestBody,
        Map<String, String> headers) {
      this(
          state,
          operationId,
          method,
          path,
          status,
          listFilteredTo,
          sortedBy,
          requestBody,
          headers,
          null);
    }

    /**
     * A read whose answer is bytes, not JSON — a figure's content (qits-1934): recorded body-less,
     * by its status and the named response headers.
     */
    static Interaction bytes(
        String state, String operationId, String path, int status, String... responseHeaders) {
      return new Interaction(
          state,
          operationId,
          "GET",
          path,
          status,
          null,
          null,
          null,
          Map.of(),
          List.of(responseHeaders));
    }

    /** Whether the answer is recorded body-less: bytes, not JSON. */
    boolean bodyless() {
      return responseHeaders != null;
    }

    /** No headers beyond what a browser sends. */
    Interaction(
        String state,
        String operationId,
        String method,
        String path,
        int status,
        String listFilteredTo,
        String sortedBy,
        String requestBody) {
      this(state, operationId, method, path, status, listFilteredTo, sortedBy, requestBody, Map.of());
    }

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

  /**
   * The header a door that judges the caller's token reads: the state's {@code authorization}.
   * Declared above {@link #INTERACTIONS}, which reads it while the class initialises.
   */
  private static final Map<String, String> BEARER = Map.of("Authorization", "{authorization}");

  /** A one-pixel PNG, base64: the bytes a pasted prompt attachment sends. */
  private static final String TINY_PNG =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

  static final List<Interaction> INTERACTIONS =
      withAgentConfiguration(
      withNeeds(
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
          // The development guide (qits-1679): one variant named, and the listing.
          new Interaction(
              ProviderStates.THE_DEVELOPMENT_GUIDE_SHIPS,
              "getDevelopmentGuide",
              "GET",
              "/projects/api/guide?for=workstation",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.THE_DEVELOPMENT_GUIDE_SHIPS,
              "listDevelopmentGuideVariants",
              "GET",
              "/projects/api/guide/variants",
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
          // The project-wide backup button, after a backup-remote sign-in: three of the four
          // repositories have a forge twin, so the answer counts three scheduled.
          new Interaction(
              ProviderStates.A_PROJECT_WITH_REPOSITORIES_IN_COMPONENTS,
              "backupSyncProject",
              "POST",
              "/projects/api/projects/{projectId}/repositories/backup-sync",
              202,
              null,
              null),
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
          // A page's references labelled in one read (qits-1897): two items named in an order of
          // the caller's choosing, and a qualified id naming nothing, which the answer leaves out.
          // Comma-separated in one id: the index records a query as one value per name, so a
          // repeated id would keep only its last.
          new Interaction(
              ProviderStates.A_PROJECT_WITH_WORK_TO_BRIEF,
              "listProjectWorkBriefs",
              "GET",
              "/projects/api/projects/{projectId}/work/briefs"
                  + "?id={ticketQualifiedId},{epicQualifiedId},{unknownQualifiedId}",
              200,
              null,
              null),
          // The CLI's `qits work list --superseded-by`: the two items dropped in favour of one.
          new Interaction(
              ProviderStates.AN_ITEM_THAT_OTHER_ITEMS_NAME_AS_THEIR_SUPERSEDED_BY,
              "listProjectWork",
              "GET",
              "/projects/api/projects/{projectId}/work?supersededBy={entityId}",
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
          // A project's own doors (qits-1934): created under a free slug, renamed, re-asserted,
          // deleted, and its qits-agent-config minted.
          new Interaction(
              ProviderStates.A_PROJECT_SLUG_THAT_NAMES_NO_PROJECT,
              "createProject",
              "POST",
              "/projects/api/projects",
              200,
              null,
              null,
              "{\"name\":\"Contract project\",\"slug\":\"{slug}\","
                  + "\"description\":\"Created by the contract.\","
                  + "\"dns\":{\"domain\":\"example.test.eu\",\"type\":\"A\","
                  + "\"value\":\"203.0.113.9\"}}"),
          new Interaction(
              ProviderStates.A_PROJECT_EXISTS,
              "updateProject",
              "PUT",
              "/projects/api/projects/{projectId}",
              200,
              null,
              null,
              "{\"name\":\"Contract project\",\"description\":\"Renamed by the contract.\"}"),
          new Interaction(
              ProviderStates.A_PROJECT_EXISTS,
              "reconcileProject",
              "POST",
              "/projects/api/projects/{projectId}/reconcile",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_PROJECT_EXISTS,
              "deleteProject",
              "DELETE",
              "/projects/api/projects/{projectId}",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_PROJECT_EXISTS,
              "ensureAgentConfigRepository",
              "POST",
              "/projects/api/projects/{projectId}/agent-config-repository",
              201,
              null,
              null),
          // The project's front desk (qits-767): absent, so read, stopped and deleted as nothing;
          // asked for where its lease can be queued.
          new Interaction(
              ProviderStates.A_PROJECT_EXISTS,
              "getFrontDesk",
              "GET",
              "/projects/api/projects/{projectId}/agent-container",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_PROJECT_EXISTS,
              "stopFrontDesk",
              "POST",
              "/projects/api/projects/{projectId}/agent-container/stop",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_PROJECT_EXISTS,
              "deleteFrontDesk",
              "DELETE",
              "/projects/api/projects/{projectId}/agent-container",
              204,
              null,
              null),
          new Interaction(
              ProviderStates.A_PROJECT_WHOSE_FRONT_DESK_CAN_BE_LEASED,
              "ensureFrontDesk",
              "POST",
              "/projects/api/projects/{projectId}/agent-container/ensure",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_PROJECT_WITH_ONE_REPOSITORY,
              "reconcileProjectRepositories",
              "POST",
              "/projects/api/projects/{projectId}/repositories/reconcile",
              200,
              null,
              null),
          // A repository's own doors (qits-1934). Pull, push and sync answer their process at
          // once; the state waits the git walk out before the next one.
          new Interaction(
              ProviderStates.A_REPOSITORY_EXISTS,
              "renameRepository",
              "PATCH",
              "/projects/api/repositories/{repositoryId}",
              200,
              null,
              null,
              "{\"name\":\"contract-api\"}"),
          new Interaction(
              ProviderStates.A_REPOSITORY_EXISTS,
              "deleteRepository",
              "DELETE",
              "/projects/api/repositories/{repositoryId}",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_EXISTS,
              "getRepositoryActiveProcess",
              "GET",
              "/projects/api/repositories/{repositoryId}/active-process",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_EXISTS,
              "getRepositorySyncStatus",
              "GET",
              "/projects/api/repositories/{repositoryId}/sync-status",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_EXISTS,
              "backupSyncRepository",
              "POST",
              "/projects/api/repositories/{repositoryId}/backup-sync",
              202,
              null,
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_EXISTS,
              "pullRepository",
              "POST",
              "/projects/api/repositories/{repositoryId}/pull",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_EXISTS,
              "pushRepository",
              "POST",
              "/projects/api/repositories/{repositoryId}/push",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_EXISTS,
              "syncRepository",
              "POST",
              "/projects/api/repositories/{repositoryId}/sync",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_WITH_A_SECOND_BRANCH,
              "setRepositoryMainBranch",
              "PUT",
              "/projects/api/repositories/{repositoryId}/main-branch",
              200,
              null,
              null,
              "{\"branch\":\"{branch}\"}"),
          new Interaction(
              ProviderStates.A_REPOSITORY_WITH_A_SECOND_BRANCH,
              "deleteRepositoryBranch",
              "DELETE",
              "/projects/api/repositories/{repositoryId}/branches?branch={branch}",
              200,
              null,
              null),
          // A process stream checks its id before it streams, so an unknown one is a finite 404.
          new Interaction(
              ProviderStates.NO_TECHNICAL_PROCESS_WITH_THE_GIVEN_ID,
              "streamTechnicalProcessEvents",
              "GET",
              "/projects/api/technical-processes/{technicalProcessId}/events",
              404,
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
              ProviderStates.NO_PROJECT_WITH_THE_GIVEN_ID,
              "backupSyncProject",
              "POST",
              "/projects/api/projects/{projectId}/repositories/backup-sync",
              404,
              null,
              null),
          // The subscribe looks the project up first, so an unknown one is a finite 404, not an
          // endless stream (qits-1934).
          new Interaction(
              ProviderStates.NO_PROJECT_WITH_THE_GIVEN_ID,
              "streamProjectEvents",
              "GET",
              "/projects/api/projects/{projectId}/events",
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
              null))))))));

  /**
   * The landing app's release-request pages (epic qits-112): every read the detail page makes, in
   * every detail state ({@link ProviderStates#RELEASE_REQUEST_DETAILS}), and each door it offers,
   * recorded where it answers: decline, withdraw and the source priority on the request awaiting
   * approval; approve where the build still runs (a green one would start a release inside the
   * call); a pipeline rerun per phase; the automation rerun and the waiver where an automation
   * failed; the upstream override where gate 3 holds (qits-914). Plus the project-wide list and its
   * withdraw.
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
    // What the person at the page may decide (epic qits-1038): the Approve and Decline buttons.
    all.add(
        read(awaiting, "getReleaseRequestApprovalEligibility", request + "/approval-eligibility"));
    all.add(
        read(
            ProviderStates.AN_APPROVED_RELEASE_REQUEST,
            "getReleaseRequestApprovalEligibility",
            request + "/approval-eligibility"));
    // Every automation kind is listed, the one that does not apply included — on both answers.
    String notApplicable = ProviderStates.A_RELEASE_REQUEST_WITH_AN_AUTOMATION_THAT_DOES_NOT_APPLY;
    all.add(read(notApplicable, "getReleaseRequest", request));
    all.add(
        read(notApplicable, "listRepositoryReleaseRequests", repository + "/release-requests"));
    // A kind QA checks (DEFERRED, qits-1178) is listed with its sentence and holds nothing.
    all.add(
        read(
            ProviderStates.A_RELEASE_REQUEST_WITH_AN_AUTOMATION_CHECKED_BY_QA,
            "getReleaseRequest",
            request));
    // A person decides, from a terminal: the CLI sends their qits CLI token (the state's
    // authorization), and the door verifies it as a person's (PersonCheck).
    all.add(
        new Interaction(
            awaiting,
            "declineReleaseRequest",
            "POST",
            request + "/decline",
            200,
            null,
            null,
            "{\"mergedSha\":\"{mergedSha}\","
                + "\"note\":\"The README promises an approval policy nobody has signed off yet.\"}",
            BEARER));
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
        new Interaction(
            ProviderStates.A_RELEASE_REQUEST_AWAITING_APPROVAL_WHILE_ITS_BUILD_RUNS,
            "approveReleaseRequest",
            "POST",
            request + "/approve",
            200,
            null,
            null,
            "{\"mergedSha\":\"{mergedSha}\","
                + "\"note\":\"Read the diff: the pin moves to the export release.\"}",
            BEARER));
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
    // A REJECTED request takes an automation's rerun too (qits-1178): that is how a stale output
    // that failed QA is regenerated.
    all.add(
        new Interaction(
            ProviderStates.A_RELEASE_REQUEST_REJECTED_BY_ITS_BUILD,
            "rerunReleaseRequestAutomation",
            "POST",
            request + "/automations/screenshot-baselines/runs",
            202,
            null,
            null,
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
    // Gate 3 (epic qits-897): a request held before QA by an upstream release, and a person
    // lifting it for that one upstream from a terminal (qits-914). The CLI sends their qits CLI
    // token; the door verifies it as a person's (PersonCheck).
    String upstreamHeld = ProviderStates.A_RELEASE_REQUEST_HELD_BY_AN_UPSTREAM_RELEASE;
    all.add(read(upstreamHeld, "getReleaseRequest", request));
    all.add(
        new Interaction(
            upstreamHeld,
            "overrideReleaseRequestUpstream",
            "POST",
            request + "/upstream-overrides",
            200,
            null,
            null,
            "{\"upstreamRequestId\":\"{upstreamRequestId}\","
                + "\"note\":\"The service change is docs only; the suite need not wait.\"}",
            BEARER));
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
    // The git reads of `qits repositories` (qits-1174): the estate's branches, the commits
    // docs/approval holds over its parent, and how main's commits name their subjects.
    all.add(read(awaiting, "listRepositoryBranches", repository + "/branches"));
    all.add(read(awaiting, "listBranchCommits", repository + "/commits?branch=docs/approval"));
    all.add(read(awaiting, "measureCommitSubjects", repository + "/commit-subjects"));
    all.add(
        read(awaiting, "getCommitFileDiff", commits + SAY_WHO_APPROVES + "/diff?path=README.md"));
    String list = ProviderStates.A_PROJECT_WITH_RELEASE_REQUESTS_IN_EVERY_STATE;
    all.add(
        read(list, "listProjectReleaseRequests", "/projects/api/projects/{projectId}/release-requests"));
    // One request through its project, by qualified id (qits-1203): the request awaiting approval.
    all.add(
        read(
            list,
            "getProjectReleaseRequest",
            "/projects/api/projects/{projectId}/release-requests/{requestQualifiedId}"));
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
    // Ideas (qits-1387): every door once, and a refused create.
    all.add(
        read(ProviderStates.A_PROJECT_WITH_IDEAS, "listIdeas", "/projects/api/ideas?project={projectId}"));
    all.add(read(ProviderStates.A_PROJECT_WITH_IDEAS, "getIdea", "/projects/api/ideas/{ideaId}"));
    // The epic the idea inspired (qits-2039): its page shows where it came from, inspiredBy.
    all.add(
        read(ProviderStates.A_PROJECT_WITH_IDEAS, "getWork", "/projects/api/work/{qualifiedId}"));
    // An idea whose enabler is archived (qits-2039): the read names the enabler, archived, and an
    // edit the title rule refuses.
    all.add(
        read(
            ProviderStates.AN_IDEA_ENABLED_BY_ANOTHER_IDEA,
            "getIdea",
            "/projects/api/ideas/{ideaId}"));
    all.add(
        new Interaction(
            ProviderStates.AN_IDEA_ENABLED_BY_ANOTHER_IDEA,
            "patchIdea",
            "PATCH",
            "/projects/api/ideas/{ideaId}",
            400,
            null,
            null,
            "{\"title\":\"Faster builds. Fewer waits. Less cost\"}"));
    all.add(
        new Interaction(
            ProviderStates.A_PROJECT_WITH_NO_WORK,
            "createIdea",
            "POST",
            "/projects/api/ideas",
            201,
            null,
            null,
            "{\"project\":\"{projectId}\",\"title\":\"Faster builds\","
                + "\"description\":\"Cache the dependencies between runs.\"}"));
    all.add(
        new Interaction(
            ProviderStates.A_PROJECT_WITH_IDEAS,
            "createIdea",
            "POST",
            "/projects/api/ideas",
            400,
            null,
            null,
            "{\"project\":\"{projectId}\",\"title\":\"Faster builds. Fewer waits. Less cost\"}"));
    all.add(
        write(
            ProviderStates.A_PROJECT_WITH_IDEAS,
            "patchIdea",
            "PATCH",
            "/projects/api/ideas/{enabledIdeaId}",
            "{\"title\":\"Share the build cache across runners\"}"));
    all.add(
        write(
            ProviderStates.A_PROJECT_WITH_IDEAS,
            "archiveIdea",
            "POST",
            "/projects/api/ideas/{enabledIdeaId}/archive",
            "{\"reason\":\"Folded into the cache epic.\"}"));
    all.add(
        write(
            ProviderStates.A_PROJECT_WITH_IDEAS,
            "unarchiveIdea",
            "POST",
            "/projects/api/ideas/{archivedIdeaId}/unarchive",
            null));
    // The ranking of ideas (qits-1791): a patch writing it outright, and the drop door moving the
    // last of three ideas directly below the ranked first one.
    all.add(
        write(
            ProviderStates.THREE_IDEAS,
            "patchIdea",
            "PATCH",
            "/projects/api/ideas/{ideaId}",
            "{\"priority\":5}"));
    // The idea the patch edits, read in the same state (qits-2039): the edit page's saved view.
    all.add(read(ProviderStates.THREE_IDEAS, "getIdea", "/projects/api/ideas/{ideaId}"));
    all.add(
        write(
            ProviderStates.THREE_IDEAS,
            "reprioritizeIdea",
            "PUT",
            "/projects/api/ideas/{ideaId}/priority",
            "{\"below\":\"{anchor}\"}"));
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
    // A refused create, for the work create page's refusal (qits-2004).
    all.add(
        new Interaction(
            ProviderStates.A_PROJECT_WITH_IDEAS,
            "createWork",
            "POST",
            "/projects/api/work",
            400,
            null,
            null,
            "{\"archetype\":\"TICKET\",\"project\":\"{projectId}\","
                + "\"title\":\"Faster builds\",\"ticketType\":\"IMPROVEMENT\"}"));
    all.add(
        write(
            ProviderStates.A_REPORTED_TICKET,
            "patchWork",
            "PATCH",
            "/projects/api/work/{qualifiedId}",
            "{\"title\":\"Database refuses connections during deploys\"}"));
    // The ranking (qits-753): a patch writing it outright, and the drop door moving the last of three
    // tickets directly below the ranked first one.
    all.add(
        write(
            ProviderStates.THREE_REPORTED_TICKETS,
            "patchWork",
            "PATCH",
            "/projects/api/work/{qualifiedId}",
            "{\"priority\":5}"));
    all.add(
        write(
            ProviderStates.THREE_REPORTED_TICKETS,
            "reprioritizeWork",
            "PUT",
            "/projects/api/work/{qualifiedId}/priority",
            "{\"below\":\"{anchor}\"}"));
    all.add(
        read(
            ProviderStates.THREE_REPORTED_TICKETS,
            "listProjectWork",
            "/projects/api/projects/{projectId}/work"));
    // The tags (qits-634): a scheduled ticket reserved for a person by a patch, past every freeze.
    all.add(
        write(
            ProviderStates.A_READY_FOR_DEV_TICKET,
            "patchWork",
            "PATCH",
            "/projects/api/work/{qualifiedId}",
            "{\"tags\":[\"RESERVED_FOR_HUMAN\"]}"));
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
    // A declared wait (qits-1153): not blocked, past the debounce, and waitingOn names it.
    all.add(
        read(
            ProviderStates.A_TICKET_ITS_AGENT_WAITS_ON_A_DECLARED_WAIT,
            "getWork",
            "/projects/api/work/{qualifiedId}"));
    all.add(
        read(
            ProviderStates.A_TICKET_ITS_AGENT_WAITS_ON_A_DECLARED_WAIT,
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
   * figures, the children, the history, the dispatch
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
            ProviderStates.A_REPORTED_TICKET,
            "getWorkDispatch",
            "/projects/api/work/{qualifiedId}/dispatch"));
    all.add(
        read(
            ProviderStates.A_REPORTED_TICKET,
            "listWorkWorkspaces",
            "/projects/api/work/{qualifiedId}/workspaces"));
    // The board's one read for a whole project (qits-1815): two entries, the idle ticket left out.
    all.add(
        read(
            ProviderStates.A_PROJECT_WHOSE_WORK_HAS_WORKSPACES,
            "listProjectWorkWorkspaces",
            "/projects/api/projects/{projectId}/work/workspaces"));
    all.add(
        read(
            ProviderStates.A_REPORTED_TICKET,
            "getWorkRefinement",
            "/projects/api/work/{qualifiedId}/refinement"));
    // `qits work design` (qits-1174): the room it finds, then its designs. The room's id is a
    // sequence param, frozen to 1.
    String design = ProviderStates.A_TICKET_WITH_A_DESIGN;
    String designs = "/projects/api/refinements/{refinementId}/designs";
    all.add(read(design, "getWorkRefinement", "/projects/api/work/{qualifiedId}/refinement"));
    all.add(read(design, "listRefinementDesigns", designs));
    all.add(read(design, "getRefinementDesign", designs + "/{designId}"));
    all.add(
        new Interaction(
            design,
            "addRefinementDesign",
            "POST",
            designs,
            201,
            null,
            null,
            "{\"title\":\"Export dialog\","
                + "\"html\":\"<!doctype html><html><body><p>Export CSV</p></body></html>\"}"));
    all.add(
        write(
            design,
            "updateRefinementDesign",
            "PUT",
            designs + "/{designId}",
            "{\"title\":\"Export button, primary\","
                + "\"html\":\"<!doctype html><html><body><button>Export CSV</button></body></html>\","
                + "\"version\":0}"));
    // The room's own doors (qits-1383): the open finds the state's room — find-or-create on a
    // REPORTED ticket — then its container door is asked (an alias of ensure on a session room,
    // epic qits-770) and the room discarded. Every interaction sets the state up afresh, so the discard costs the
    // others nothing.
    all.add(
        new Interaction(
            design,
            "startWorkRefinement",
            "POST",
            "/projects/api/work/{qualifiedId}/refinement",
            200,
            null,
            null));
    all.add(
        new Interaction(
            design,
            "ensureRefinementContainer",
            "POST",
            "/projects/api/refinements/{refinementId}/ensure-container",
            200,
            null,
            null));
    // The session doors (epic qits-770): the room's session asked for, then stopped.
    all.add(
        new Interaction(
            design,
            "ensureRefinementSession",
            "POST",
            "/projects/api/refinements/{refinementId}/ensure",
            200,
            null,
            null));
    all.add(
        new Interaction(
            design,
            "stopRefinementSession",
            "POST",
            "/projects/api/refinements/{refinementId}/stop",
            200,
            null,
            null));
    all.add(
        new Interaction(
            design,
            "discardRefinement",
            "POST",
            "/projects/api/refinements/{refinementId}/discard",
            200,
            null,
            null));
    // The room's own reads and its prompt (qits-1934): the room by its id and its project, a
    // design deleted, the draft saved, and the stream's refusal of a room that does not exist.
    String refinement = "/projects/api/refinements/{refinementId}";
    all.add(read(design, "getRefinement", refinement));
    all.add(
        read(design, "listProjectRefinements", "/projects/api/projects/{projectId}/refinements"));
    all.add(
        new Interaction(
            design,
            "deleteRefinementDesign",
            "DELETE",
            designs + "/{designId}",
            204,
            null,
            null));
    all.add(
        write(
            design,
            "saveRefinementPromptDraft",
            "PUT",
            refinement + "/prompt-draft",
            "{\"content\":\"{\\\"type\\\":\\\"doc\\\",\\\"content\\\":[]}\"}"));
    all.add(
        new Interaction(
            design,
            "addRefinementPromptAttachment",
            "POST",
            refinement + "/prompt-attachments",
            201,
            null,
            null,
            "{\"label\":\"Pasted screenshot\",\"source\":\"PASTE\","
                + "\"dataBase64\":\"" + TINY_PNG + "\"}"));
    String draft = ProviderStates.A_REFINEMENT_ROOM_WITH_A_PROMPT_DRAFT;
    all.add(read(draft, "getRefinementPromptDraft", refinement + "/prompt-draft"));
    all.add(
        new Interaction(
            draft,
            "deleteRefinementPromptDraft",
            "DELETE",
            refinement + "/prompt-draft",
            204,
            null,
            null));
    String sketch = ProviderStates.AN_EPIC_WITH_A_SKETCH_TO_INLINE;
    String attachments = refinement + "/prompt-attachments";
    all.add(read(sketch, "listRefinementPromptAttachments", attachments));
    all.add(
        write(
            sketch,
            "replaceRefinementPromptAttachment",
            "PUT",
            attachments + "/{sketchId}",
            "{\"label\":\"Export data flow, revised\",\"source\":\"SKETCH\","
                + "\"dataBase64\":\"" + TINY_PNG + "\"}"));
    all.add(
        new Interaction(
            sketch,
            "deleteRefinementPromptAttachment",
            "DELETE",
            attachments + "/{sketchId}",
            204,
            null,
            null));
    // The sketch's bytes: no golden file, its type and caching are the record.
    all.add(
        Interaction.bytes(
            sketch,
            "getRefinementPromptAttachmentContent",
            attachments + "/{sketchId}/content",
            200,
            "Content-Type",
            "Cache-Control"));
    // The stream reaches the room before it streams, so an unknown one is a finite 404.
    all.add(
        new Interaction(
            ProviderStates.NO_REFINEMENT_WITH_THE_GIVEN_ID,
            "streamRefinementEvents",
            "GET",
            refinement + "/events",
            404,
            null,
            null));
    // `qits work unblock` (qits-1174): the blocked bug, freed.
    all.add(
        write(
            ProviderStates.A_BUG_TICKET_IN_DETAIL,
            "setWorkBlocked",
            "POST",
            "/projects/api/work/{qualifiedId}/blocked",
            "{\"blocked\":false,\"reason\":\"The database team confirmed the new limit.\"}"));
    // And where the open is refused: a REFINED ticket's refine phase has run.
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
            // The body the bootstrap sends (qits-bootstrap-cli ProjectsApi.adoptRepository): the
            // forge twin's url rides along whenever the estate has one, and no archetype — the
            // kind is the repository's own statement, never the caller's default (qits-1834).
            "{\"repositoryId\":\"{repositoryId}\",\"name\":\"{repoName}\","
                + "\"url\":\"https://forge.example.test/contract/contract-events-service.git\"}"));
    all.add(
        write(
            ProviderStates.A_PROJECT_TO_CREATE_A_REPOSITORY_IN,
            "createProjectRepository",
            "POST",
            "/projects/api/projects/{projectId}/repositories",
            // A stated kind: since qits-1834 the name decides nothing, so the recording states one.
            "{\"name\":\"contract-service\",\"component\":\"contract\","
                + "\"archetype\":\"SERVICE\"}"));
    String requests = "/projects/api/repositories/{repositoryId}/release-requests";
    all.add(
        write(
            ProviderStates.A_REPOSITORY_WITH_A_BRANCH_TO_RELEASE,
            "createReleaseRequest",
            "POST",
            requests,
            "{\"branch\":\"feature/export\",\"priority\":\"LOWEST\"}"));
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
            ProviderStates.A_REPOSITORY_WITH_A_TAGGED_RELEASE_WHOSE_REQUEST_WENT_OBSOLETE_MID_PUBLISH,
            "listRepositoryReleaseRequests",
            requests + "?state=all"));
    // The same requests as the landing app's release pages read them (qits-2039): each names the
    // ticket its branch carries, and the obsolete one names what superseded it. The list is read
    // as the app reads it, with no state, so it holds the open successor alone.
    String obsoleted =
        ProviderStates.A_REPOSITORY_WITH_A_TAGGED_RELEASE_WHOSE_REQUEST_WENT_OBSOLETE_MID_PUBLISH;
    all.add(
        read(
            obsoleted, "listProjectReleaseRequests", "/projects/api/projects/{projectId}/release-requests"));
    all.add(read(obsoleted, "getReleaseRequest", requests + "/{requestId}"));
    all.add(
        read(
            obsoleted,
            "getProjectReleaseRequest",
            "/projects/api/projects/{projectId}/release-requests/{requestQualifiedId}"));
    // The soft feature freeze (epic qits-1580): both lists, the frozen request read by id and the
    // queued one read through its project by qualified id.
    String frozen = ProviderStates.A_FROZEN_RELEASE_REQUEST_WITH_A_QUEUED_NEXT_REQUEST;
    all.add(read(frozen, "listRepositoryReleaseRequests", requests));
    all.add(
        read(frozen, "listProjectReleaseRequests", "/projects/api/projects/{projectId}/release-requests"));
    all.add(read(frozen, "getReleaseRequest", requests + "/{requestId}"));
    all.add(
        read(
            frozen,
            "getProjectReleaseRequest",
            "/projects/api/projects/{projectId}/release-requests/{queuedRequestQualifiedId}"));
    // qits-1665: one source of the frozen request moved into the request queued behind it — what
    // qits release-request move and the landing app's "Move to next request" call.
    all.add(
        write(
            frozen,
            "moveReleaseRequestSource",
            "POST",
            requests + "/{requestId}/sources/move",
            "{\"branch\":\"ticket/contract-12\",\"reason\":\"It conflicts with"
                + " ticket/contract-11.\"}"));
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
    // Keyed by the entity id, as qits-workspaces sends it; 204 and no body. The frame names a
    // declared wait (qits-1153), the fullest shape the relay sends.
    all.add(
        new Interaction(
            ProviderStates.A_TICKET_WITH_A_DISPATCHED_AGENT,
            "reportWorkAgentWaiting",
            "POST",
            "/projects/api/work/{ticketId}/agent-waiting",
            204,
            null,
            null,
            "{\"waiting\":true,\"cause\":\"Wait\",\"sessionId\":\"contract-session\","
                + "\"at\":1234,\"waitingOn\":\"release-request 42; ci-run 7\"}"));
    // A real run, as the orchestrator's schedule makes it; the five pin sources pin nothing.
    all.add(
        write(
            ProviderStates.REPOSITORIES_WITH_DECOMMISSIONABLE_TAGS,
            "collectTags",
            "POST",
            "/projects/api/gc/tags",
            "{\"dryRun\":false,\"pins\":{\"deployments\":{\"pins\":[]},\"ciDaemon\":{},"
                + "\"dependencies\":{\"pins\":[]},\"workspaceLaunches\":{\"pins\":[]},"
                + "\"projectLaunches\":{\"pins\":[]}}}"));
    // The front desk's two doors (qits-1572), as qits-platform-access-cli calls them: a prompt the
    // desk's daemon delivers at once, and the last answer it gave, with its text. Both answers are
    // the fake daemon's, passed through the real tunnel verbatim.
    String frontDesk = "/projects/api/projects/{projectId}/front-desk";
    all.add(
        write(
            ProviderStates.A_PROJECT_WHOSE_FRONT_DESK_IS_RUNNING,
            "promptFrontDesk",
            "POST",
            frontDesk + "/prompt",
            "{\"text\":\"What is ready for dev?\"}"));
    all.add(
        read(
            ProviderStates.A_PROJECT_WHOSE_FRONT_DESK_IS_RUNNING,
            "getFrontDeskLastAnswer",
            frontDesk + "/last-answer"));
    // The named agents' doors (qits-2154), as qits-platform-access-monorepo's `qits projects agents
    // list|prompt|answer` calls them: the listing (the desk from its own last answer, the UI builder
    // as the daemon lists it), a prompt the UI builder's session takes at once, and its last answer.
    // Every daemon answer is the fake's, through the real tunnel.
    String agents = "/projects/api/projects/{projectId}/agents";
    all.add(read(ProviderStates.A_PROJECT_WHOSE_NAMED_AGENT_IS_RUNNING, "listAgents", agents));
    all.add(
        write(
            ProviderStates.A_PROJECT_WHOSE_NAMED_AGENT_IS_RUNNING,
            "promptAgent",
            "POST",
            agents + "/{name}/prompt",
            "{\"text\":\"Pitch a calmer header.\"}"));
    all.add(
        read(
            ProviderStates.A_PROJECT_WHOSE_NAMED_AGENT_IS_RUNNING,
            "getAgentLastAnswer",
            agents + "/{name}/last-answer"));
    // The desk trigger read door (qits-1612, epic qits-1604), as qits-platform-access-cli's `qits
    // projects desk triggers` and the landing app's desk page call it: one delivered window, its
    // message and its two lines.
    all.add(
        read(
            ProviderStates.A_PROJECT_WHOSE_DESK_RECEIVED_A_TRIGGER_MESSAGE,
            "listDeskTriggerMessages",
            "/projects/api/projects/{projectId}/desk-triggers"));
    // A comment that addressed the desk (qits-2039): its line names the comment.
    all.add(
        read(
            ProviderStates.A_PROJECT_WHOSE_DESK_WAS_ADDRESSED_IN_A_COMMENT,
            "listDeskTriggerMessages",
            "/projects/api/projects/{projectId}/desk-triggers"));
    // An open issue (qits-2039): the project's work lists it, and its page reads it.
    all.add(
        read(
            ProviderStates.A_PROJECT_WITH_AN_OPEN_ISSUE,
            "listProjectWork",
            "/projects/api/projects/{projectId}/work"));
    all.add(
        read(
            ProviderStates.A_PROJECT_WITH_AN_OPEN_ISSUE,
            "getWork",
            "/projects/api/work/{qualifiedId}"));
    // The branch-delivery read door (qits-1720), as qits-platform-access-cli reads where a branch
    // stands: by the branch's name alone, which is all an agent waiting on its branch holds.
    all.add(
        read(
            ProviderStates.BRANCH_TICKET_QITS_9001_IS_RELEASED_IN_QITS_DEMO_SERVICE,
            "getBranchDeliveries",
            "/projects/api/branch-deliveries?branch=ticket/qits-9001"));
    // The wrapper-closings read door (qits-1873): every VERIFIED ticket of a project with where its
    // wrapper closing stands, one ticket per verdict.
    all.add(
        read(
            ProviderStates.A_PROJECT_WITH_VERIFIED_TICKETS_IN_EVERY_WRAPPER_VERDICT,
            "listWrapperClosings",
            "/projects/api/projects/{projectId}/wrapper-closings"));
    // The same tickets as the work a consumer's Acceptance view lists, and the IN_FLIGHT one's page.
    all.add(
        read(
            ProviderStates.A_PROJECT_WITH_VERIFIED_TICKETS_IN_EVERY_WRAPPER_VERDICT,
            "listProjectWork",
            "/projects/api/projects/{projectId}/work"));
    all.add(
        read(
            ProviderStates.A_PROJECT_WITH_VERIFIED_TICKETS_IN_EVERY_WRAPPER_VERDICT,
            "getWork",
            "/projects/api/work/{qualifiedId}"));
    // The backup twin reset (qits-1885), as `qits repositories backup-reset` sends it: a person's
    // CLI token, since the door is a person's (or the project's desk's). The twin's main is a
    // forge's unrelated seed, so the answer is an UNRELATED_RESET between two seeded shas.
    all.add(
        new Interaction(
            ProviderStates.A_REPOSITORY_WHOSE_BACKUP_TWIN_HOLDS_UNRELATED_HISTORY,
            "backupResetRepository",
            "POST",
            "/projects/api/repositories/{repositoryId}/backup-reset",
            200,
            null,
            null,
            "{\"reason\":\"The forge seeded the twin with a README commit of its own.\"}",
            BEARER));
    // The resets `qits repositories backup-reset` made, as the repository's history lists them.
    all.add(
        read(
            ProviderStates.A_REPOSITORY_WHOSE_BACKUP_TWIN_WAS_RESET,
            "listRepositoryBackupResets",
            "/projects/api/repositories/{repositoryId}/backup-resets"));
    // `qits repositories archive` and `unarchive` (qits-1784), where main already says what is
    // asked: 200 unchanged, nothing opened. A 202 would open a release request inside the call.
    all.add(
        write(
            ProviderStates.A_REPOSITORY_WHOSE_MAIN_ALREADY_SAYS_IT_IS_ARCHIVED,
            "archiveRepository",
            "POST",
            "/projects/api/repositories/{repositoryId}/archive",
            null));
    all.add(
        write(
            ProviderStates.A_REPOSITORY_EXISTS,
            "unarchiveRepository",
            "POST",
            "/projects/api/repositories/{repositoryId}/unarchive",
            null));
    return List.copyOf(all);
  }

  /**
   * The estate-wide agent configuration (qits-1934): the capability catalogue and a container's
   * report into it, the document a container is created with, the surfaces with one saved and one
   * revision trail, and the external MCP catalog and its credentials, every write on each.
   */
  private static List<Interaction> withAgentConfiguration(List<Interaction> base) {
    List<Interaction> all = new ArrayList<>(base);
    String capabilities = ProviderStates.A_CONTAINER_REPORTED_ITS_HARNESS_CAPABILITIES;
    all.add(
        write(
            capabilities,
            "reportAgentCapabilities",
            "PUT",
            "/projects/api/agent-capabilities",
            ProviderStates.CAPABILITY_REPORT));
    all.add(read(capabilities, "getAgentCapabilities", "/projects/api/agent-capabilities"));
    String surfaces = ProviderStates.EVERY_AGENT_SURFACE_AT_ITS_SHIPPED_DEFAULT;
    all.add(read(surfaces, "getAgentConfiguration", "/projects/api/agent-configuration"));
    all.add(read(surfaces, "listAgentSurfaces", "/projects/api/agent-surfaces"));
    all.add(read(surfaces, "getAgentSurface", "/projects/api/agent-surfaces/{surface}"));
    all.add(
        write(
            surfaces,
            "updateAgentSurface",
            "PUT",
            "/projects/api/agent-surfaces/{surface}",
            "{\"harness\":\"CLAUDE\",\"model\":\"opus\",\"effort\":\"high\","
                + "\"remoteControl\":true,\"permissionMode\":\"SKIP_PERMISSIONS\","
                + "\"activityTracking\":true,"
                + "\"systemPrompt\":\"You are the contract's work desk.\","
                + "\"initialPrompt\":\"\","
                + "\"mcpServers\":[{\"server\":\"qits\",\"narrowProject\":true,"
                + "\"narrowRepository\":false,\"narrowWorkspace\":false,\"readOnly\":false}],"
                + "\"externalMcpServers\":[]}"));
    all.add(
        read(
            ProviderStates.AN_AGENT_SURFACE_WITH_A_SAVED_REVISION,
            "listAgentSurfaceRevisions",
            "/projects/api/agent-surfaces/{surface}/revisions"));
    String mcp = ProviderStates.AN_EXTERNAL_MCP_SERVER_IN_THE_CATALOG;
    all.add(read(mcp, "listAgentMcpCatalog", "/projects/api/agent-mcp-catalog"));
    all.add(read(mcp, "getAgentMcpCatalogEntry", "/projects/api/agent-mcp-catalog/{catalogKey}"));
    all.add(
        write(
            mcp,
            "saveAgentMcpCatalogEntry",
            "PUT",
            "/projects/api/agent-mcp-catalog/{catalogKey}",
            "{\"displayName\":\"Weather service\","
                + "\"url\":\"https://mcp.weather.example.test/mcp\","
                + "\"headerName\":\"Authorization\",\"credentialKey\":\"env.WEATHER_TOKEN\","
                + "\"allowedTools\":[\"forecast\",\"alerts\"]}"));
    all.add(
        new Interaction(
            mcp,
            "deleteAgentMcpCatalogEntry",
            "DELETE",
            "/projects/api/agent-mcp-catalog/{catalogKey}",
            204,
            null,
            null));
    all.add(
        read(mcp, "listAgentMcpCredentials", "/projects/api/agent-mcp-catalog/credentials"));
    all.add(
        write(
            mcp,
            "setAgentMcpCredential",
            "PUT",
            "/projects/api/agent-mcp-catalog/credentials/{credentialKey}",
            "{\"value\":\"Bearer contract-placeholder-rotated\"}"));
    all.add(
        new Interaction(
            mcp,
            "deleteAgentMcpCredential",
            "DELETE",
            "/projects/api/agent-mcp-catalog/credentials/{credentialKey}",
            204,
            null,
            null));
    return List.copyOf(all);
  }

  /**
   * The work reads that carry {@code needs} and {@code unmetNeeds} (qits-1903), item to item: the
   * three reads of a ticket that needs another.
   */
  private static List<Interaction> withNeeds(List<Interaction> base) {
    List<Interaction> all = new ArrayList<>(base);
    String needs = ProviderStates.A_TICKET_THAT_NEEDS_ANOTHER;
    all.add(read(needs, "getWork", "/projects/api/work/{qualifiedId}"));
    all.add(read(needs, "listWorkChildren", "/projects/api/work/{featureQualifiedId}/children"));
    all.add(read(needs, "listProjectWork", "/projects/api/projects/{projectId}/work"));
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
   * the tickets add their dossier — the epic its figures too.
   */
  private static List<Interaction> withWorkDetail(List<Interaction> base) {
    List<Interaction> all = new ArrayList<>(base);
    ProviderStates.IN_DETAIL.forEach(
        (state, focus) -> {
          all.add(read(state, "getWork", "/projects/api/work/{qualifiedId}"));
          all.add(read(state, "listWorkComments", "/projects/api/work/{qualifiedId}/comments"));
          all.add(read(state, "listProjectWork", "/projects/api/projects/{projectId}/work"));
        });
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
    // The figure's bytes: no golden file, the sandboxing headers are the contract (qits-1934).
    all.add(
        Interaction.bytes(
            ProviderStates.AN_EPIC_IN_DETAIL,
            "getWorkDossierAssetContent",
            "/projects/api/work/{qualifiedId}/dossier-assets/{figureAssetId}/content",
            200,
            "Content-Type",
            "Content-Security-Policy",
            "X-Content-Type-Options",
            "Cache-Control"));
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
    moves(all, ProviderStates.TICKET_IN_STATUS, true);
    moves(all, ProviderStates.EPIC_IN_STATUS, false);
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
    // qits-2095: the landing work item page's action screenshots render each status from its own
    // state, so every status state records the item's reads — the epics their figures too.
    statusReads(all, ProviderStates.TICKET_IN_STATUS, false);
    statusReads(all, ProviderStates.EPIC_IN_STATUS, true);
    // The page of a feature under way: the epic's READY_FOR_DEV "Open feature" (no dossier).
    all.add(
        read(
            ProviderStates.AN_EPIC_WITH_FEATURES_AND_TASKS,
            "getWork",
            "/projects/api/work/{qualifiedId}"));
    all.add(
        read(
            ProviderStates.AN_EPIC_WITH_FEATURES_AND_TASKS,
            "listWorkComments",
            "/projects/api/work/{qualifiedId}/comments"));
    return List.copyOf(all);
  }

  /** The work item page's reads of each state's item: entity, thread, project list, dossier. */
  private static void statusReads(
      List<Interaction> all, Map<String, EntityStatus> statesByStatus, boolean figures) {
    statesByStatus
        .keySet()
        .forEach(
            state -> {
              all.add(read(state, "getWork", "/projects/api/work/{qualifiedId}"));
              all.add(read(state, "listWorkComments", "/projects/api/work/{qualifiedId}/comments"));
              all.add(read(state, "listProjectWork", "/projects/api/projects/{projectId}/work"));
              all.add(read(state, "listWorkDossier", "/projects/api/work/{qualifiedId}/dossier"));
              if (figures) {
                all.add(
                    read(
                        state,
                        "listWorkDossierAssets",
                        "/projects/api/work/{qualifiedId}/dossier-assets"));
              }
            });
  }

  /**
   * Each state's first legal move through the status door. A ticket's VERIFIED → DONE is recorded
   * as the 409 it now answers (qits-150): the platform sets a ticket DONE when its wrapper release
   * request is finalized, and no caller asks for it.
   */
  private static void moves(
      List<Interaction> all, Map<String, EntityStatus> statesByStatus, boolean tickets) {
    statesByStatus.forEach(
        (state, status) -> {
          List<EntityStateMachine.Transition> out = EntityStateMachine.transitionsFrom(status);
          if (out.isEmpty()) {
            return;
          }
          EntityStatus target = out.get(0).to();
          all.add(
              new Interaction(
                  state,
                  "setWorkStatus",
                  "POST",
                  "/projects/api/work/{qualifiedId}/status",
                  tickets && target == EntityStatus.DONE ? 409 : 200,
                  null,
                  null,
                  "{\"target\":\"" + target.name() + "\"}"));
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

  /**
   * The committed index's frozen params, slug → param → frozen value: what pins a param's frozen id
   * number ({@link Freezer#seed(Map, Map)}), read once before recording.
   */
  private Map<String, Map<String, String>> committedParams = Map.of();

  @Test
  void goldenMastersMatchTheProvider() throws IOException {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();

    // slug -> recorded state, sorted by slug; operations sorted by operationId below
    Map<String, ObjectNode> indexStates = new TreeMap<>();
    Map<String, Map<String, ObjectNode>> indexOperations = new TreeMap<>();

    committedParams = committedParams(dir.resolve("index.json"));
    Map<String, Boolean> takesBody = operationsTakingABody();
    for (Interaction interaction : INTERACTIONS) {
      // By method and path, not operationId: a third of the operations declare none (qits-1934).
      String template = operation(takesBody.keySet(), interaction.method(), interaction.path());
      if (template == null) {
        failures.add(
            interaction.method()
                + " "
                + interaction.path()
                + " ("
                + interaction.operationId()
                + ") matches no operation of the openapi.");
      } else if ((interaction.requestBody() != null) != takesBody.get(template)) {
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
      if (!interaction.headers().isEmpty()) {
        ObjectNode headers = operation.putObject("headers");
        new TreeMap<>(interaction.headers()).forEach(headers::put);
      }
      if (interaction.requestBody() != null) {
        operation.set("body", JSON.readTree(interaction.requestBody()));
      }
      operation.put("status", interaction.status());
      if (interaction.bodyless()) {
        // No file for an answer that is bytes, not JSON (qits-1934): its headers are the record.
        operation.set("responseHeaders", recorded.headers());
        operation.putNull("file");
      } else {
        operation.put("file", file);
      }
      ObjectNode frozen = operation.putObject("frozen");
      frozen.set("ids", strings(recorded.freezer().idPaths()));
      frozen.set("instants", strings(recorded.freezer().instantPaths()));
      frozen.set("strings", strings(recorded.freezer().stringPaths()));
      if (!recorded.freezer().numberPaths().isEmpty()) {
        // Additive, and only where a sequence number was frozen, as keys below.
        frozen.set("numbers", strings(recorded.freezer().numberPaths()));
      }
      if (!recorded.freezer().keyPaths().isEmpty()) {
        // Additive, and only where a member name was frozen: every other entry stays as it was.
        frozen.set("keys", strings(recorded.freezer().keyPaths()));
      }
      if (interaction.bodyless()) {
        frozen.set("responseHeaders", strings(interaction.responseHeaders()));
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

      if (!interaction.bodyless()) {
        written.add(file);
        check(dir.resolve(file), GoldenJson.render(recorded.body()), update, failures);
      }
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

  /**
   * One interaction's frozen answer, its frozen params and what was frozen where. A body-less
   * recording's body is null and its named response headers are {@code headers}; a JSON one's
   * {@code headers} are empty.
   */
  record Recorded(JsonNode body, ObjectNode headers, ObjectNode params, Freezer freezer) {}

  private Recorded record(Interaction interaction) throws IOException {
    ProviderStates.Setup setup = states.setUp(interaction.state());
    Map<String, String> params = setup.params();

    Response response;
    try {
      // The consumers are browsers: a session rides with every call, which is what lets a person's
      // door — the scheduling move (qits-887) — answer as it does for them. A machine's call — one
      // that sends a header of its own — carries its bearer instead, and no session.
      var request =
          interaction.headers().isEmpty()
              ? given().cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev"))
              : given();
      for (Map.Entry<String, String> header : interaction.headers().entrySet()) {
        request = request.header(header.getKey(), expand(header.getValue(), params));
      }
      if (!interaction.headers().containsKey("Authorization")) {
        // A recorded call without a credential of its own is the consumer's caller, named rather
        // than left to a forward-auth dev user (epic qits-543): sent, not recorded, so the golden
        // master stays the consumer's shape.
        request = request.header("Authorization", ContractBearers.consumerCaller());
      }
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
    ObjectNode headers = JsonNodeFactory.instance.objectNode();
    JsonNode body = null;
    if (interaction.bodyless()) {
      // Bytes, not JSON (qits-1934): never parsed, and only the named headers are recorded.
      for (String name : interaction.responseHeaders()) {
        String value = response.header(name);
        if (value == null) {
          throw new AssertionError(
              interaction.operationId()
                  + " in state '"
                  + interaction.state()
                  + "' carries no "
                  + name
                  + " header");
        }
        headers.put(name, value);
      }
    } else {
      // An answer with no body (204) is recorded as JSON null: the status is the whole contract.
      body = raw.isEmpty() ? JsonNodeFactory.instance.nullNode() : JSON.readTree(raw);
      body = recordable(body, interaction, params.values(), setup.uniqueTokens());
    }

    Freezer freezer =
        new Freezer()
            .seed(
                params,
                committedParams.getOrDefault(ProviderStates.slug(interaction.state()), Map.of()))
            .uniqueTokens(setup.uniqueTokens())
            .sequences(setup.sequenceParams().stream().map(params::get).toList());
    ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
    params.forEach((k, v) -> frozenParams.put(k, freezer.freezeParam(v)));
    return new Recorded(body == null ? null : freezer.freeze(body), headers, frozenParams, freezer);
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

  /**
   * Every operation of the served openapi, as {@code METHOD path-template}, to whether it declares
   * a request body. Keyed by method and path rather than operationId (qits-1934): 47 of the
   * operations declare no operationId, and a write to one of them would otherwise read as taking
   * no body.
   */
  private static Map<String, Boolean> operationsTakingABody() throws IOException {
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
    Map<String, Boolean> operations = new TreeMap<>();
    paths
        .fields()
        .forEachRemaining(
            path ->
                path.getValue()
                    .fields()
                    .forEachRemaining(
                        method -> {
                          if (method.getValue().isObject()
                              && HTTP_METHODS.contains(method.getKey())) {
                            operations.put(
                                method.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey(),
                                method.getValue().has("requestBody"));
                          }
                        }));
    return operations;
  }

  /** The members of an openapi path item that are operations; the rest are shared parameters. */
  private static final Set<String> HTTP_METHODS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  /**
   * The operation an interaction calls, out of {@code METHOD path-template} keys: the same method,
   * and a template of as many segments whose literals equal the interaction's path (its query
   * dropped) while each {@code {x}} stands for exactly one segment, whatever it holds. Where
   * several match, the one with the most literal segments — {@code /work/{qualifiedId}/dossier} over
   * {@code /work/{qualifiedId}/{page}}. Null when none matches. Package-private for the machinery
   * test (qits-1934).
   */
  static String operation(Collection<String> operations, String method, String path) {
    int at = path.indexOf('?');
    String[] segments = (at < 0 ? path : path.substring(0, at)).split("/", -1);
    String best = null;
    int bestLiterals = -1;
    for (String operation : operations) {
      int space = operation.indexOf(' ');
      if (!operation.substring(0, space).equals(method)) {
        continue;
      }
      String[] template = operation.substring(space + 1).split("/", -1);
      if (template.length != segments.length) {
        continue;
      }
      int literals = 0;
      boolean matches = true;
      for (int i = 0; i < template.length && matches; i++) {
        if (TEMPLATE_SEGMENT.matcher(template[i]).matches()) {
          matches = !segments[i].isEmpty();
        } else {
          matches = template[i].equals(segments[i]);
          literals++;
        }
      }
      if (matches && literals > bestLiterals) {
        best = operation;
        bestLiterals = literals;
      }
    }
    return best;
  }

  /** A path segment that is one param of an openapi template. */
  private static final Pattern TEMPLATE_SEGMENT = Pattern.compile("\\{[^/{}]+}");

  private static ArrayNode strings(List<String> values) {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    values.forEach(out::add);
    return out;
  }

  /**
   * slug → frozen params of every state in the committed index ({@code states[].slug}, {@code
   * states[].params}); empty when there is no index yet.
   */
  static Map<String, Map<String, String>> committedParams(Path index) throws IOException {
    if (!Files.isRegularFile(index)) {
      return Map.of();
    }
    Map<String, Map<String, String>> out = new TreeMap<>();
    for (JsonNode state : JSON.readTree(index.toFile()).path("states")) {
      Map<String, String> params = new TreeMap<>();
      state.path("params").fields().forEachRemaining(p -> params.put(p.getKey(), p.getValue().asText()));
      out.put(state.path("slug").asText(), params);
    }
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
