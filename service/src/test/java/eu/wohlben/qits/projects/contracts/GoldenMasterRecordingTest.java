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
   *     body} so a consumer's pact sends the same; null for a read. A {@code {param}} in it — a
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

  /** The generic entity operations whose {@code /work} twin is recorded beside them. */
  private static final Map<String, String> WORK_TWINS =
      Map.ofEntries(
          Map.entry("getEntity", "getWork"),
          Map.entry("listEntityComments", "listWorkComments"),
          Map.entry("listProjectEntities", "listProjectWork"),
          Map.entry("listArchetypes", "listWorkArchetypes"),
          Map.entry("moveEntityStatus", "setWorkStatus"),
          // The work sub-resources (qits-970): a campaign's members, both dossier halves, the
          // epic's figures and the dispatch press.
          Map.entry("getCampaign", "listWorkMembers"),
          Map.entry("listEpicDossierPages", "listWorkDossier"),
          Map.entry("listTicketDossierPages", "listWorkDossier"),
          Map.entry("listEpicDossierAssets", "listWorkDossierAssets"),
          Map.entry("dispatchEntity", "dispatchWork"));

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

  // Declared before INTERACTIONS, whose initializer reads them through workPath.

  /** {@code GET /campaigns/{<name>Id}}, the campaign's read the members twin replaces. */
  private static final Pattern CAMPAIGN_READ =
      Pattern.compile("/projects/api/campaigns/\\{([A-Za-z]+)Id}");

  /** A per-archetype dossier half or the epic's figures, under the entity's id param. */
  private static final Pattern DOSSIER =
      Pattern.compile("/projects/api/(?:epics|tickets)/\\{[A-Za-z]+}/(dossier|dossier-assets)");

  static final List<Interaction> INTERACTIONS =
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
              ProviderStates.AN_EPIC_WITH_TASKS_IN_EVERY_STATUS,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.AN_EPIC_WITH_A_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.AN_EPIC_WITH_A_VERIFIED_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.AN_IMPLEMENTING_EPIC_WITH_FEATURES_IN_MIXED_STATUSES,
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
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_A_DONE_A_VERIFIED_AND_AN_IMPLEMENTING_EPIC,
              "listProjectEntities",
              "GET",
              "/projects/api/projects/{projectId}/entities",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_A_DONE_A_VERIFIED_AND_AN_IMPLEMENTING_EPIC,
              "getCampaign",
              "GET",
              "/projects/api/campaigns/{campaignId}",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_A_DONE_A_VERIFIED_AND_AN_IMPLEMENTING_EPIC,
              "getEntity",
              "GET",
              "/projects/api/entities/{qualifiedId}",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_CAMPAIGN_WITH_A_DONE_A_VERIFIED_AND_AN_IMPLEMENTING_EPIC,
              "listEntityComments",
              "GET",
              "/projects/api/entities/{qualifiedId}/comments",
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
              null))));

  /**
   * The work family ({@code /projects/api/work}, qits-969, epic qits-965): every generic entity read
   * and move the table already records gets its {@code /work} twin in the same state — {@link
   * #WORK_TWINS} — addressed by the qualified id wherever the original was addressed by a ticket's
   * or an epic's UUID, so a consumer moving onto {@code /work} finds every state it used; and the
   * family's own writes and thread operations are recorded once each.
   */
  private static List<Interaction> withWorkFamily(List<Interaction> base) {
    List<Interaction> all = new ArrayList<>(base);
    for (Interaction original : base) {
      String twin = WORK_TWINS.get(original.operationId());
      if (twin != null) {
        all.add(
            new Interaction(
                original.state(),
                twin,
                original.method(),
                workPath(original.path()),
                original.status(),
                original.listFilteredTo(),
                original.sortedBy(),
                original.requestBody()));
      }
    }
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
   * read, the workspaces, the refinement room and the delete. The twins of the routes they replace are {@link
   * #WORK_TWINS}'. {@code getWorkDossierAssetContent} serves bytes and has no golden master, as
   * {@code getDossierAssetContent} has none.
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
   * An entity route's {@code /work} address: the archetype registry and a project's listing move
   * under {@code work}, and an entity addressed by a ticket's or an epic's UUID is addressed by its
   * qualified id — every state recording one returns it as {@code qualifiedId}.
   */
  private static String workPath(String path) {
    // A campaign's read becomes its members, by the campaign's qualified id ({campaignId} →
    // {campaignQualifiedId}); a dossier half and the epic's figures move under the entity the
    // state focuses on, which is its {qualifiedId}.
    Matcher campaign = CAMPAIGN_READ.matcher(path);
    if (campaign.matches()) {
      return "/projects/api/work/{" + campaign.group(1) + "QualifiedId}/members";
    }
    path = DOSSIER.matcher(path).replaceFirst("/projects/api/work/{qualifiedId}/$1");
    return path.replace("/projects/api/projects/{projectId}/entities", "/projects/api/projects/{projectId}/work")
        .replace("/projects/api/entities/{ticketId}", "/projects/api/work/{qualifiedId}")
        .replace("/projects/api/entities/{epicId}", "/projects/api/work/{qualifiedId}")
        .replace("/projects/api/entities/", "/projects/api/work/");
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
          all.add(read(state, "getEntity", "/projects/api/entities/{qualifiedId}"));
          all.add(
              read(state, "listEntityComments", "/projects/api/entities/{qualifiedId}/comments"));
          all.add(
              read(state, "listProjectEntities", "/projects/api/projects/{projectId}/entities"));
        });
    for (String member :
        List.of(
            ProviderStates.AN_EPIC_IN_DETAIL,
            ProviderStates.A_BUG_TICKET_IN_DETAIL,
            ProviderStates.AN_IMPROVEMENT_TICKET_IN_DETAIL,
            ProviderStates.A_CAMPAIGN_IN_DETAIL)) {
      all.add(read(member, "getCampaign", "/projects/api/campaigns/{campaignId}"));
    }
    all.add(
        read(
            ProviderStates.AN_EPIC_IN_DETAIL,
            "listEpicDossierPages",
            "/projects/api/epics/{epicId}/dossier"));
    all.add(
        read(
            ProviderStates.AN_EPIC_IN_DETAIL,
            "listEpicDossierAssets",
            "/projects/api/epics/{epicId}/dossier-assets"));
    all.add(
        read(
            ProviderStates.A_BUG_TICKET_IN_DETAIL,
            "listTicketDossierPages",
            "/projects/api/tickets/{bugTicketId}/dossier"));
    all.add(
        read(
            ProviderStates.AN_IMPROVEMENT_TICKET_IN_DETAIL,
            "listTicketDossierPages",
            "/projects/api/tickets/{improvementTicketId}/dossier"));
    all.add(
        read(
            ProviderStates.A_MAINTENANCE_TICKET_IN_DETAIL,
            "listTicketDossierPages",
            "/projects/api/tickets/{maintenanceTicketId}/dossier"));
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
            "listArchetypes",
            "GET",
            "/projects/api/entities/archetypes",
            200,
            null,
            null));
    moves(all, ProviderStates.TICKET_IN_STATUS, "ticketId");
    moves(all, ProviderStates.EPIC_IN_STATUS, "epicId");
    all.add(dispatch(ProviderStates.AN_IMPLEMENTED_TICKET, "ticketId", "PHASE"));
    // qits-887: implement runs from READY_FOR_DEV; a REFINED entity waits for a person to schedule
    // it, and its press is the 409 that says so.
    all.add(dispatch(ProviderStates.A_REFINED_TICKET, "ticketId", "FLOW", 409));
    all.add(dispatch(ProviderStates.A_READY_FOR_DEV_TICKET, "ticketId", "FLOW"));
    all.add(dispatch(ProviderStates.A_REPORTED_EPIC, "epicId", "PHASE"));
    all.add(dispatch(ProviderStates.A_REFINED_EPIC, "epicId", "FLOW", 409));
    all.add(dispatch(ProviderStates.A_READY_FOR_DEV_EPIC, "epicId", "FLOW"));
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
    return dispatch(state, idParam, mode, 200);
  }

  private static Interaction dispatch(String state, String idParam, String mode, int status) {
    return new Interaction(
        state,
        "dispatchEntity",
        "POST",
        "/projects/api/entities/{" + idParam + "}/dispatch",
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
