package eu.wohlben.qits.projects.contracts;

import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.api.TestCriteria;
import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.BackupOutcome;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ProjectDnsRecord;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import eu.wohlben.qits.projects.entity.CommitBuildStatus;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequest.State;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>The provider states qits-projects answers for</b>: a state name → a setup that seeds through
 * the service layer, with fresh ids, and returns the state's parameters.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before recording each operation
 * that names it, and a pact verification test's {@code @State} methods delegate here and return
 * {@link Setup#params()}. It is a bean so either can simply {@code @Inject} it.
 *
 * <p><b>Every state is parallel-safe and assumes nothing about the database.</b> Ids are minted
 * fresh by the services themselves; the one value that must be unique service-wide and is not an
 * id — the project slug — carries a random token, which the setup reports in {@link
 * Setup#uniqueTokens()} so a recorder can freeze it ({@code frozen.strings} in the index). Nothing
 * here relies on {@code PlatformStateReset} having truncated anything: the recorder filters every
 * list down to the state's own entities.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_PROJECT_EXISTS = "a project exists";
  public static final String TWO_PROJECTS_EXIST = "two projects exist";
  public static final String NO_PROJECTS_EXIST = "no projects exist";
  public static final String A_PROJECT_WITH_ONE_REPOSITORY = "a project with one repository";
  public static final String A_PROJECT_WITH_3_REPOSITORIES = "a project with 3 repositories";
  public static final String A_REPOSITORY_EXISTS = "a repository exists";
  public static final String A_PROJECT_WITH_REFINED_WORK = "a project with refined work";
  public static final String A_PROJECT_WITH_NO_WORK = "a project with no work";
  public static final String A_PROJECT_WITH_PENDING_RELEASE_REQUESTS =
      "a project with pending release requests";
  public static final String A_PROJECT_WITH_NO_RELEASE_REQUESTS =
      "a project with no release requests";
  // The landing app's release-request pages (epic qits-112): one state per variant of the detail
  // page and its panels, and one project-wide list. Every detail state seeds the same project and
  // the same two real repositories (see #releaseFixture), so its fold, commits and changes are the
  // same shas on every run, and differs only in the request it focuses on.
  public static final String A_RELEASE_REQUEST_AWAITING_APPROVAL =
      "a release request awaiting approval";
  public static final String A_RELEASE_REQUEST_AWAITING_APPROVAL_WHILE_ITS_BUILD_RUNS =
      "a release request awaiting approval while its build runs";
  public static final String AN_APPROVED_RELEASE_REQUEST = "an approved release request";
  public static final String A_DECLINED_RELEASE_REQUEST = "a declined release request";
  public static final String A_RELEASED_RELEASE_REQUEST = "a released release request";
  public static final String A_RELEASE_REQUEST_WHOSE_PUBLISH_FAILED =
      "a release request whose publish failed";
  public static final String A_RELEASE_REQUEST_REJECTED_BY_ITS_BUILD =
      "a release request rejected by its build";
  public static final String A_RELEASE_REQUEST_HELD_BY_A_FAILED_AUTOMATION =
      "a release request held by a failed automation";
  public static final String A_RELEASE_REQUEST_WITH_AN_AUTOMATION_THAT_DOES_NOT_APPLY =
      "a release request with an automation that does not apply";
  public static final String A_WITHDRAWN_RELEASE_REQUEST = "a withdrawn release request";
  public static final String A_CONFLICTED_RELEASE_REQUEST = "a conflicted release request";
  public static final String A_REFOLDED_RELEASE_REQUEST = "a refolded release request";
  public static final String A_PROJECT_WITH_RELEASE_REQUESTS_IN_EVERY_STATE =
      "a project with release requests in every state";

  /** Every release-request detail state, in the order the recorder walks them. */
  public static final List<String> RELEASE_REQUEST_DETAILS =
      List.of(
          A_RELEASE_REQUEST_AWAITING_APPROVAL,
          A_RELEASE_REQUEST_AWAITING_APPROVAL_WHILE_ITS_BUILD_RUNS,
          AN_APPROVED_RELEASE_REQUEST,
          A_DECLINED_RELEASE_REQUEST,
          A_RELEASED_RELEASE_REQUEST,
          A_RELEASE_REQUEST_WHOSE_PUBLISH_FAILED,
          A_RELEASE_REQUEST_REJECTED_BY_ITS_BUILD,
          A_RELEASE_REQUEST_HELD_BY_A_FAILED_AUTOMATION,
          A_WITHDRAWN_RELEASE_REQUEST,
          A_CONFLICTED_RELEASE_REQUEST,
          A_REFOLDED_RELEASE_REQUEST);

  /** The detail states focused on contract-suite-app, whose fold moves a submodule pin. */
  public static final Set<String> RELEASE_REQUEST_ESTATE_DETAILS =
      Set.of(
          A_RELEASE_REQUEST_AWAITING_APPROVAL,
          A_RELEASE_REQUEST_AWAITING_APPROVAL_WHILE_ITS_BUILD_RUNS,
          AN_APPROVED_RELEASE_REQUEST,
          A_DECLINED_RELEASE_REQUEST,
          A_RELEASE_REQUEST_HELD_BY_A_FAILED_AUTOMATION);

  public static final String A_PROJECT_WITH_REPOSITORIES_IN_COMPONENTS =
      "a project with repositories in components";
  public static final String AN_EPIC_WITH_FEATURES_AND_TASKS = "an epic with features and tasks";
  public static final String AN_EPIC_WITH_TASKS_IN_EVERY_STATUS =
      "an epic with tasks in every status";
  public static final String AN_EPIC_WITH_A_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED =
      "an epic with a feature whose tasks are all verified";
  public static final String AN_EPIC_WITH_A_VERIFIED_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED =
      "an epic with a verified feature whose tasks are all verified";
  public static final String AN_IMPLEMENTING_EPIC_WITH_FEATURES_IN_MIXED_STATUSES =
      "an implementing epic with features in mixed statuses";
  public static final String A_CAMPAIGN_WITH_A_DONE_A_VERIFIED_AND_AN_IMPLEMENTING_EPIC =
      "a campaign with a done, a verified and an implementing epic";
  public static final String A_CAMPAIGN_WITH_ORDERED_DEVELOPMENTS =
      "a campaign with ordered developments";
  public static final String A_VERIFIED_EPIC = "a verified epic";
  public static final String A_VERIFIED_TICKET = "a verified ticket";
  public static final String A_PROJECT_WITH_WORK_IN_EVERY_STATUS =
      "a project with work in every status";
  public static final String A_TICKET_OF_EVERY_TYPE = "a ticket of every type";
  public static final String A_VERIFIED_EPIC_WITH_EVERY_TASK_IMPLEMENTED =
      "a verified epic with every task implemented";
  public static final String A_DONE_EPIC_WITH_EVERY_TASK_IMPLEMENTED =
      "a done epic with every task implemented";
  public static final String A_CAMPAIGN_WITH_WORK_IN_EVERY_PHASE =
      "a campaign with work in every phase";
  public static final String AN_EPIC_IN_TWO_CAMPAIGNS = "an epic in two campaigns";
  public static final String THE_SECOND_CAMPAIGN_OF_AN_EPIC_IN_TWO_CAMPAIGNS =
      "the second campaign of an epic in two campaigns";
  public static final String THE_ARCHETYPE_REGISTRY = "the archetype registry";
  public static final String A_TICKET_WITH_A_COMMENT = "a ticket with a comment";

  /**
   * A REPORTED ticket whose agent session has stood waiting for a person past the debounce, and that
   * nobody blocked (qits-895): the derived block, {@code blockSource: AGENT_WAITING}.
   */
  public static final String A_TICKET_ITS_AGENT_IS_WAITING_ON = "a ticket its agent is waiting on";
  public static final String A_CAMPAIGN_WITH_MEMBERS_TO_EDIT = "a campaign with members to edit";
  public static final String AN_EPIC_WITH_A_SKETCH_TO_INLINE = "an epic with a sketch to inline";
  public static final String A_REPORTED_TICKET = "a reported ticket";
  public static final String A_REFINED_TICKET = "a refined ticket";
  public static final String A_READY_FOR_DEV_TICKET = "a ready for dev ticket";
  public static final String AN_IMPLEMENTING_TICKET = "an implementing ticket";
  public static final String AN_IMPLEMENTED_TICKET = "an implemented ticket";
  public static final String A_VERIFYING_TICKET = "a verifying ticket";
  public static final String A_DROPPED_TICKET = "a dropped ticket";
  public static final String A_REPORTED_EPIC = "a reported epic";
  public static final String A_REFINED_EPIC = "a refined epic";
  public static final String A_READY_FOR_DEV_EPIC = "a ready for dev epic";
  public static final String AN_IMPLEMENTING_EPIC = "an implementing epic";
  public static final String AN_IMPLEMENTED_EPIC = "an implemented epic";
  public static final String A_VERIFYING_EPIC = "a verifying epic";
  public static final String A_DROPPED_EPIC = "a dropped epic";

  // The landing app's detail page, one state per archetype (and per ticket type). All seven seed
  // the same project — see #workInDetail — and differ only in the entity they focus on.
  public static final String AN_EPIC_IN_DETAIL = "an epic in detail";
  public static final String A_FEATURE_IN_DETAIL = "a feature in detail";
  public static final String A_TASK_IN_DETAIL = "a task in detail";
  public static final String A_BUG_TICKET_IN_DETAIL = "a bug ticket in detail";
  public static final String AN_IMPROVEMENT_TICKET_IN_DETAIL = "an improvement ticket in detail";
  public static final String A_MAINTENANCE_TICKET_IN_DETAIL = "a maintenance ticket in detail";
  public static final String A_CAMPAIGN_IN_DETAIL = "a campaign in detail";

  /** Each detail state, by the param naming the entity it focuses on. */
  public static final Map<String, String> IN_DETAIL = inDetail();

  /**
   * The ticket states for the status door, by the status each leaves the ticket in. {@link
   * #A_VERIFIED_TICKET} is one of them.
   */
  public static final Map<String, EntityStatus> TICKET_IN_STATUS = ticketsInStatus();

  /** The epic states for the status door, as {@link #TICKET_IN_STATUS}. */
  public static final Map<String, EntityStatus> EPIC_IN_STATUS = epicsInStatus();

  public static final String NO_PROJECT_WITH_THE_GIVEN_ID = "no project with the given id";
  public static final String NO_REPOSITORY_WITH_THE_GIVEN_ID = "no repository with the given id";

  // Round 2 of qits-1149: the states other services' and the CLI's pacts asked for.
  public static final String AN_AGENT_LAUNCH_IMAGE_IN_USE = "an agent launch image in use";
  public static final String A_PROJECT_AND_AN_UNADOPTED_REPOSITORY_ON_THE_GIT_HOST =
      "a project and an unadopted repository on the git host";
  public static final String A_PROJECT_TO_CREATE_A_REPOSITORY_IN =
      "a project to create a repository in";
  public static final String A_REPOSITORY_WITH_A_BRANCH_TO_RELEASE =
      "a repository with a branch to release";
  public static final String A_RELEASE_REQUEST_OPEN_TO_ANOTHER_BRANCH =
      "a release request open to another branch";
  public static final String A_REPOSITORY_WITH_RELEASED_RELEASE_REQUESTS =
      "a repository with released release requests";
  public static final String A_REPOSITORY_WITH_A_RELEASE_NOT_MERGED_TO_MAIN =
      "a repository with a release not merged to main";
  public static final String THE_ARCHETYPE_REGISTRYS_UPDATE_SCHEMA =
      "the archetype registry's update schema";
  public static final String THE_ARCHETYPE_REGISTRYS_TRANSITION_SCHEMA =
      "the archetype registry's transition schema";
  public static final String A_TICKET_WITH_A_DISPATCHED_AGENT = "a ticket with a dispatched agent";
  public static final String REPOSITORIES_WITH_DECOMMISSIONABLE_TAGS =
      "repositories with decommissionable tags";

  /** The fold the release-request states script the merger to answer, first and after a change. */
  static final String SCRIPTED_FOLD = "5eed5eed5eed5eed5eed5eed5eed5eed5eed0001";

  static final String SCRIPTED_REFOLD = "5eed5eed5eed5eed5eed5eed5eed5eed5eed0002";

  /** The three component repositories {@link #A_PROJECT_WITH_3_REPOSITORIES} creates. */
  static final List<String> THREE_REPOSITORIES =
      List.of("contract-service", "contract-frontend", "contract-daemon");

  /** When the seeded release requests moved: a fixed base, a minute apart per request. */
  private static final Instant SEEDED_AT = Instant.parse("2026-01-01T00:00:00Z");

  /** Who the seeded work items name as their reporter. */
  private static final String SEEDER = "contract-seeder";

  /** The inert record every seeded project carries — a reserved TLD and a documentation address. */
  private static final ProjectDnsRecord DNS =
      new ProjectDnsRecord("example.test.eu", ProjectDnsRecordType.A, "203.0.113.9");

  /**
   * What a state hands back.
   *
   * @param params the state's parameters, keys sorted — what a pact {@code @State} method returns
   * @param uniqueTokens random tokens the state had to put into names to keep them unique
   */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  @Inject ProjectService projectService;

  @Inject WorkEntityService work;

  @Inject eu.wohlben.qits.entities.campaign.CampaignService campaigns;

  @Inject eu.wohlben.qits.entities.control.EntityCommentService comments;

  @Inject eu.wohlben.qits.entities.control.DossierService dossier;

  @Inject eu.wohlben.qits.entities.control.DossierAssetService dossierAssets;

  @Inject eu.wohlben.qits.projects.api.EntityBlocks blocks;

  /** The derived block's door, for the state whose agent is waiting (qits-895). */
  @Inject eu.wohlben.qits.projects.api.AgentWaiting agentWaiting;

  /** Opens a refinement room, for the state whose epic inlines one of its sketches. */
  @Inject eu.wohlben.qits.projects.refinementhost.RefinementService refinements;

  @Inject eu.wohlben.qits.projects.refinementhost.RefinementPromptAttachments attachments;

  /**
   * The test suite's dispatch port: a dispatch press records here and starts no agent. An {@code
   * Instance} because a test profile may take the port away ({@code
   * EntityDispatchWithNoWorkspacesTest}), and this bean is in every test application.
   */
  @Inject
  jakarta.enterprise.inject.Instance<eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch>
      dispatches;

  /** The test suite's turn port, reset with {@link #dispatches} so no earlier test's script leaks. */
  @Inject
  jakarta.enterprise.inject.Instance<eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentTurns>
      turns;

  /** Writes the release fixture's commits; see {@link SeededGit}. */
  @Inject eu.wohlben.qits.projects.control.GitExecutor gitWork;

  @Inject eu.wohlben.qits.projects.control.GitHostAddress gitHost;

  @Inject eu.wohlben.qits.projects.control.GitMirrorRegistry mirrors;

  /** Holds a request's automations note in memory; forgotten again by {@link #cleanUp()}. */
  @Inject eu.wohlben.qits.projects.control.AutomationLedger automationLedger;

  // The release flow's shared fakes. Instances for the reason {@link #dispatches} is one.
  @Inject
  jakarta.enterprise.inject.Instance<eu.wohlben.qits.projects.releasehost.RecordingReleaseGitHost>
      releaseGitHost;

  @Inject
  jakarta.enterprise.inject.Instance<eu.wohlben.qits.projects.releasehost.FakeActiveBuilds>
      activeBuilds;

  @Inject
  jakarta.enterprise.inject.Instance<eu.wohlben.qits.projects.releasehost.FakeReleaseDecisions>
      releaseDecisions;

  @Inject
  jakarta.enterprise.inject.Instance<eu.wohlben.qits.projects.deploymenthost.FakeDeploymentRequests>
      deploymentRequests;

  @Inject
  jakarta.enterprise.inject.Instance<
          eu.wohlben.qits.projects.releasehost.RecordingBackingBranchMerger>
      merger;

  /** Opens release requests the way the door does, for the state that adds a source to one. */
  @Inject eu.wohlben.qits.projects.control.ReleaseRequests releaseRequests;

  /** The fake git host's lifecycle port: a bare the bootstrap made, waiting to be adopted. */
  @Inject eu.wohlben.qits.projects.control.GitHostRepositories gitHostRepositories;

  /** What a launch would pull: the pins route reads these two and nothing else. */
  @Inject eu.wohlben.qits.projects.deskhost.FrontDeskSpecs agentContainers;

  @Inject eu.wohlben.qits.projects.refinementhost.RefinementContainerFactory refinementContainers;

  /** Whether a state scripted the merger, which cleanUp sets back to fresh merges. */
  private boolean mergerTouched;

  /** Repositories whose requests a DOOR opened (ids unknown to the state), removed by cleanUp. */
  private final List<String> requestRepos = new ArrayList<>();

  /** The repository's release-request settings, where {@code manual-review} is declared. */
  private static final String SETTINGS_FILE = ".config/qits/release-requests.yml";

  /** The deployment declaration at a repository's main or tag. */
  private static final String DEPLOYMENTS_FILE = ".config/qits/deployments.yml";

  /** Repositories whose released tags and verdicts {@link #cleanUp()} removes. */
  private final List<String> seededRepos = new ArrayList<>();

  /** Whether a state scripted the decisions or deployments fakes, which cleanUp resets. */
  private boolean fakesTouched;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  /** Release requests and CI verdicts the states wrote, removed again by {@link #cleanUp()}. */
  private final List<String> seededRequests = new ArrayList<>();

  private final List<String> seededVerdicts = new ArrayList<>();

  public ProviderStates() {
    states.put(A_PROJECT_EXISTS, this::aProjectExists);
    states.put(TWO_PROJECTS_EXIST, this::twoProjectsExist);
    states.put(NO_PROJECTS_EXIST, this::noProjectsExist);
    states.put(A_PROJECT_WITH_ONE_REPOSITORY, this::aProjectWithOneRepository);
    states.put(A_PROJECT_WITH_3_REPOSITORIES, this::aProjectWith3Repositories);
    states.put(A_REPOSITORY_EXISTS, this::aRepositoryExists);
    states.put(A_PROJECT_WITH_REFINED_WORK, this::aProjectWithRefinedWork);
    states.put(A_PROJECT_WITH_NO_WORK, this::aProjectWithNoWork);
    states.put(A_PROJECT_WITH_PENDING_RELEASE_REQUESTS, this::aProjectWithPendingReleaseRequests);
    states.put(A_PROJECT_WITH_NO_RELEASE_REQUESTS, this::aProjectWithNoReleaseRequests);
    RELEASE_REQUEST_DETAILS.forEach(name -> states.put(name, () -> releaseRequestInDetail(name)));
    // Not a detail state: recorded for its automations alone, on the detail and the list answer.
    states.put(
        A_RELEASE_REQUEST_WITH_AN_AUTOMATION_THAT_DOES_NOT_APPLY,
        () -> releaseRequestInDetail(A_RELEASE_REQUEST_WITH_AN_AUTOMATION_THAT_DOES_NOT_APPLY));
    states.put(
        A_PROJECT_WITH_RELEASE_REQUESTS_IN_EVERY_STATE, this::aProjectWithReleaseRequestsInEveryState);
    states.put(
        A_PROJECT_WITH_REPOSITORIES_IN_COMPONENTS, this::aProjectWithRepositoriesInComponents);
    states.put(A_PROJECT_WITH_WORK_IN_EVERY_STATUS, this::aProjectWithWorkInEveryStatus);
    states.put(AN_EPIC_WITH_FEATURES_AND_TASKS, this::anEpicWithFeaturesAndTasks);
    states.put(AN_EPIC_WITH_TASKS_IN_EVERY_STATUS, this::anEpicWithTasksInEveryStatus);
    states.put(
        AN_EPIC_WITH_A_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED,
        () ->
            anEpicWithAFeatureWhoseTasksAreAllVerified(
                AN_EPIC_WITH_A_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED));
    states.put(
        AN_EPIC_WITH_A_VERIFIED_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED,
        () ->
            anEpicWithAFeatureWhoseTasksAreAllVerified(
                AN_EPIC_WITH_A_VERIFIED_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED));
    states.put(
        AN_IMPLEMENTING_EPIC_WITH_FEATURES_IN_MIXED_STATUSES,
        this::anImplementingEpicWithFeaturesInMixedStatuses);
    states.put(
        A_CAMPAIGN_WITH_A_DONE_A_VERIFIED_AND_AN_IMPLEMENTING_EPIC,
        this::aCampaignWithADoneAVerifiedAndAnImplementingEpic);
    states.put(A_VERIFIED_EPIC, this::aVerifiedEpic);
    states.put(A_VERIFIED_TICKET, this::aVerifiedTicket);
    states.put(A_CAMPAIGN_WITH_ORDERED_DEVELOPMENTS, this::aCampaignWithOrderedDevelopments);
    states.put(A_TICKET_OF_EVERY_TYPE, this::aTicketOfEveryType);
    states.put(
        A_VERIFIED_EPIC_WITH_EVERY_TASK_IMPLEMENTED,
        () -> anEpicWithEveryTaskImplemented(A_VERIFIED_EPIC_WITH_EVERY_TASK_IMPLEMENTED));
    states.put(
        A_DONE_EPIC_WITH_EVERY_TASK_IMPLEMENTED,
        () -> anEpicWithEveryTaskImplemented(A_DONE_EPIC_WITH_EVERY_TASK_IMPLEMENTED));
    states.put(A_CAMPAIGN_WITH_WORK_IN_EVERY_PHASE, this::aCampaignWithWorkInEveryPhase);
    states.put(AN_EPIC_IN_TWO_CAMPAIGNS, this::anEpicInTwoCampaigns);
    states.put(THE_SECOND_CAMPAIGN_OF_AN_EPIC_IN_TWO_CAMPAIGNS, this::anEpicInTwoCampaigns);
    states.put(THE_ARCHETYPE_REGISTRY, ProviderStates::theArchetypeRegistry);
    states.put(A_TICKET_WITH_A_COMMENT, this::aTicketWithAComment);
    states.put(A_TICKET_ITS_AGENT_IS_WAITING_ON, this::aTicketItsAgentIsWaitingOn);
    states.put(A_CAMPAIGN_WITH_MEMBERS_TO_EDIT, this::aCampaignWithMembersToEdit);
    states.put(AN_EPIC_WITH_A_SKETCH_TO_INLINE, this::anEpicWithASketchToInline);
    TICKET_IN_STATUS.forEach(
        (name, status) -> {
          if (!name.equals(A_VERIFIED_TICKET)) {
            states.put(name, () -> aTicketIn(name, status));
          }
        });
    EPIC_IN_STATUS.forEach(
        (name, status) -> {
          if (!name.equals(A_VERIFIED_EPIC)) {
            states.put(name, () -> anEpicIn(name, status));
          }
        });
    IN_DETAIL.forEach((name, focus) -> states.put(name, () -> workInDetail(name, focus)));
    states.put(NO_PROJECT_WITH_THE_GIVEN_ID, this::noProjectWithTheGivenId);
    states.put(NO_REPOSITORY_WITH_THE_GIVEN_ID, this::noRepositoryWithTheGivenId);
    states.put(AN_AGENT_LAUNCH_IMAGE_IN_USE, this::anAgentLaunchImageInUse);
    states.put(
        A_PROJECT_AND_AN_UNADOPTED_REPOSITORY_ON_THE_GIT_HOST,
        this::aProjectAndAnUnadoptedRepositoryOnTheGitHost);
    states.put(A_PROJECT_TO_CREATE_A_REPOSITORY_IN, this::aProjectToCreateARepositoryIn);
    states.put(A_REPOSITORY_WITH_A_BRANCH_TO_RELEASE, this::aRepositoryWithABranchToRelease);
    states.put(A_RELEASE_REQUEST_OPEN_TO_ANOTHER_BRANCH, this::aReleaseRequestOpenToAnotherBranch);
    states.put(
        A_REPOSITORY_WITH_RELEASED_RELEASE_REQUESTS,
        () -> aRepositoryWithReleases(A_REPOSITORY_WITH_RELEASED_RELEASE_REQUESTS));
    states.put(
        A_REPOSITORY_WITH_A_RELEASE_NOT_MERGED_TO_MAIN,
        () -> aRepositoryWithReleases(A_REPOSITORY_WITH_A_RELEASE_NOT_MERGED_TO_MAIN));
    states.put(THE_ARCHETYPE_REGISTRYS_UPDATE_SCHEMA, ProviderStates::theArchetypeRegistry);
    states.put(THE_ARCHETYPE_REGISTRYS_TRANSITION_SCHEMA, ProviderStates::theArchetypeRegistry);
    states.put(A_TICKET_WITH_A_DISPATCHED_AGENT, this::aTicketWithADispatchedAgent);
    states.put(
        REPOSITORIES_WITH_DECOMMISSIONABLE_TAGS, this::repositoriesWithDecommissionableTags);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  /**
   * Removes the release requests the states wrote. <b>Open requests must not outlive a test</b>:
   * {@code ReleaseRequests.sweep()} walks every open row in the database, so one left behind is a
   * door call inside the next class that sweeps. Callers run this after each recording or
   * verification.
   */
  public void cleanUp() {
    SeededGit.deleteAll();
    if (fakesTouched) {
      fakesTouched = false;
      releaseDecisions.get().reset();
      deploymentRequests.get().reset();
    }
    if (mergerTouched) {
      mergerTouched = false;
      merger.get().reset();
    }
    if (!requestRepos.isEmpty()) {
      List<String> repos = List.copyOf(requestRepos);
      requestRepos.clear();
      seededRequests.addAll(
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      ReleaseRequest.<ReleaseRequest>list("repoId in ?1", repos).stream()
                          .map(row -> row.id)
                          .filter(id -> !seededRequests.contains(id))
                          .toList()));
    }
    if (seededRequests.isEmpty() && seededVerdicts.isEmpty() && seededRepos.isEmpty()) {
      return;
    }
    List<String> ids = List.copyOf(seededRequests);
    List<String> runs = List.copyOf(seededVerdicts);
    List<String> repos = List.copyOf(seededRepos);
    seededRequests.clear();
    seededVerdicts.clear();
    seededRepos.clear();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (!ids.isEmpty()) {
                // What the release states and the doors they record wrote about each request.
                eu.wohlben.qits.projects.entity.ReleaseRequestSource.delete("requestId in ?1", ids);
                eu.wohlben.qits.projects.entity.ReleaseRequestApproval.delete(
                    "requestId in ?1", ids);
                eu.wohlben.qits.projects.entity.ReleaseRequestAutomationWaiver.delete(
                    "requestId in ?1", ids);
                eu.wohlben.qits.projects.entity.ReleasePipelineRun.delete(
                    "releaseRequestId in ?1", ids);
                ReleaseRequest.delete("id in ?1", ids);
              }
              if (!runs.isEmpty()) CommitBuildStatus.delete("runId in ?1", runs);
              if (!repos.isEmpty()) {
                eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge.delete(
                    "repoId in ?1", repos);
                CommitBuildStatus.delete("repoId in ?1", repos);
              }
            });
    ids.forEach(automationLedger::forget);
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /**
   * The state's slug: the name lower-cased, every run of non-alphanumeric characters replaced by
   * {@code -}. It names the state's directory under {@code golden-masters/}.
   */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  private Setup aProjectExists() {
    String token = token();
    Project project = project(token, A_PROJECT_EXISTS);
    return new Setup(params("projectId", project.id), List.of(token));
  }

  /**
   * Two projects, {@code qits} ({@code projectId}) and {@code telemetry} ({@code secondProjectId}),
   * for a picker that shows more than one. The first holds two repositories in the {@code qits-ci}
   * component and three work items (a REFINED epic and ticket, a REPORTED ticket), so its
   * per-project reads have something to count; the second holds nothing. Slugs are {@code
   * <name>-<token>}, as every seeded slug carries a token.
   */
  private Setup twoProjectsExist() {
    String first = token();
    String second = token();
    Project qits = project("qits", "qits-" + first, TWO_PROJECTS_EXIST);
    Project telemetry = project("telemetry", "telemetry-" + second, TWO_PROJECTS_EXIST);
    projectService.createRepository(qits.id, null, "qits-ci-service", null, "qits-ci");
    projectService.createRepository(qits.id, null, "qits-ci-frontend", null, "qits-ci");
    String epic = create(Archetype.EPIC, qits, EntityWrite.epic("Unified SPA", "Seeded work.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    String ticket = ticket(qits, "Picker shows every project");
    ticket(qits, "Cards load their lines lazily");
    work.transition(Archetype.EPIC, epic, "REFINED", SEEDER);
    work.transition(Archetype.TICKET, ticket, "REFINED", SEEDER);
    return new Setup(
        params("projectId", qits.id, "secondProjectId", telemetry.id), List.of(first, second));
  }

  /**
   * Seeds nothing. A pact verification runs it after {@code PlatformStateReset} has emptied the
   * projects tables, so the list is empty there; the recorder filters the list to the state's own
   * entries, of which there are none.
   */
  private Setup noProjectsExist() {
    return new Setup(params(), List.of());
  }

  /** One component repository: the listing holds it and the project's wrapper. */
  private Setup aProjectWithOneRepository() {
    String token = token();
    Project project = project(token, A_PROJECT_WITH_ONE_REPOSITORY);
    projectService.createRepository(project.id, null, "contract-service", null);
    return new Setup(params("projectId", project.id), List.of(token));
  }

  /**
   * Three blank component repositories on the (fake) git host, each added to the wrapper by the
   * same create flow the REST door runs — so the listing's {@code wrapper} view is read off a real
   * {@code .gitmodules}.
   */
  private Setup aProjectWith3Repositories() {
    String token = token();
    Project project = project(token, A_PROJECT_WITH_3_REPOSITORIES);
    for (String name : THREE_REPOSITORIES) {
      projectService.createRepository(project.id, null, name, null);
    }
    return new Setup(params("projectId", project.id), List.of(token));
  }

  private Setup aRepositoryExists() {
    String token = token();
    Project project = project(token, A_REPOSITORY_EXISTS);
    var created = projectService.createRepository(project.id, null, "contract-service", null);
    return new Setup(
        params(
            "projectId", project.id,
            "repositoryId", created.repository().id,
            "repoName", "contract-service"),
        List.of(token));
  }

  /**
   * Five work items: three REFINED (an epic and two tickets), one ticket still REPORTED and one
   * DONE, so a status filter has something to leave out on both sides. Created and moved through
   * the service layer in a fixed order, so their numbers are fixed too.
   */
  private Setup aProjectWithRefinedWork() {
    String token = token();
    Project project = project(token, A_PROJECT_WITH_REFINED_WORK);
    String epic = create(Archetype.EPIC, project, EntityWrite.epic("Refined epic", "Seeded work.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    String first = ticket(project, "Refined ticket");
    String second = ticket(project, "Second refined ticket");
    ticket(project, "Reported ticket");
    String done = ticket(project, "Done ticket");
    work.transition(Archetype.EPIC, epic, "REFINED", SEEDER);
    work.transition(Archetype.TICKET, first, "REFINED", SEEDER);
    work.transition(Archetype.TICKET, second, "REFINED", SEEDER);
    walk(Archetype.TICKET, done, EntityStatus.DONE);
    return new Setup(params("projectId", project.id), List.of(token));
  }

  /** A project and nothing in it: every work list answers empty. */
  /**
   * One epic and one ticket in each status, READY_FOR_DEV, IMPLEMENTING, VERIFYING and DROPPED
   * included: the Work
   * page's board, backlog and archive all have something to show. Each item is moved through the
   * lifecycle to its status, through IMPLEMENTING and VERIFYING wherever its path passes them
   * (qits-749). Plus one scheduled (READY_FOR_DEV) epic whose
   * one feature holds a task marked implementing and not implemented, so a feature and a task in
   * the IMPLEMENTING column are on the record too.
   */
  private Setup aProjectWithWorkInEveryStatus() {
    String token = token();
    Project project = project(token, A_PROJECT_WITH_WORK_IN_EVERY_STATUS);
    Map<String, List<String>> paths = new LinkedHashMap<>();
    paths.put("Reported", List.of());
    paths.put("Refined", List.of("REFINED"));
    paths.put("Ready for dev", List.of("REFINED", "READY_FOR_DEV"));
    paths.put("Implementing", List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTING"));
    paths.put("Implemented", List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTING", "IMPLEMENTED"));
    paths.put("Verifying", List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTING", "IMPLEMENTED", "VERIFYING"));
    paths.put(
        "Verified", List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTING", "IMPLEMENTED", "VERIFYING", "VERIFIED"));
    paths.put(
        "Done",
        List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTING", "IMPLEMENTED", "VERIFYING", "VERIFIED", "DONE"));
    paths.put("Dropped", List.of("DROPPED"));
    for (Map.Entry<String, List<String>> path : paths.entrySet()) {
      String epic =
          create(Archetype.EPIC, project, EntityWrite.epic(path.getKey() + " epic", "Seeded work.").withAcceptanceCriteria(TestCriteria.CRITERIA));
      String ticket = ticket(project, path.getKey() + " ticket");
      for (String status : path.getValue()) {
        work.transition(Archetype.EPIC, epic, status, Mover.person(SEEDER));
        work.transition(Archetype.TICKET, ticket, status, Mover.person(SEEDER));
      }
    }
    String repositoryId = repository(project, "contract-service");
    String started =
        create(Archetype.EPIC, project, EntityWrite.epic("Started epic", "Seeded work.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    String feature =
        node(Archetype.FEATURE, started, EntityWrite.feature("Started feature", "Seeded.", null));
    String task =
        node(Archetype.TASK, feature, EntityWrite.task(repositoryId, "Started task", "Seeded.", null));
    work.transition(Archetype.EPIC, started, "REFINED", SEEDER);
    work.transition(Archetype.EPIC, started, "READY_FOR_DEV", Mover.person(SEEDER));
    // The tool's own path: stamps the task and its feature, and moves the epic to IMPLEMENTING.
    work.markImplementing(task, SEEDER);
    return new Setup(params("projectId", project.id), List.of(token));
  }

  /**
   * A READY_FOR_DEV epic (refined and scheduled, so its markers move — qits-887) with two features:
   * one implemented (one of its two tasks implemented too), one
   * not (with one open task). The implemented feature runs ahead of its epic, so the epic's lane on
   * a board spans two columns, and so does the feature's. Children are created while the epic is still REPORTED, as the domain demands.
   */
  private Setup anEpicWithFeaturesAndTasks() {
    String token = token();
    Project project = project(token, AN_EPIC_WITH_FEATURES_AND_TASKS);
    String repositoryId = repository(project, "contract-service");
    String epic = create(Archetype.EPIC, project, EntityWrite.epic("Nested epic", "Seeded work.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    String done = node(Archetype.FEATURE, epic, EntityWrite.feature("Shipped feature", "Seeded.", null));
    String shippedTask =
        node(Archetype.TASK, done, EntityWrite.task(repositoryId, "First shipped task", "Seeded.", null));
    node(Archetype.TASK, done, EntityWrite.task(repositoryId, "Second shipped task", "Seeded.", null));
    String open = node(Archetype.FEATURE, epic, EntityWrite.feature("Open feature", "Seeded.", null));
    node(Archetype.TASK, open, EntityWrite.task(repositoryId, "Open task", "Seeded.", null));
    work.transition(Archetype.EPIC, epic, "REFINED", SEEDER);
    work.transition(Archetype.EPIC, epic, "READY_FOR_DEV", Mover.person(SEEDER));
    implemented(shippedTask);
    implemented(done);
    return new Setup(params("projectId", project.id), List.of(token));
  }

  /**
   * <b>One epic whose tasks stand in every status</b> (qits-763): a feature and a task hold the one
   * lifecycle of their own, so a board draws each task in its own column rather than its epic's. One
   * feature with nine tasks, one per word, each brought there the way the platform moves one:
   * created while the epic is REPORTED, carried to REFINED by the epic's freeze and to READY_FOR_DEV
   * by its scheduling (qits-887), then — the REPORTED one moved back by its own door before the
   * scheduling, the IMPLEMENTING one through {@code mark_task_implementing} (which also moves its
   * feature and the epic to IMPLEMENTING), the REFINED one moved back by its own door once the epic
   * is under way, the IMPLEMENTED one through its marker, the three beyond through their marker and
   * then their own moves, and the DROPPED one dropped. The epic stays IMPLEMENTING: verifying three of its tasks moved nothing above them.
   */
  private Setup anEpicWithTasksInEveryStatus() {
    String token = token();
    Project project = project(token, AN_EPIC_WITH_TASKS_IN_EVERY_STATUS);
    String repositoryId = repository(project, "contract-service");
    String epic =
        create(Archetype.EPIC, project, EntityWrite.epic("Epic in flight", "Seeded work.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    String feature =
        node(Archetype.FEATURE, epic, EntityWrite.feature("Feature in flight", "Seeded.", null));
    Map<EntityStatus, String> tasks = new LinkedHashMap<>();
    for (EntityStatus status : EntityStateMachine.states()) {
      String title = status.name().charAt(0) + status.name().substring(1).toLowerCase(Locale.ROOT);
      tasks.put(
          status,
          node(
              Archetype.TASK,
              feature,
              EntityWrite.task(repositoryId, title + " task", "Seeded.", null)));
    }
    work.transition(Archetype.EPIC, epic, "REFINED", SEEDER);
    work.transition(Archetype.TASK, tasks.get(EntityStatus.REPORTED), "REPORTED", SEEDER);
    work.transition(Archetype.EPIC, epic, "READY_FOR_DEV", Mover.person(SEEDER));
    work.markImplementing(tasks.get(EntityStatus.IMPLEMENTING), SEEDER);
    work.transition(Archetype.TASK, tasks.get(EntityStatus.REFINED), "REFINED", SEEDER);
    for (EntityStatus status :
        List.of(
            EntityStatus.IMPLEMENTED,
            EntityStatus.VERIFYING,
            EntityStatus.VERIFIED,
            EntityStatus.DONE)) {
      String task = tasks.get(status);
      implemented(task);
      walk(Archetype.TASK, task, status);
    }
    work.transition(Archetype.TASK, tasks.get(EntityStatus.DROPPED), "DROPPED", SEEDER);
    return new Setup(params("epicId", epic, "projectId", project.id), List.of(token));
  }

  /**
   * <b>An IMPLEMENTING epic with a feature whose tasks are all VERIFIED</b>: a board draws that
   * feature's row with empty lanes. A second feature keeps one REFINED task (moved back by its own
   * door once the epic is under way) and one IMPLEMENTING task, for contrast. The epic is scheduled
   * (READY_FOR_DEV, which carries its pieces), and the IMPLEMENTING task moves it to IMPLEMENTING;
   * each verified task gets its marker and then its own moves, which move nothing above it.
   *
   * <p>The feature keeps its own status: READY_FOR_DEV, titled "Feature pending verification". For
   * {@link #AN_EPIC_WITH_A_VERIFIED_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED} it is titled "Verified
   * feature" and, after its tasks, gets its own marker and moves to VERIFIED too.
   */
  private Setup anEpicWithAFeatureWhoseTasksAreAllVerified(String state) {
    boolean featureVerified =
        state.equals(AN_EPIC_WITH_A_VERIFIED_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED);
    String token = token();
    Project project = project(token, state);
    String repositoryId = repository(project, "contract-service");
    String epic =
        create(Archetype.EPIC, project, EntityWrite.epic("Epic in flight", "Seeded work.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    String verified =
        node(
            Archetype.FEATURE,
            epic,
            EntityWrite.feature(
                featureVerified ? "Verified feature" : "Feature pending verification",
                "Seeded.",
                null));
    List<String> verifiedTasks = new ArrayList<>();
    for (String ordinal : List.of("First", "Second", "Third")) {
      verifiedTasks.add(
          node(
              Archetype.TASK,
              verified,
              EntityWrite.task(repositoryId, ordinal + " verified task", "Seeded.", null)));
    }
    String open =
        node(Archetype.FEATURE, epic, EntityWrite.feature("Open feature", "Seeded.", null));
    String refinedTask =
        node(Archetype.TASK, open, EntityWrite.task(repositoryId, "Refined task", "Seeded.", null));
    String started =
        node(
            Archetype.TASK,
            open,
            EntityWrite.task(repositoryId, "Implementing task", "Seeded.", null));
    work.transition(Archetype.EPIC, epic, "REFINED", SEEDER);
    work.transition(Archetype.EPIC, epic, "READY_FOR_DEV", Mover.person(SEEDER));
    work.markImplementing(started, SEEDER);
    work.transition(Archetype.TASK, refinedTask, "REFINED", SEEDER);
    for (String task : verifiedTasks) {
      implemented(task);
      walk(Archetype.TASK, task, EntityStatus.VERIFIED);
    }
    if (featureVerified) {
      implemented(verified);
      walk(Archetype.FEATURE, verified, EntityStatus.VERIFIED);
    }
    return new Setup(params("epicId", epic, "projectId", project.id), List.of(token));
  }

  /**
   * A REFINED campaign ordering three developments: a VERIFIED epic, a REFINED epic with an open
   * feature, and a REPORTED ticket; plus one IMPLEMENTED ticket outside any campaign. Recorded for
   * the entity list and for the campaign itself (its members, in order).
   */
  private Setup aCampaignWithOrderedDevelopments() {
    String token = token();
    Project project = project(token, A_CAMPAIGN_WITH_ORDERED_DEVELOPMENTS);
    String campaign =
        work.createCampaign(project.id, "Ordered campaign", "Seeded work.", SEEDER).id;
    String shipped = create(Archetype.EPIC, project, EntityWrite.epic("Verified epic", "Seeded.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    String running = create(Archetype.EPIC, project, EntityWrite.epic("Running epic", "Seeded.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    node(Archetype.FEATURE, running, EntityWrite.feature("Running feature", "Seeded.", null));
    String waiting = ticket(project, "Waiting ticket");
    String standalone = ticket(project, "Standalone ticket");
    walk(Archetype.EPIC, shipped, EntityStatus.VERIFIED);
    work.transition(Archetype.EPIC, running, "REFINED", SEEDER);
    walk(Archetype.TICKET, standalone, EntityStatus.IMPLEMENTED);
    for (String member : List.of(shipped, running, waiting)) {
      campaigns.addMember(campaign, member, null, false, SEEDER);
    }
    work.transition(Archetype.CAMPAIGN, campaign, "REFINED", SEEDER);
    return new Setup(
        params(
            "campaignId",
            campaign,
            "campaignQualifiedId",
            qualified(project, campaign),
            "projectId",
            project.id),
        List.of(token));
  }

  /**
   * <b>An IMPLEMENTING epic with features in mixed statuses</b>: a VERIFIED feature with one
   * VERIFIED task, an IMPLEMENTED feature with one VERIFIED task, a REFINED feature with one REFINED
   * task, and an IMPLEMENTING feature with one IMPLEMENTING and one VERIFYING task. Each feature is
   * moved on its own: task moves move nothing above them, except the first IMPLEMENTING task, which
   * moves its feature and the epic to IMPLEMENTING.
   */
  private Setup anImplementingEpicWithFeaturesInMixedStatuses() {
    String token = token();
    Project project = project(token, AN_IMPLEMENTING_EPIC_WITH_FEATURES_IN_MIXED_STATUSES);
    String repositoryId = repository(project, "contract-service");
    String epic = epicWithFeaturesInMixedStatuses(project, repositoryId);
    return new Setup(params("epicId", epic, "projectId", project.id), List.of(token));
  }

  /**
   * Seeds the IMPLEMENTING epic of {@link #AN_IMPLEMENTING_EPIC_WITH_FEATURES_IN_MIXED_STATUSES}
   * in {@code project} and returns its id.
   */
  private String epicWithFeaturesInMixedStatuses(Project project, String repositoryId) {
    String epic =
        create(
            Archetype.EPIC, project, EntityWrite.epic("Epic with mixed features", "Seeded work.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    String verifiedFeature =
        node(Archetype.FEATURE, epic, EntityWrite.feature("Verified feature", "Seeded.", null));
    String verifiedTask =
        node(
            Archetype.TASK,
            verifiedFeature,
            EntityWrite.task(repositoryId, "Verified task", "Seeded.", null));
    String implementedFeature =
        node(Archetype.FEATURE, epic, EntityWrite.feature("Implemented feature", "Seeded.", null));
    String taskOfImplemented =
        node(
            Archetype.TASK,
            implementedFeature,
            EntityWrite.task(
                repositoryId, "Verified task of an implemented feature", "Seeded.", null));
    String refinedFeature =
        node(Archetype.FEATURE, epic, EntityWrite.feature("Refined feature", "Seeded.", null));
    String refinedTask =
        node(
            Archetype.TASK,
            refinedFeature,
            EntityWrite.task(repositoryId, "Refined task", "Seeded.", null));
    String implementingFeature =
        node(
            Archetype.FEATURE, epic, EntityWrite.feature("Implementing feature", "Seeded.", null));
    String implementingTask =
        node(
            Archetype.TASK,
            implementingFeature,
            EntityWrite.task(repositoryId, "Implementing task", "Seeded.", null));
    String verifyingTask =
        node(
            Archetype.TASK,
            implementingFeature,
            EntityWrite.task(repositoryId, "Verifying task", "Seeded.", null));
    work.transition(Archetype.EPIC, epic, "REFINED", SEEDER);
    work.transition(Archetype.EPIC, epic, "READY_FOR_DEV", Mover.person(SEEDER));
    work.markImplementing(implementingTask, SEEDER);
    // The epic's scheduling carried every piece to READY_FOR_DEV (qits-887); the REFINED pair is
    // moved back by its own doors once the epic is under way.
    work.transition(Archetype.TASK, refinedTask, "REFINED", SEEDER);
    work.transition(Archetype.FEATURE, refinedFeature, "REFINED", SEEDER);
    for (String task : List.of(verifiedTask, taskOfImplemented)) {
      implemented(task);
      walk(Archetype.TASK, task, EntityStatus.VERIFIED);
    }
    implemented(verifyingTask);
    walk(Archetype.TASK, verifyingTask, EntityStatus.VERIFYING);
    implemented(verifiedFeature);
    walk(Archetype.FEATURE, verifiedFeature, EntityStatus.VERIFIED);
    implemented(implementedFeature);
    walk(Archetype.FEATURE, implementedFeature, EntityStatus.IMPLEMENTED);
    return epic;
  }

  /**
   * Seeds an epic with one feature and its tasks, each moved to {@code target} along the walk:
   * the epic is made REFINED and scheduled (READY_FOR_DEV), then the tasks, then the feature, then
   * the epic itself are moved.
   */
  private String epicWithEverything(
      Project project, String repositoryId, String title, String prefix, EntityStatus target) {
    String epic = create(Archetype.EPIC, project, EntityWrite.epic(title, "Seeded work.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    String feature =
        node(Archetype.FEATURE, epic, EntityWrite.feature(prefix + " feature", "Seeded.", null));
    List<String> tasks = new ArrayList<>();
    for (String ordinal : List.of("First", "Second")) {
      tasks.add(
          node(
              Archetype.TASK,
              feature,
              EntityWrite.task(
                  repositoryId,
                  ordinal + " " + prefix.toLowerCase() + " task",
                  "Seeded.",
                  null)));
    }
    work.transition(Archetype.EPIC, epic, "REFINED", SEEDER);
    work.transition(Archetype.EPIC, epic, "READY_FOR_DEV", Mover.person(SEEDER));
    for (String task : tasks) {
      implemented(task);
      walk(Archetype.TASK, task, target);
    }
    implemented(feature);
    walk(Archetype.FEATURE, feature, target);
    walk(Archetype.EPIC, epic, target);
    return epic;
  }

  /**
   * A REFINED (running) campaign for the landing app's campaign views, with five members in this
   * order: a DONE ticket; a DONE epic whose feature and two tasks are DONE; a VERIFIED epic whose
   * feature and two tasks are VERIFIED; the IMPLEMENTING epic of {@link
   * #AN_IMPLEMENTING_EPIC_WITH_FEATURES_IN_MIXED_STATUSES}; and a REFINED ticket. Recorded for the
   * entity list, for the campaign, and for the detail page's reads of the campaign item (its
   * {@code qualifiedId} param); the params name every member, so all answers give each member the
   * same frozen id.
   */
  private Setup aCampaignWithADoneAVerifiedAndAnImplementingEpic() {
    String token = token();
    Project project = project(token, A_CAMPAIGN_WITH_A_DONE_A_VERIFIED_AND_AN_IMPLEMENTING_EPIC);
    String repositoryId = repository(project, "contract-service");
    String campaign =
        work.createCampaign(
                project.id,
                "Campaign in flight",
                "Ships two tickets and three epics, one after the other.",
                SEEDER)
            .id;
    String doneTicket = ticket(project, "Done ticket");
    String refinedTicket = ticket(project, "Refined ticket");
    String doneEpic =
        epicWithEverything(project, repositoryId, "Done epic", "Done", EntityStatus.DONE);
    String verifiedEpic =
        epicWithEverything(
            project, repositoryId, "Verified epic", "Verified", EntityStatus.VERIFIED);
    String implementingEpic = epicWithFeaturesInMixedStatuses(project, repositoryId);
    walk(Archetype.TICKET, doneTicket, EntityStatus.DONE);
    work.transition(Archetype.TICKET, refinedTicket, "REFINED", SEEDER);
    for (String member :
        List.of(doneTicket, doneEpic, verifiedEpic, implementingEpic, refinedTicket)) {
      campaigns.addMember(campaign, member, null, false, SEEDER);
    }
    work.transition(Archetype.CAMPAIGN, campaign, "REFINED", SEEDER);
    return new Setup(
        params(
            "campaignId", campaign,
            "campaignQualifiedId", qualified(project, campaign),
            "doneEpicId", doneEpic,
            "doneTicketId", doneTicket,
            "implementingEpicId", implementingEpic,
            "projectId", project.id,
            "qualifiedId",
                eu.wohlben.qits.projects.api.QualifiedEntityIds.render(
                    project.slug, work.find(campaign).number),
            "refinedTicketId", refinedTicket,
            "verifiedEpicId", verifiedEpic),
        List.of(token));
  }

  /** A VERIFIED epic with one feature and one task, ready to be moved to DONE. */
  private Setup aVerifiedEpic() {
    String token = token();
    Project project = project(token, A_VERIFIED_EPIC);
    String repositoryId = repository(project, "contract-service");
    String epic = create(Archetype.EPIC, project, EntityWrite.epic("Verified epic", "Seeded.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    String feature = node(Archetype.FEATURE, epic, EntityWrite.feature("A feature", "Seeded.", null));
    node(Archetype.TASK, feature, EntityWrite.task(repositoryId, "A task", "Seeded.", null));
    walk(Archetype.EPIC, epic, EntityStatus.VERIFIED);
    return new Setup(
        params("epicId", epic, "projectId", project.id, "qualifiedId", qualified(project, epic)),
        List.of(token));
  }

  private static Map<String, EntityStatus> ticketsInStatus() {
    Map<String, EntityStatus> out = new LinkedHashMap<>();
    out.put(A_REPORTED_TICKET, EntityStatus.REPORTED);
    out.put(A_REFINED_TICKET, EntityStatus.REFINED);
    out.put(A_READY_FOR_DEV_TICKET, EntityStatus.READY_FOR_DEV);
    out.put(AN_IMPLEMENTING_TICKET, EntityStatus.IMPLEMENTING);
    out.put(AN_IMPLEMENTED_TICKET, EntityStatus.IMPLEMENTED);
    out.put(A_VERIFYING_TICKET, EntityStatus.VERIFYING);
    out.put(A_VERIFIED_TICKET, EntityStatus.VERIFIED);
    out.put(A_DROPPED_TICKET, EntityStatus.DROPPED);
    return Collections.unmodifiableMap(out);
  }

  private static Map<String, EntityStatus> epicsInStatus() {
    Map<String, EntityStatus> out = new LinkedHashMap<>();
    out.put(A_REPORTED_EPIC, EntityStatus.REPORTED);
    out.put(A_REFINED_EPIC, EntityStatus.REFINED);
    out.put(A_READY_FOR_DEV_EPIC, EntityStatus.READY_FOR_DEV);
    out.put(AN_IMPLEMENTING_EPIC, EntityStatus.IMPLEMENTING);
    out.put(AN_IMPLEMENTED_EPIC, EntityStatus.IMPLEMENTED);
    out.put(A_VERIFYING_EPIC, EntityStatus.VERIFYING);
    out.put(A_VERIFIED_EPIC, EntityStatus.VERIFIED);
    out.put(A_DROPPED_EPIC, EntityStatus.DROPPED);
    return Collections.unmodifiableMap(out);
  }

  /** The registry is the model itself and needs no seed. */
  private static Setup theArchetypeRegistry() {
    return new Setup(params(), List.of());
  }

  /**
   * A BUG ticket in {@code status}, walked there one step at a time (DROPPED: dropped while
   * REPORTED). It carries an impetus, a description and, from IMPLEMENTED on, the implementer's
   * comment — the shape of a real ticket's page. The dispatch ports are reset, so a dispatch press
   * answers the recording port's default and starts no agent.
   */
  private Setup aTicketIn(String state, EntityStatus status) {
    resetDispatchPorts();
    String token = token();
    Project project = project(token, state);
    String ticket =
        create(
            Archetype.TICKET,
            project,
            EntityWrite.ticket(
                "Database runs out of connection slots during deploys",
                "A new container fails its migration at boot: the database refuses the connection.",
                "Raise the database's connection limit, so two pools fit during a rolling deploy."
                    + " Verify by deploying again.",
                "BUG",
                null).withAcceptanceCriteria(TestCriteria.CRITERIA));
    moveTo(Archetype.TICKET, ticket, status);
    if (status != EntityStatus.DROPPED
        && EntityStateMachine.isAtOrPast(status, EntityStatus.IMPLEMENTED)) {
      comments.addComment(ticket, "Released and deployed. The connection limit is now 300.", SEEDER);
    }
    return new Setup(
        params(
            "projectId", project.id, "qualifiedId", qualified(project, ticket), "ticketId", ticket),
        List.of(token));
  }

  /** An epic with no children in {@code status}, walked there as {@link #aTicketIn} is. */
  private Setup anEpicIn(String state, EntityStatus status) {
    resetDispatchPorts();
    String token = token();
    Project project = project(token, state);
    String epic =
        create(
            Archetype.EPIC,
            project,
            EntityWrite.epic("Work item actions", "Seeded work: the page's moves and dispatches.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    moveTo(Archetype.EPIC, epic, status);
    return new Setup(
        params("epicId", epic, "projectId", project.id, "qualifiedId", qualified(project, epic)),
        List.of(token));
  }

  private void moveTo(Archetype archetype, String id, EntityStatus status) {
    if (status == EntityStatus.DROPPED) {
      work.transition(archetype, id, status.name(), Mover.person(SEEDER));
    } else {
      walk(archetype, id, status);
    }
  }

  private void resetDispatchPorts() {
    if (dispatches.isResolvable()) {
      dispatches.get().reset();
    }
    if (turns.isResolvable()) {
      turns.get().reset();
    }
  }

  /** A VERIFIED ticket, ready to be moved to DONE. */
  private Setup aVerifiedTicket() {
    String token = token();
    Project project = project(token, A_VERIFIED_TICKET);
    String ticket = ticket(project, "Verified ticket");
    walk(Archetype.TICKET, ticket, EntityStatus.VERIFIED);
    return new Setup(
        params(
            "projectId", project.id, "qualifiedId", qualified(project, ticket), "ticketId", ticket),
        List.of(token));
  }

  /**
   * A REPORTED BUG ticket with one comment on its thread, for the thread's writes on the work family
   * (qits-969): the comment is the {@code commentId} param an edit and a delete address, under the
   * ticket's {@code qualifiedId}.
   */
  /**
   * {@link #A_TICKET_ITS_AGENT_IS_WAITING_ON}: the session's Stop is reported as stamped a minute
   * ago, past the 30s debounce, so the derived block stands the moment the state is set up.
   */
  private Setup aTicketItsAgentIsWaitingOn() {
    String token = token();
    Project project = project(token, A_TICKET_ITS_AGENT_IS_WAITING_ON);
    String ticket = ticket(project, "Export waits on a question nobody answered");
    agentWaiting.report(
        work.get(Archetype.TICKET, ticket),
        true,
        "Stop",
        "contract-session",
        java.time.Instant.now().minusSeconds(60));
    return new Setup(
        params("projectId", project.id, "qualifiedId", qualified(project, ticket), "ticketId", ticket),
        List.of(token));
  }

  private Setup aTicketWithAComment() {
    String token = token();
    Project project = project(token, A_TICKET_WITH_A_COMMENT);
    String ticket = ticket(project, "Export fails for an empty quarter");
    String comment =
        comments.addComment(ticket, "It answers 500 when the range holds no invoice.", PERSON).id;
    return new Setup(
        params(
            "commentId", comment,
            "projectId", project.id,
            "qualifiedId", qualified(project, ticket),
            "ticketId", ticket),
        List.of(token));
  }

  /**
   * A REPORTED campaign of three members — a REFINED ticket, a REFINED epic, and a REPORTED ticket
   * gated on the epic being VERIFIED and on an approval — plus one ticket outside it, for the
   * membership writes of the work family (qits-970): the add (the outside ticket, by qualified id),
   * the move, the remove, the condition (on the epic, by qualified id) and the approve. Each
   * membership and the approval criterion are params, and the campaign is the {@code qualifiedId}.
   */
  private Setup aCampaignWithMembersToEdit() {
    String token = token();
    Project project = project(token, A_CAMPAIGN_WITH_MEMBERS_TO_EDIT);
    String campaign =
        work.createCampaign(project.id, "Close the quarter", "Seeded work.", PERSON).id;
    String first = ticket(project, "Export the quarter as CSV");
    String epic =
        create(
            Archetype.EPIC,
            project,
            EntityWrite.epic("Tax rates per country", "Seeded.")
                .withAcceptanceCriteria(TestCriteria.CRITERIA));
    String last = ticket(project, "Credit notes show the wrong sign");
    String outside = ticket(project, "Remember the last export format");
    work.transition(Archetype.TICKET, first, "REFINED", SEEDER);
    work.transition(Archetype.EPIC, epic, "REFINED", SEEDER);
    List<String> memberships = new ArrayList<>();
    for (String member : List.of(first, epic, last)) {
      memberships.add(campaigns.addMember(campaign, member, null, false, PERSON).membership().id);
    }
    var gated =
        campaigns.setCondition(
            campaign,
            memberships.get(2),
            List.of(
                new eu.wohlben.qits.entities.campaign.CampaignService.GroupSpec(
                    List.of(
                        new eu.wohlben.qits.entities.campaign.CampaignService.CriterionSpec(
                            null, "ENTITY_STATUS", Map.of("entityId", epic, "status", "VERIFIED")),
                        new eu.wohlben.qits.entities.campaign.CampaignService.CriterionSpec(
                            null, "APPROVAL", null)))),
            PERSON);
    String approval =
        gated.groups().get(0).criteria().stream()
            .filter(c -> c.kind.name().equals("APPROVAL"))
            .findFirst()
            .orElseThrow()
            .id;
    return new Setup(
        params(
            "approvalCriterionId", approval,
            "campaignId", campaign,
            "epicId", epic,
            "epicMembershipId", memberships.get(1),
            "epicQualifiedId", qualified(project, epic),
            "firstMembershipId", memberships.get(0),
            "firstTicketId", first,
            "lastMembershipId", memberships.get(2),
            "lastTicketId", last,
            "outsideTicketId", outside,
            "outsideTicketQualifiedId", qualified(project, outside),
            "projectId", project.id,
            "qualifiedId", qualified(project, campaign)),
        List.of(token));
  }

  /**
   * A REPORTED epic whose refinement room holds one sketch, for the inline door of the work family
   * (qits-970): the sketch is the {@code sketchId} param, the epic the {@code qualifiedId}. The room
   * is opened the way the refine press opens it, against the suite's refinement fakes.
   */
  private Setup anEpicWithASketchToInline() {
    resetDispatchPorts();
    String token = token();
    Project project = project(token, AN_EPIC_WITH_A_SKETCH_TO_INLINE);
    String epic =
        create(
            Archetype.EPIC,
            project,
            EntityWrite.epic("Export invoices for the accountants", "Seeded.")
                .withAcceptanceCriteria(TestCriteria.CRITERIA));
    long room = refinements.findOrCreate(epic).id;
    String sketch =
        attachments
            .add(
                room,
                "Export data flow",
                "SKETCH",
                java.util.Base64.getEncoder().encodeToString(FIGURE_PNG))
            .id;
    return new Setup(
        params(
            "epicId", epic,
            "projectId", project.id,
            "qualifiedId", qualified(project, epic),
            "sketchId", sketch),
        List.of(token));
  }

  /** The qualified id of entity {@code id} in {@code project}: {@code <slug>-<number>}. */
  private String qualified(Project project, String id) {
    return eu.wohlben.qits.projects.api.QualifiedEntityIds.render(
        project.slug, work.find(id).number);
  }

  /**
   * Three REFINED tickets, one of each type: BUG, IMPROVEMENT and MAINTENANCE (the type the
   * platform files for a stuck release request; seeded here through the service layer).
   */
  private Setup aTicketOfEveryType() {
    String token = token();
    Project project = project(token, A_TICKET_OF_EVERY_TYPE);
    for (String type : List.of("BUG", "IMPROVEMENT", "MAINTENANCE")) {
      String title = type.charAt(0) + type.substring(1).toLowerCase(Locale.ROOT) + " ticket";
      String ticket =
          create(Archetype.TICKET, project, EntityWrite.ticket(title, "Seeded work.", null, type, null).withAcceptanceCriteria(TestCriteria.CRITERIA));
      work.transition(Archetype.TICKET, ticket, "REFINED", SEEDER);
    }
    return new Setup(params("projectId", project.id), List.of(token));
  }

  /**
   * An epic whose work is complete: one feature with two tasks, all three implemented while the
   * epic is READY_FOR_DEV, then the epic moved to VERIFIED, or on to DONE for {@link
   * #A_DONE_EPIC_WITH_EVERY_TASK_IMPLEMENTED}. The children are created while the epic is still
   * REPORTED, as the domain demands.
   */
  private Setup anEpicWithEveryTaskImplemented(String state) {
    String token = token();
    Project project = project(token, state);
    String repositoryId = repository(project, "contract-service");
    boolean done = state.equals(A_DONE_EPIC_WITH_EVERY_TASK_IMPLEMENTED);
    String epic =
        create(
            Archetype.EPIC,
            project,
            EntityWrite.epic(done ? "Done epic" : "Verified epic", "Seeded work.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    String feature =
        node(Archetype.FEATURE, epic, EntityWrite.feature("Shipped feature", "Seeded.", null));
    List<String> tasks = new ArrayList<>();
    for (String title : List.of("First shipped task", "Second shipped task")) {
      tasks.add(
          node(Archetype.TASK, feature, EntityWrite.task(repositoryId, title, "Seeded.", null)));
    }
    // Task markers move only while the epic is READY_FOR_DEV or IMPLEMENTING.
    work.transition(Archetype.EPIC, epic, "REFINED", SEEDER);
    work.transition(Archetype.EPIC, epic, "READY_FOR_DEV", Mover.person(SEEDER));
    tasks.forEach(this::implemented);
    implemented(feature);
    walk(Archetype.EPIC, epic, done ? EntityStatus.DONE : EntityStatus.VERIFIED);
    return new Setup(params("projectId", project.id), List.of(token));
  }

  /**
   * A REFINED campaign whose members sit in every phase: a REFINED epic and a REFINED ticket (the
   * board), a REPORTED ticket (the backlog) and a DONE ticket (the archive), plus one REFINED ticket
   * outside the campaign. Recorded for the entity list and for the campaign.
   *
   * <p>The params name every member, so both answers number the same entity with the same frozen
   * id: the freezer numbers the params first, and a consumer can join the campaign's members to the
   * list's entities.
   */
  private Setup aCampaignWithWorkInEveryPhase() {
    String token = token();
    Project project = project(token, A_CAMPAIGN_WITH_WORK_IN_EVERY_PHASE);
    String campaign = work.createCampaign(project.id, "Card campaign", "Seeded work.", SEEDER).id;
    String epic = create(Archetype.EPIC, project, EntityWrite.epic("Refined epic", "Seeded.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    String refined = ticket(project, "Refined ticket");
    String reported = ticket(project, "Reported ticket");
    String done = ticket(project, "Done ticket");
    String outside = ticket(project, "Ticket outside the campaign");
    work.transition(Archetype.EPIC, epic, "REFINED", SEEDER);
    work.transition(Archetype.TICKET, refined, "REFINED", SEEDER);
    work.transition(Archetype.TICKET, outside, "REFINED", SEEDER);
    walk(Archetype.TICKET, done, EntityStatus.DONE);
    for (String member : List.of(epic, refined, reported, done)) {
      campaigns.addMember(campaign, member, null, false, SEEDER);
    }
    work.transition(Archetype.CAMPAIGN, campaign, "REFINED", SEEDER);
    return new Setup(
        params(
            "campaignId", campaign,
            "campaignQualifiedId", qualified(project, campaign),
            "doneTicketId", done,
            "outsideTicketId", outside,
            "projectId", project.id,
            "refinedEpicId", epic,
            "refinedTicketId", refined,
            "reportedTicketId", reported),
        List.of(token));
  }

  /**
   * A REFINED epic that is a member of two REFINED campaigns, and nothing else. Recorded as two
   * states with the same seed: {@link #AN_EPIC_IN_TWO_CAMPAIGNS} for the entity list and the first
   * campaign, {@link #THE_SECOND_CAMPAIGN_OF_AN_EPIC_IN_TWO_CAMPAIGNS} for the second campaign (a
   * state records one answer per operation).
   *
   * <p>Both states have the same params, which name every entity, so the freezer gives each entity
   * the same id in all three answers and a consumer can join both campaigns' members to the list.
   */
  private Setup anEpicInTwoCampaigns() {
    String token = token();
    Project project = project(token, AN_EPIC_IN_TWO_CAMPAIGNS);
    String first = work.createCampaign(project.id, "First campaign", "Seeded work.", SEEDER).id;
    String second = work.createCampaign(project.id, "Second campaign", "Seeded work.", SEEDER).id;
    String epic =
        create(Archetype.EPIC, project, EntityWrite.epic("Epic in two campaigns", "Seeded.").withAcceptanceCriteria(TestCriteria.CRITERIA));
    work.transition(Archetype.EPIC, epic, "REFINED", SEEDER);
    for (String campaign : List.of(first, second)) {
      campaigns.addMember(campaign, epic, null, false, SEEDER);
      work.transition(Archetype.CAMPAIGN, campaign, "REFINED", SEEDER);
    }
    return new Setup(
        params(
            "epicId", epic,
            "firstCampaignId", first,
            "firstCampaignQualifiedId", qualified(project, first),
            "projectId", project.id,
            "secondCampaignId", second,
            "secondCampaignQualifiedId", qualified(project, second)),
        List.of(token));
  }

  private static Map<String, String> inDetail() {
    Map<String, String> out = new LinkedHashMap<>();
    out.put(AN_EPIC_IN_DETAIL, "epicId");
    out.put(A_FEATURE_IN_DETAIL, "featureId");
    out.put(A_TASK_IN_DETAIL, "taskId");
    out.put(A_BUG_TICKET_IN_DETAIL, "bugTicketId");
    out.put(AN_IMPROVEMENT_TICKET_IN_DETAIL, "improvementTicketId");
    out.put(A_MAINTENANCE_TICKET_IN_DETAIL, "maintenanceTicketId");
    out.put(A_CAMPAIGN_IN_DETAIL, "campaignId");
    return Collections.unmodifiableMap(out);
  }

  /** A person on the threads, and an agent. */
  private static final String PERSON = "dana.weber";

  private static final String AGENT = "qits-agent";

  /** A 1x1 PNG: the figure's bytes. The content route serves them; no golden master holds them. */
  private static final byte[] FIGURE_PNG =
      java.util.Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

  /**
   * <b>One project's work, rich enough for the detail page of every archetype</b> (epic qits-112).
   * The seven detail states seed this same project and differ only in {@code focus}, the param
   * whose entity the page shows; its qualified id is the {@code qualifiedId} param. Every state
   * returns every entity's id, so all of them freeze each entity to the same id.
   *
   * <ul>
   *   <li>A campaign, REFINED, with a Markdown description and five members in order: a VERIFIED
   *       epic, the IMPLEMENTING epic, the blocked bug, a DONE bug and the REFINED improvement — the
   *       last waiting on the epic being VERIFIED and on an approval.
   *   <li>An IMPLEMENTING epic (Markdown description with headings, a list and code; three
   *       comments; a dossier of three pages, one inlining a figure). Its first feature is
   *       IMPLEMENTED with both tasks IMPLEMENTED; its second depends on the first, is IMPLEMENTING,
   *       and holds one IMPLEMENTING task and one still REFINED. Each holds its own status
   *       (qits-763), moved by the marker doors. Each task names a repository; the second task of
   *       each feature depends on the first.
   *   <li>A BUG ticket, IMPLEMENTING, assigned and blocked (the reason lands on its thread), with a
   *       dossier of two pages. An IMPROVEMENT ticket, REFINED, with an empty dossier. A
   *       MAINTENANCE ticket, REPORTED, with one page and no comments.
   * </ul>
   */
  private Setup workInDetail(String state, String focus) {
    resetDispatchPorts();
    String token = token();
    Project project = project(token, state);
    String service =
        projectService.createRepository(project.id, null, "billing-service", null, "billing")
            .repository()
            .id;
    String frontend =
        projectService.createRepository(project.id, null, "billing-frontend", null, "billing")
            .repository()
            .id;

    String campaign =
        work.createCampaign(
                project.id,
                "Invoicing for the Q4 close",
                """
                Everything accounting needs before the books close on 15 December.

                ## Done when
                - the accountants can export every invoice of a quarter
                - tax rates are right for every country we bill
                - no open bug touches an invoice total
                """,
                PERSON)
            .id;

    String epic =
        create(
            Archetype.EPIC,
            project,
            EntityWrite.epic(
                "Export invoices for the accountants",
                """
                ## Why
                Accounting copies every invoice into their tool by hand, about 400 a quarter.

                ## What
                - a **CSV export** of a date range, in the column order the tool imports
                - a **PDF export** of one invoice, for the audit folder

                ## Example
                ```
                GET /billing/api/invoices/export?from=2026-10-01&to=2026-12-31&format=csv
                ```
                """).withAcceptanceCriteria(TestCriteria.CRITERIA));
    String csv =
        node(
            Archetype.FEATURE,
            epic,
            EntityWrite.feature(
                "CSV export",
                "Stream a date range of invoices as CSV, one row per line item.",
                null));
    String stream =
        node(
            Archetype.TASK,
            csv,
            EntityWrite.task(
                service,
                "Stream invoices as CSV",
                "Add `GET /invoices/export?format=csv`. Stream the rows; do not load the range.",
                null));
    String button =
        node(
            Archetype.TASK,
            csv,
            EntityWrite.task(
                frontend,
                "Download button on the invoice list",
                "A button above the list that downloads the filtered range as CSV.",
                stream));
    String pdf =
        node(
            Archetype.FEATURE,
            epic,
            EntityWrite.feature(
                "PDF export",
                "Render one invoice as an A4 PDF, in the same layout as the printed one.",
                csv));
    String render =
        node(
            Archetype.TASK,
            pdf,
            EntityWrite.task(
                service,
                "Render one invoice as PDF",
                "Render server-side; embed the fonts so the archive stays readable.",
                null));
    String preview =
        node(
            Archetype.TASK,
            pdf,
            EntityWrite.task(
                frontend,
                "Preview the PDF before download",
                "Show the rendered PDF in a dialog, with a download button.",
                render));
    dossierPage(
        epic,
        null,
        "Scope",
        """
        ## In scope
        - invoices and credit notes
        - one date range per export

        ## Out of scope
        - exports of payments; the bank statement covers them
        """);
    String figure = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                dossierAssets.copyFrom(
                    epic,
                    figure,
                    eu.wohlben.qits.entities.entity.DossierAsset.Kind.IMAGE,
                    FIGURE_PNG,
                    "image/png",
                    "Export data flow"));
    String flowPage =
        dossierPage(
            epic,
            null,
            "Data flow",
            "The export reads the invoices once and streams them out:\n\n"
                + "![Export data flow]("
                + eu.wohlben.qits.entities.control.DossierAssetService.contentUrl(epic, figure)
                + ")\n\n"
                + "1. the list sends its filter\n"
                + "2. the service streams the rows\n"
                + "3. the browser saves the file\n");
    dossierPage(
        epic,
        null,
        "Rollout",
        "Ship the CSV export first. The PDF export follows once the accountants confirm the"
            + " columns.");

    String taxes =
        create(
            Archetype.EPIC,
            project,
            EntityWrite.epic("Tax rates per country", "Bill each country at its own VAT rate.").withAcceptanceCriteria(TestCriteria.CRITERIA));

    String bug =
        create(
            Archetype.TICKET,
            project,
            EntityWrite.ticket(
                "Invoice totals are off by one cent",
                "An accountant found three invoices whose total is one cent above the sum of their"
                    + " lines.",
                """
                ## Cause
                Each line is rounded before the sum, so rounding errors add up.

                ## Fix
                Sum the exact amounts and round **once**, half up:
                ```java
                total = lines.stream().map(Line::amount).reduce(ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
                ```
                """,
                "BUG",
                PERSON).withAcceptanceCriteria(TestCriteria.CRITERIA));
    dossierPage(
        null,
        bug,
        "Reproduction",
        """
        1. Create an invoice with three lines of 0.335 EUR each.
        2. Read its total: **1.02 EUR**. The lines add up to 1.005, which rounds once to 1.01.
        """);
    dossierPage(
        null,
        bug,
        "Affected invoices",
        "| Invoice | Total | Expected |\n|---|---|---|\n| 2026-0412 | 1.01 | 1.00 |\n"
            + "| 2026-0418 | 20.04 | 20.03 |\n| 2026-0533 | 7.11 | 7.10 |\n");

    String improvement =
        create(
            Archetype.TICKET,
            project,
            EntityWrite.ticket(
                "Remember the last export format",
                "Accountants pick CSV every time; the list forgets it.",
                "Keep the chosen export format per user and preselect it next time.",
                "IMPROVEMENT",
                null).withAcceptanceCriteria(TestCriteria.CRITERIA));

    String maintenance =
        create(
            Archetype.TICKET,
            project,
            EntityWrite.ticket(
                "Release request for billing-service is stuck",
                "The release request for billing-service has waited on its gates for 6 hours.",
                null,
                "MAINTENANCE",
                null).withAcceptanceCriteria(TestCriteria.CRITERIA));
    dossierPage(
        null,
        maintenance,
        "What the platform saw",
        "The fold built green, and the deployment never reported the version live.\n\n"
            + "- requested: `2026.1003.90000`\n- last gate: `deployment`\n");

    String creditNotes =
        create(
            Archetype.TICKET,
            project,
            EntityWrite.ticket(
                "Credit notes show the wrong sign",
                "A credit note of 50 EUR prints as 50 EUR, not -50 EUR.",
                "Print credit note amounts negative.",
                "BUG",
                null).withAcceptanceCriteria(TestCriteria.CRITERIA));

    // Work: the epic's markers, then every status. Task markers move only while the epic is
    // READY_FOR_DEV or IMPLEMENTING; marking a task implementing moves the epic to IMPLEMENTING.
    work.transition(Archetype.EPIC, epic, "REFINED", SEEDER);
    work.transition(Archetype.EPIC, epic, "READY_FOR_DEV", Mover.person(SEEDER));
    work.markImplementing(stream, AGENT);
    implemented(stream);
    work.markImplementing(button, AGENT);
    implemented(button);
    implemented(csv);
    work.markImplementing(render, AGENT);
    walk(Archetype.EPIC, taxes, EntityStatus.VERIFIED);
    walk(Archetype.TICKET, bug, EntityStatus.IMPLEMENTING);
    work.transition(Archetype.TICKET, improvement, "REFINED", SEEDER);
    walk(Archetype.TICKET, creditNotes, EntityStatus.DONE);

    List<String> members = new ArrayList<>();
    for (String member : List.of(taxes, epic, bug, creditNotes, improvement)) {
      members.add(campaigns.addMember(campaign, member, null, false, PERSON).membership().id);
    }
    campaigns.setCondition(
        campaign,
        members.get(4),
        List.of(
            new eu.wohlben.qits.entities.campaign.CampaignService.GroupSpec(
                List.of(
                    new eu.wohlben.qits.entities.campaign.CampaignService.CriterionSpec(
                        null, "ENTITY_STATUS", Map.of("entityId", epic, "status", "VERIFIED")),
                    new eu.wohlben.qits.entities.campaign.CampaignService.CriterionSpec(
                        null, "APPROVAL", null)))),
        PERSON);
    work.transition(Archetype.CAMPAIGN, campaign, "REFINED", SEEDER);

    // Threads, oldest first. The block's reason lands on the bug's thread as the last remark.
    comments.addComment(
        epic, "Accounting needs the export before the Q4 close on 15 December.", PERSON);
    comments.addComment(
        epic,
        "Refined into two features. CSV comes first: the accountants' tool imports it today."
            + " The PDF export depends on it for the column order.",
        AGENT);
    comments.addComment(epic, "Agreed. Start with the CSV export.", PERSON);
    comments.addComment(
        pdf, "Started on the renderer. The preview waits for it.", AGENT);
    comments.addComment(
        button, "Implemented and released in billing-frontend `2026.1001.140000`.", AGENT);
    comments.addComment(
        bug, "Three invoices from October are affected; see the dossier.", PERSON);
    comments.addComment(
        bug, "The fix is ready, but the expected totals need finance's sign-off.", AGENT);
    blocks.apply(
        work.find(bug),
        true,
        "Waiting for finance to confirm the expected totals of the affected invoices.",
        AGENT);
    comments.addComment(improvement, "Per user, not per browser, please.", PERSON);
    comments.addComment(
        campaign, "Tax rates are verified. The export is next, then the open bug.", PERSON);

    Map<String, String> ids = new TreeMap<>();
    ids.put("campaignId", campaign);
    ids.put("campaignQualifiedId", qualified(project, campaign));
    ids.put("epicId", epic);
    ids.put("featureId", pdf);
    ids.put("taskId", button);
    ids.put("bugTicketId", bug);
    ids.put("improvementTicketId", improvement);
    ids.put("maintenanceTicketId", maintenance);
    ids.put("projectId", project.id);
    // The rest are named so every answer freezes them alike: a consumer joins a campaign's members,
    // a task's dependsOn and a page's figure to the other answers by id.
    ids.put("taxesEpicId", taxes);
    ids.put("creditNoteTicketId", creditNotes);
    ids.put("csvFeatureId", csv);
    ids.put("csvTaskId", stream);
    ids.put("pdfTaskId", render);
    ids.put("previewTaskId", preview);
    ids.put("serviceRepositoryId", service);
    ids.put("frontendRepositoryId", frontend);
    ids.put("figureAssetId", figure);
    ids.put("figurePageId", flowPage);
    ids.put(
        "qualifiedId",
        eu.wohlben.qits.projects.api.QualifiedEntityIds.render(
            project.slug, work.find(ids.get(focus)).number));
    return new Setup(Collections.unmodifiableMap(ids), List.of(token));
  }

  /** A dossier page on an epic or a ticket, as the person on the threads writes it. */
  private String dossierPage(String epicId, String ticketId, String title, String body) {
    var owner =
        epicId != null
            ? eu.wohlben.qits.entities.entity.DossierOwner.epic(epicId)
            : eu.wohlben.qits.entities.entity.DossierOwner.ticket(ticketId);
    return dossier.create(owner, title, body, PERSON).id;
  }

  private String repository(Project project, String name) {
    return projectService.createRepository(project.id, null, name, null).repository().id;
  }

  private String node(Archetype archetype, String parent, EntityWrite write) {
    return work.create(archetype, parent, write, SEEDER).entity().id;
  }

  /**
   * Moves an epic, a ticket or a task forward one step at a time along the walk until it is {@code
   * target}, through IMPLEMENTING and VERIFYING (qits-749) rather than over them — the way the
   * platform itself moves work, so a seeded VERIFIED row got there the way a real one does.
   */
  private void walk(Archetype archetype, String id, EntityStatus target) {
    List<EntityStatus> walk = EntityStateMachine.walk();
    // Read in a transaction of its own: outside one the seeder keeps one persistence context, and a
    // row it read before a cascade or a marker moved it answers the old status. Before qits-887 a
    // stale REFINED walked a marked task IMPLEMENTED → IMPLEMENTING and back by accident; now that
    // step is READY_FOR_DEV, which no IMPLEMENTED row may move to.
    String current = QuarkusTransaction.requiringNew().call(() -> work.find(id).status);
    int from = walk.indexOf(EntityStatus.valueOf(current));
    for (int step = from + 1; step <= walk.indexOf(target); step++) {
      work.transition(archetype, id, walk.get(step).name(), Mover.person(SEEDER));
    }
  }

  /** Marks a feature or task implemented now, as an edit of its marker. */
  private void implemented(String id) {
    work.update(work.find(id).archetype, id, EntityWrite.implementedAt(Instant.now()), SEEDER);
  }

  private Setup aProjectWithNoWork() {
    String token = token();
    Project project = project(token, A_PROJECT_WITH_NO_WORK);
    return new Setup(params("projectId", project.id), List.of(token));
  }

  /**
   * Two repositories and six release requests, one per situation the release menu shows: waiting
   * on its gates (PENDING, CI not answered), folded and ready (READY, CI passed), rejected by CI
   * (REJECTED, CI failed), unable to fold (CONFLICTED), released and waiting on its deployment
   * (RELEASED, still open work), and one FINALIZED — which the default list carries as its tail but
   * which is no longer pending. The CI answers are qits-ci's verdicts in the build-status ledger, at
   * the request's folded sha. The rows are written straight to the
   * table, as {@code ProjectReleaseRequestsTest} does for finished ones: driving them through the
   * gates would make the fixture depend on the state machine's timing. Each moved at its own fixed
   * minute, so the list's order (most recently moved first) is fixed too.
   */
  private Setup aProjectWithPendingReleaseRequests() {
    String token = token();
    Project project = project(token, A_PROJECT_WITH_PENDING_RELEASE_REQUESTS);
    String service =
        projectService.createRepository(project.id, null, "contract-service", null).repository().id;
    String frontend =
        projectService.createRepository(project.id, null, "contract-frontend", null).repository().id;
    request(project, service, "contract-service", "Finalized release", State.FINALIZED, 1, null);
    request(project, frontend, "contract-frontend", "Cannot fold", State.CONFLICTED, 2, null);
    request(project, frontend, "contract-frontend", "Rejected by CI", State.REJECTED, 3, "FAILURE");
    request(project, service, "contract-service", "Folded and ready", State.READY, 4, "SUCCESS");
    request(project, frontend, "contract-frontend", "Released, deploying", State.RELEASED, 5, null);
    request(project, service, "contract-service", "Waiting on its gates", State.PENDING, 6, null);
    return new Setup(params("projectId", project.id), List.of(token));
  }

  /** A project with a repository and no release request: the menu's empty list. */
  private Setup aProjectWithNoReleaseRequests() {
    String token = token();
    Project project = project(token, A_PROJECT_WITH_NO_RELEASE_REQUESTS);
    projectService.createRepository(project.id, null, "contract-service", null);
    return new Setup(params("projectId", project.id), List.of(token));
  }

  /**
   * Four repositories in two components, the way a wrapper groups them ({@code
   * components/<component>/<name>}): {@code contract} holds a service and a frontend, {@code
   * billing} a daemon and a javalib. Each has a forge twin but the javalib, and their last backups
   * differ — SUCCEEDED, FAILED (with a detail), AUTH_REQUIRED, and never — so a reader sees every
   * backup state at once. The outcomes are written straight to the rows at a fixed time: a real
   * backup is a push to a forge the suite does not have.
   */
  private Setup aProjectWithRepositoriesInComponents() {
    String token = token();
    Project project = project(token, A_PROJECT_WITH_REPOSITORIES_IN_COMPONENTS);
    backedUp(project, "contract-service", "contract", BackupOutcome.SUCCEEDED, null);
    backedUp(
        project, "contract-frontend", "contract", BackupOutcome.FAILED, "the forge refused the push");
    backedUp(project, "billing-daemon", "billing", BackupOutcome.AUTH_REQUIRED, null);
    projectService.createRepository(project.id, null, "billing-javalib", null, "billing");
    return new Setup(params("projectId", project.id), List.of(token));
  }

  /** Creates {@code name} under {@code component}, with a forge twin and a last backup. */
  private void backedUp(
      Project project, String name, String component, BackupOutcome outcome, String detail) {
    String id =
        projectService.createRepository(project.id, null, name, null, component).repository().id;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Repository row = Repository.findById(id);
              row.url = "https://forge.example.test/contract/" + name + ".git";
              row.lastBackupOutcome = outcome;
              row.lastBackupAt = SEEDED_AT;
              row.lastBackupDetail = detail;
            });
  }

  // --- release requests ------------------------------------------------------------------------

  /** The release every seeded fold is diffed against: the tag on both repositories' first commit. */
  private static final String BASE_VERSION = "2026.101.100000";

  /** The version a released seeded request carries. */
  private static final String RELEASED_VERSION = "2026.101.100500";

  /** Where the suite pins contract-service — the house grammar, components/<component>/<name>. */
  static final String ESTATE_SUBMODULE = "components/contract/contract-service";

  /** The suite's settings: a person approves every release of it. */
  private static final String MANUAL_REVIEW = "manual-review: true\n";

  /** The deployment declaration: its presence is the DEPLOYMENT gate and the deploy phase. */
  private static final String DEPLOYMENTS = "services:\n  contract-service: {}\n";

  /** The contract-service commits every detail state seeds, oldest first. */
  private record ServiceShas(String base, String export, String readme, String rounding, String fold) {}

  /**
   * What every release-request detail state seeds: a project with two real repositories on the git
   * host, both with the same history on every run.
   *
   * <ul>
   *   <li>{@code contract-service} — {@code main} released as {@value #BASE_VERSION}, then {@code
   *       feature/export} (two commits: a new file and a README change) and {@code fix/rounding}
   *       (one change), folded by one octopus merge on {@code release/fold}. It deploys.
   *   <li>{@code contract-suite-app} — an app pinning contract-service as a submodule at {@value
   *       #ESTATE_SUBMODULE}: {@code main} pins the service's base, {@code bump/contract-service}
   *       moves the pin to the service's fold, {@code docs/approval} changes the README, and the
   *       two are folded the same way. A person approves its releases ({@code manual-review}).
   * </ul>
   */
  private record ReleaseFixture(
      Project project,
      String token,
      String serviceId,
      String estateId,
      ServiceShas service,
      String estateFold) {}

  private ReleaseFixture releaseFixture(String state) {
    String token = token();
    Project project = project(token, state);
    String serviceId =
        projectService.createRepository(project.id, null, "contract-service", null, "contract")
            .repository()
            .id;
    String estateId =
        projectService.createRepository(project.id, null, "contract-suite-app", null).repository().id;
    seededRepos.add(serviceId);
    seededRepos.add(estateId);
    ServiceShas service;
    String estateFold;
    try {
      service =
          state.equals(A_REFOLDED_RELEASE_REQUEST)
              ? seedRefoldedService(serviceId)
              : seedService(serviceId);
      estateFold = seedEstate(estateId, service.base(), service.fold());
    } catch (Exception e) {
      throw new IllegalStateException("Could not seed the release fixture's repositories", e);
    }
    eu.wohlben.qits.projects.releasehost.RecordingReleaseGitHost host = releaseGitHost.get();
    host.gatedTreeFor(estateId, "refs/heads/main", Map.of(SETTINGS_FILE, MANUAL_REVIEW));
    host.gatedTreeFor(serviceId, "refs/heads/main", Map.of(DEPLOYMENTS_FILE, DEPLOYMENTS));
    // A gate re-asked by a door (approve, decline, waive) reads whether CI runs are in flight; the
    // probe is a shared fake, so it is told here rather than left to whichever test ran last.
    activeBuilds.get().answer(java.util.Optional.of(0));
    return new ReleaseFixture(project, token, serviceId, estateId, service, estateFold);
  }

  private ServiceShas seedService(String repoId) throws Exception {
    SeededGit repo = SeededGit.init(gitWork, gitHost.fetchUrl(repoId));
    String readme = "# contract-service\n\nInvoices for the contract project.\n";
    repo.write(Map.of("README.md", readme, "src/invoice.txt", "Totals round half up.\n"));
    String base = repo.commit(1, "Import the invoice service");
    repo.tag(BASE_VERSION);
    repo.branch("feature/export", "main");
    repo.write(Map.of("src/export.txt", "Export a quarter of invoices as CSV.\n"));
    String export = repo.commit(2, "Add the CSV export");
    repo.write(Map.of("README.md", readme + "\nExport a quarter as CSV: see src/export.txt.\n"));
    String readmeSha = repo.commit(3, "Describe the export in the README");
    repo.branch("fix/rounding", "main");
    repo.write(Map.of("src/invoice.txt", "Totals round half to even.\n"));
    String rounding = repo.commit(4, "Round totals half to even");
    repo.branch("release/fold", "main");
    String fold =
        repo.merge(5, "Release the CSV export and the rounding fix", "feature/export", "fix/rounding");
    repo.push("main", "feature/export", "fix/rounding", "release/fold");
    goCold(repoId);
    return new ServiceShas(base, export, readmeSha, rounding, fold);
  }

  /**
   * contract-service as a re-folded request leaves it: the backing branch is a chain of fold
   * merges, the way qits-githost builds it when sources move after the first fold. {@code
   * release/fold} starts as a merge of the two branches, then takes fix/rounding's second commit,
   * then feature/export's second commit — three fold merges, each with the previous fold as its
   * first parent. In the answer: {@code export} and {@code readme} are feature/export's two commits,
   * {@code rounding} is fix/rounding's tip and {@code fold} the newest fold.
   */
  private ServiceShas seedRefoldedService(String repoId) throws Exception {
    SeededGit repo = SeededGit.init(gitWork, gitHost.fetchUrl(repoId));
    String readme = "# contract-service\n\nInvoices for the contract project.\n";
    repo.write(Map.of("README.md", readme, "src/invoice.txt", "Totals round half up.\n"));
    String base = repo.commit(1, "Import the invoice service");
    repo.tag(BASE_VERSION);
    repo.branch("feature/export", "main");
    repo.write(Map.of("src/export.txt", "Export a quarter of invoices as CSV.\n"));
    String export = repo.commit(2, "Add the CSV export");
    repo.branch("fix/rounding", "main");
    repo.write(Map.of("src/invoice.txt", "Totals round half to even.\n"));
    repo.commit(3, "Round totals half to even");
    repo.branch("release/fold", "feature/export");
    String summary = "Release the CSV export and the rounding fix";
    repo.merge(4, summary, "fix/rounding");
    repo.checkout("fix/rounding");
    repo.write(
        Map.of("src/invoice.txt", "Totals round half to even, negative totals too.\n"));
    String rounding = repo.commit(5, "Round negative totals too");
    repo.checkout("release/fold");
    repo.merge(6, summary, "fix/rounding");
    repo.checkout("feature/export");
    repo.write(Map.of("README.md", readme + "\nExport a quarter as CSV: see src/export.txt.\n"));
    String readmeSha = repo.commit(7, "Describe the export in the README");
    repo.checkout("release/fold");
    String fold = repo.merge(8, summary, "feature/export");
    repo.push("main", "feature/export", "fix/rounding", "release/fold");
    goCold(repoId);
    return new ServiceShas(base, export, readmeSha, rounding, fold);
  }

  private String seedEstate(String repoId, String servicePin, String serviceFold) throws Exception {
    SeededGit repo = SeededGit.init(gitWork, gitHost.fetchUrl(repoId));
    String readme = "# contract-suite-app\n\nThe contract suite: one pin per component.\n";
    repo.write(
        Map.of(
            ".gitmodules",
            "[submodule \"contract-service\"]\n\tpath = "
                + ESTATE_SUBMODULE
                + "\n\turl = ../contract-service.git\n\tbranch = main\n\tignore = all\n"
                + "\tupdate = merge\n",
            "README.md",
            readme));
    repo.gitlink(ESTATE_SUBMODULE, servicePin);
    repo.commit(1, "Declare the suite");
    repo.tag(BASE_VERSION);
    repo.branch("bump/contract-service", "main");
    repo.gitlink(ESTATE_SUBMODULE, serviceFold);
    repo.commit(2, "Bump contract-service to its export release");
    repo.branch("docs/approval", "main");
    repo.write(Map.of("README.md", readme + "\nA person approves every release of the suite.\n"));
    repo.commit(3, "Say who approves a suite release");
    repo.branch("release/fold", "main");
    String fold =
        repo.merge(
            4, "Release the export across the suite", "bump/contract-service", "docs/approval");
    repo.push("main", "bump/contract-service", "docs/approval", "release/fold");
    goCold(repoId);
    return fold;
  }

  /** Drops a repository's mirror, so the next read clones what was just pushed. */
  private void goCold(String repoId) throws java.io.IOException {
    java.nio.file.Path mirror = mirrors.of(repoId).gitDir();
    if (!java.nio.file.Files.exists(mirror)) {
      return;
    }
    try (var paths = java.nio.file.Files.walk(mirror)) {
      for (java.nio.file.Path path :
          paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        java.nio.file.Files.deleteIfExists(path);
      }
    }
  }

  /**
   * One release request in detail, by the variant the state names — see {@link
   * #RELEASE_REQUEST_DETAILS}. The params are the project, the request's repository, the request
   * and, where it has one, its fold ({@code mergedSha}): what the detail page's route and its
   * decisions carry.
   */
  private Setup releaseRequestInDetail(String state) {
    ReleaseFixture f = releaseFixture(state);
    Project project = f.project();
    String estate = f.estateId();
    String service = f.serviceId();
    String estateFold = f.estateFold();
    String serviceFold = f.service().fold();
    String estateSummary = "Release the export across the suite";
    String serviceSummary = "Release the CSV export and the rounding fix";
    String repoId;
    String fold;
    String id;
    switch (state) {
      case A_RELEASE_REQUEST_AWAITING_APPROVAL -> {
        repoId = estate;
        fold = estateFold;
        id =
            releaseRow(
                project, estate, "contract-suite-app", estateSummary, State.PENDING, 10, fold,
                row -> row.detail = "Waiting for a person to approve " + shortSha(fold));
        estateSources(id);
        verdict(project, estate, "contract-suite-app", id, fold, "SUCCESS", 12);
        phaseRun(id, estate, "RELEASE_REQUEST", "SUCCESS", 11, 12);
        automationsFresh(id, fold);
      }
      case A_RELEASE_REQUEST_AWAITING_APPROVAL_WHILE_ITS_BUILD_RUNS -> {
        repoId = estate;
        fold = estateFold;
        id =
            releaseRow(
                project, estate, "contract-suite-app", estateSummary, State.PENDING, 10, fold,
                row -> row.detail = "Waiting for a CI verdict for " + shortSha(fold));
        estateSources(id);
        phaseRun(id, estate, "RELEASE_REQUEST", "RUNNING", 11, null);
        automationsFresh(id, fold);
      }
      case AN_APPROVED_RELEASE_REQUEST -> {
        repoId = estate;
        fold = estateFold;
        id = releaseRow(project, estate, "contract-suite-app", estateSummary, State.READY, 10, fold, row -> {});
        estateSources(id);
        verdict(project, estate, "contract-suite-app", id, fold, "SUCCESS", 12);
        phaseRun(id, estate, "RELEASE_REQUEST", "SUCCESS", 11, 12);
        automationsFresh(id, fold);
        decision(
            id, fold, eu.wohlben.qits.projects.entity.ReleaseRequestApproval.Decision.APPROVED,
            "Read the diff: the pin moves to the export release.", 13);
      }
      case A_DECLINED_RELEASE_REQUEST -> {
        repoId = estate;
        fold = estateFold;
        String note = "The README promises an approval policy nobody has signed off yet.";
        id =
            releaseRow(
                project, estate, "contract-suite-app", estateSummary, State.REJECTED, 10, fold,
                row -> row.detail = "Declined by " + PERSON + ": " + note);
        estateSources(id);
        verdict(project, estate, "contract-suite-app", id, fold, "SUCCESS", 12);
        phaseRun(id, estate, "RELEASE_REQUEST", "SUCCESS", 11, 12);
        automationsFresh(id, fold);
        decision(
            id, fold, eu.wohlben.qits.projects.entity.ReleaseRequestApproval.Decision.DECLINED,
            note, 13);
      }
      case A_RELEASED_RELEASE_REQUEST, A_RELEASE_REQUEST_WHOSE_PUBLISH_FAILED -> {
        boolean published = state.equals(A_RELEASED_RELEASE_REQUEST);
        repoId = service;
        fold = serviceFold;
        id =
            releaseRow(
                project, service, "contract-service", serviceSummary, State.RELEASED, 10, fold,
                row -> row.version = RELEASED_VERSION);
        serviceSources(id);
        verdict(project, service, "contract-service", id, fold, "SUCCESS", 12);
        phaseRun(id, service, "RELEASE_REQUEST", "SUCCESS", 11, 12);
        String publishRun =
            phaseRun(id, service, "RELEASE", published ? "SUCCESS" : "FAILED", 14, 15);
        releasedTag(
            service, id, fold, publishRun,
            published
                ? eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge.PublishState.PASSED
                : eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge.PublishState.FAILED,
            published
                ? null
                : "The release run failed publishing the npm package: the registry refused the"
                    + " upload.");
        // What the tag declares and what qits-ci decided to publish at it.
        releaseGitHost
            .get()
            .treeFor(
                service,
                "refs/tags/" + RELEASED_VERSION,
                Map.of(
                    eu.wohlben.qits.projects.releasehost.RecordingReleaseGitHost.RELEASE_CONFIG,
                    eu.wohlben.qits.projects.releasehost.RecordingReleaseGitHost
                        .GATING_RELEASE_CONFIG,
                    DEPLOYMENTS_FILE,
                    DEPLOYMENTS));
        fakesTouched = true;
        if (published) {
          releaseDecisions
              .get()
              .decisions(
                  new eu.wohlben.qits.projects.control.ReleaseDecisions.Decision(
                      "maven", "eu.wohlben.qits:contract-service", "published", null),
                  new eu.wohlben.qits.projects.control.ReleaseDecisions.Decision(
                      "npm", "@contract/service-golden-masters", "published", null),
                  new eu.wohlben.qits.projects.control.ReleaseDecisions.Decision(
                      "maven", "eu.wohlben.qits:contract-service-pacts", "unchanged",
                      BASE_VERSION));
          deploymentRequests.get().answerStatus(UUID.randomUUID().toString(), "STARTING");
        } else {
          releaseDecisions
              .get()
              .decisions(
                  new eu.wohlben.qits.projects.control.ReleaseDecisions.Decision(
                      "maven", "eu.wohlben.qits:contract-service", "published", null),
                  new eu.wohlben.qits.projects.control.ReleaseDecisions.Decision(
                      "npm", "@contract/service-golden-masters", "pending", null));
          deploymentRequests.get().answerNothingYet();
        }
      }
      case A_RELEASE_REQUEST_REJECTED_BY_ITS_BUILD -> {
        repoId = service;
        fold = serviceFold;
        String run = UUID.randomUUID().toString();
        id =
            releaseRow(
                project, service, "contract-service", serviceSummary, State.REJECTED, 10, fold,
                row -> {
                  row.rejectingRunId = run;
                  row.detail = "Run " + run + " finished FAILURE for " + fold;
                });
        serviceSources(id);
        verdictOf(run, project, service, "contract-service", id, fold, "FAILURE", 12);
        phaseRunWithId(run, id, service, "RELEASE_REQUEST", "FAILED", 11, 12);
      }
      case A_RELEASE_REQUEST_HELD_BY_A_FAILED_AUTOMATION -> {
        repoId = estate;
        fold = estateFold;
        id =
            releaseRow(
                project, estate, "contract-suite-app", estateSummary, State.PENDING, 10, fold,
                row ->
                    row.detail =
                        "Waiting for automations at " + shortSha(fold) + ": Estate pins failed");
        estateSources(id);
        verdict(project, estate, "contract-suite-app", id, fold, "SUCCESS", 12);
        phaseRun(id, estate, "RELEASE_REQUEST", "SUCCESS", 11, 12);
        automationLedger.record(
            id,
            new eu.wohlben.qits.projects.control.AutomationLedger.Note(
                fold,
                eu.wohlben.qits.projects.control.AutomationLedger.State.FAILED,
                List.of(
                    automation(
                        "estate-pins", "Estate pins", "FAILED", fold,
                        "contract-service is pinned at a commit its main does not contain.",
                        new eu.wohlben.qits.projects.control.AutomationLedger.Failure(
                            2,
                            "registry.example.test/qits/maintenance:2026.101.90000",
                            1,
                            "error: contract-service: " + shortSha(serviceFold)
                                + " is not on main")),
                    automation("screenshot-baselines", "Screenshot baselines", "FRESH", fold, null, null)),
                "Estate pins failed",
                null));
      }
      case A_RELEASE_REQUEST_WITH_AN_AUTOMATION_THAT_DOES_NOT_APPLY -> {
        // One kind applies and runs; the other does not apply and says why. qits-maintenance
        // lists both, and the request waits for the one that applies.
        repoId = estate;
        fold = estateFold;
        id =
            releaseRow(
                project, estate, "contract-suite-app", estateSummary, State.PENDING, 10, fold,
                row ->
                    row.detail =
                        "Waiting for automations at " + shortSha(fold) + ": Estate pins running");
        estateSources(id);
        verdict(project, estate, "contract-suite-app", id, fold, "SUCCESS", 12);
        phaseRun(id, estate, "RELEASE_REQUEST", "SUCCESS", 11, 12);
        automationLedger.record(
            id,
            new eu.wohlben.qits.projects.control.AutomationLedger.Note(
                fold,
                eu.wohlben.qits.projects.control.AutomationLedger.State.PENDING,
                List.of(
                    automation("estate-pins", "Estate pins", "RUNNING", fold, null, null),
                    new eu.wohlben.qits.projects.control.AutomationLedger.Automation(
                        "screenshot-baselines",
                        "Screenshot baselines",
                        eu.wohlben.qits.projects.control.AutomationLedger.NOT_APPLICABLE,
                        "the fold carries no package.json",
                        List.of(),
                        null,
                        null,
                        null,
                        null)),
                null,
                null));
      }
      case A_WITHDRAWN_RELEASE_REQUEST -> {
        repoId = service;
        fold = serviceFold;
        id =
            releaseRow(
                project, service, "contract-service", serviceSummary, State.WITHDRAWN, 10, fold,
                row ->
                    row.detail =
                        "Withdrawn by " + PERSON + ": the export moves to next quarter's release");
        serviceSources(id);
        verdict(project, service, "contract-service", id, fold, "SUCCESS", 12);
        phaseRun(id, service, "RELEASE_REQUEST", "SUCCESS", 11, 12);
      }
      case A_CONFLICTED_RELEASE_REQUEST -> {
        repoId = service;
        fold = null;
        ServiceShas shas = f.service();
        id =
            releaseRow(
                project, service, "contract-service", serviceSummary, State.CONFLICTED, 10, null,
                row -> {
                  row.detail = "The sources cannot be folded: src/invoice.txt, README.md";
                  row.conflictDetail =
                      conflict(
                          row.backingBranch(),
                          new String[] {
                            "src/invoice.txt", "refs/heads/fix/rounding", shas.rounding(),
                            shas.base(), shas.export(), shas.rounding()
                          },
                          new String[] {
                            "README.md", "refs/heads/feature/export", shas.readme(),
                            shas.base(), null, shas.readme()
                          });
                });
        serviceSources(id);
      }
      case A_REFOLDED_RELEASE_REQUEST -> {
        repoId = service;
        fold = serviceFold;
        id =
            releaseRow(
                project, service, "contract-service", serviceSummary, State.READY, 10, fold,
                row -> {});
        serviceSources(id);
        verdict(project, service, "contract-service", id, fold, "SUCCESS", 12);
        phaseRun(id, service, "RELEASE_REQUEST", "SUCCESS", 11, 12);
      }
      default -> throw new IllegalArgumentException("No release request variant for " + state);
    }
    Map<String, String> params = new TreeMap<>();
    params.put("projectId", project.id);
    params.put("repositoryId", repoId);
    params.put("requestId", id);
    if (fold != null) {
      params.put("mergedSha", fold);
    }
    return new Setup(Collections.unmodifiableMap(params), List.of(f.token()));
  }

  /**
   * The release list across a project (the landing app's release-requests page): three
   * repositories and one request in each state the default list shows — waiting for a person
   * (with a BLOCKING branch on it), ready, rejected by CI, unable to fold, failed at execution,
   * released and finalized. Each moved at its own minute, so the order is fixed. Params name the
   * request awaiting approval, which the page can withdraw. No git is read: the shas are made up.
   */
  private Setup aProjectWithReleaseRequestsInEveryState() {
    String token = token();
    Project project = project(token, A_PROJECT_WITH_RELEASE_REQUESTS_IN_EVERY_STATE);
    String service =
        projectService.createRepository(project.id, null, "contract-service", null, "contract")
            .repository()
            .id;
    String frontend =
        projectService.createRepository(project.id, null, "contract-frontend", null, "contract")
            .repository()
            .id;
    String estate =
        projectService.createRepository(project.id, null, "contract-suite-app", null).repository().id;
    seededRepos.add(service);
    seededRepos.add(frontend);
    seededRepos.add(estate);
    releaseGitHost.get().gatedTreeFor(estate, "refs/heads/main", Map.of(SETTINGS_FILE, MANUAL_REVIEW));
    activeBuilds.get().answer(java.util.Optional.of(0));

    String finalized =
        releaseRow(
            project, service, "contract-service", "Release the invoice service", State.FINALIZED,
            1, madeUpSha(1), row -> row.version = "2026.101.100100");
    verdict(project, service, "contract-service", finalized, madeUpSha(1), "SUCCESS", 1);
    String released =
        releaseRow(
            project, frontend, "contract-frontend", "Release the invoice screens", State.RELEASED,
            2, madeUpSha(2), row -> row.version = "2026.101.100200");
    verdict(project, frontend, "contract-frontend", released, madeUpSha(2), "SUCCESS", 2);
    // The released one is deployed: its QA and publish runs are mirrored, its tag published and
    // went live, so the list answer draws its pipeline and every gate including the rollback one.
    releaseGitHost
        .get()
        .gatedTreeFor(frontend, "refs/heads/main", Map.of(DEPLOYMENTS_FILE, DEPLOYMENTS));
    phaseRun(released, frontend, "RELEASE_REQUEST", "SUCCESS", 2, 2);
    String publishRun = phaseRun(released, frontend, "RELEASE", "SUCCESS", 2, 3);
    liveTag(frontend, released, "2026.101.100200", madeUpSha(20), publishRun, 4);
    String failed =
        releaseRow(
            project, frontend, "contract-frontend", "Release the PDF download", State.FAILED, 3,
            madeUpSha(3),
            row -> {
              row.detail = "The release could not push its tag: the git host refused the push.";
              row.retryable = true;
            });
    verdict(project, frontend, "contract-frontend", failed, madeUpSha(3), "SUCCESS", 3);
    releaseRow(
        project, frontend, "contract-frontend", "Release the dark theme", State.CONFLICTED, 4, null,
        row -> {
          row.detail = "The sources cannot be folded: src/theme.css";
          row.conflictDetail =
              conflict(
                  row.backingBranch(),
                  new String[] {
                    "src/theme.css", "refs/heads/feature/dark-theme", madeUpSha(41),
                    madeUpSha(40), madeUpSha(42), madeUpSha(41)
                  });
        });
    String rejectingRun = UUID.randomUUID().toString();
    String rejected =
        releaseRow(
            project, service, "contract-service", "Release the CSV export", State.REJECTED, 5,
            madeUpSha(5),
            row -> {
              row.rejectingRunId = rejectingRun;
              row.detail = "Run " + rejectingRun + " finished FAILURE for " + madeUpSha(5);
            });
    verdictOf(rejectingRun, project, service, "contract-service", rejected, madeUpSha(5), "FAILURE", 5);
    String ready =
        releaseRow(
            project, service, "contract-service", "Release the rounding fix", State.READY, 6,
            madeUpSha(6), row -> {});
    verdict(project, service, "contract-service", ready, madeUpSha(6), "SUCCESS", 6);
    String waiting =
        releaseRow(
            project, estate, "contract-suite-app", "Release the export across the suite",
            State.PENDING, 7, madeUpSha(7),
            row -> row.detail = "Waiting for a person to approve " + shortSha(madeUpSha(7)));
    verdict(project, estate, "contract-suite-app", waiting, madeUpSha(7), "SUCCESS", 7);
    source(waiting, "main", eu.wohlben.qits.projects.entity.ReleasePriority.MEDIUM, 6);
    source(
        waiting, "bump/contract-service", eu.wohlben.qits.projects.entity.ReleasePriority.BLOCKING, 7);
    // Two of its three automations are fresh and one is still running: "Automations 2/3".
    automationLedger.record(
        waiting,
        new eu.wohlben.qits.projects.control.AutomationLedger.Note(
            madeUpSha(7),
            eu.wohlben.qits.projects.control.AutomationLedger.State.PENDING,
            List.of(
                automation("estate-pins", "Estate pins", "FRESH", madeUpSha(7), null, null),
                automation(
                    "screenshot-baselines", "Screenshot baselines", "FRESH", madeUpSha(7), null, null),
                automation(
                    "entity-diagram",
                    "Entity diagram",
                    "PENDING",
                    madeUpSha(7),
                    "Run in flight",
                    null)),
            null,
            null));
    return new Setup(
        params("projectId", project.id, "repositoryId", estate, "requestId", waiting),
        List.of(token));
  }

  /** A 40-hex sha no repository holds, for rows whose git is never read. */
  private static String madeUpSha(int n) {
    return String.format("%040x", n);
  }

  /** The house abbreviation ReleaseRequests uses in every sentence it writes. */
  private static String shortSha(String sha) {
    return sha.length() <= 10 ? sha : sha.substring(0, 10);
  }

  /**
   * A release request row written straight to the table, as {@link #request} does: driving it
   * through the gates would make the fixture depend on the state machine's timing.
   */
  private String releaseRow(
      Project project,
      String repoId,
      String repoName,
      String summary,
      State state,
      int minute,
      String mergedSha,
      java.util.function.Consumer<ReleaseRequest> more) {
    Instant when = SEEDED_AT.plusSeconds(60L * minute);
    String id =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest row = new ReleaseRequest();
                  row.id = UUID.randomUUID().toString();
                  row.repoId = repoId;
                  row.projectId = project.id;
                  row.repoName = repoName;
                  row.summary = summary;
                  row.requester = SEEDER;
                  row.state = state;
                  row.mergedSha = mergedSha;
                  row.createdAt = when;
                  row.armedAt = when;
                  row.updatedAt = when;
                  more.accept(row);
                  row.persist();
                  return row.id;
                });
    seededRequests.add(id);
    return id;
  }

  /** The suite request's branches: main, the bump (urgent) and the README change (not). */
  private void estateSources(String requestId) {
    source(requestId, "main", eu.wohlben.qits.projects.entity.ReleasePriority.MEDIUM, 9);
    source(
        requestId, "bump/contract-service", eu.wohlben.qits.projects.entity.ReleasePriority.HIGH, 10);
    source(requestId, "docs/approval", eu.wohlben.qits.projects.entity.ReleasePriority.LOW, 11);
  }

  /** The service request's branches: main, the export (urgent) and the rounding fix. */
  private void serviceSources(String requestId) {
    source(requestId, "main", eu.wohlben.qits.projects.entity.ReleasePriority.MEDIUM, 9);
    source(requestId, "feature/export", eu.wohlben.qits.projects.entity.ReleasePriority.HIGH, 10);
    source(requestId, "fix/rounding", eu.wohlben.qits.projects.entity.ReleasePriority.MEDIUM, 11);
  }

  private void source(
      String requestId, String branch, eu.wohlben.qits.projects.entity.ReleasePriority priority, int minute) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var source = new eu.wohlben.qits.projects.entity.ReleaseRequestSource();
              source.id = UUID.randomUUID().toString();
              source.requestId = requestId;
              source.kind = eu.wohlben.qits.projects.entity.ReleaseRequestSource.Kind.BRANCH;
              source.name = branch;
              source.priority = priority;
              source.addedAt = SEEDED_AT.plusSeconds(60L * minute);
              source.addedBy = SEEDER;
              source.persist();
            });
  }

  /** qits-ci's verdict about the fold, in the build-status ledger. */
  private void verdict(
      Project project, String repoId, String repoName, String requestId, String sha, String status, int minute) {
    verdictOf(UUID.randomUUID().toString(), project, repoId, repoName, requestId, sha, status, minute);
  }

  private void verdictOf(
      String runId,
      Project project,
      String repoId,
      String repoName,
      String requestId,
      String sha,
      String status,
      int minute) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CommitBuildStatus verdict = new CommitBuildStatus();
              verdict.runId = runId;
              verdict.repoId = repoId;
              verdict.projectId = project.id;
              verdict.repoName = repoName;
              verdict.branch = ReleaseRequest.backingBranchOf(requestId);
              verdict.commitSha = sha;
              verdict.status = status;
              verdict.finishedAt = SEEDED_AT.plusSeconds(60L * minute);
              verdict.persist();
            });
    seededVerdicts.add(runId);
  }

  /** A phase run as qits-ci reported it; {@code finished} null for one still running. */
  private String phaseRun(
      String requestId, String repoId, String phase, String status, int started, Integer finished) {
    return phaseRunWithId(
        UUID.randomUUID().toString(), requestId, repoId, phase, status, started, finished);
  }

  private String phaseRunWithId(
      String runId,
      String requestId,
      String repoId,
      String phase,
      String status,
      int started,
      Integer finished) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var run = new eu.wohlben.qits.projects.entity.ReleasePipelineRun();
              run.runId = runId;
              run.releaseRequestId = requestId;
              run.repoId = repoId;
              run.phase = phase;
              run.status = status;
              run.startedAt = SEEDED_AT.plusSeconds(60L * started);
              run.finishedAt = finished == null ? null : SEEDED_AT.plusSeconds(60L * finished);
              run.updatedAt = SEEDED_AT.plusSeconds(60L * (finished == null ? started : finished));
              run.persist();
            });
    return runId;
  }

  /** A person's decision about the fold. */
  private void decision(
      String requestId,
      String fold,
      eu.wohlben.qits.projects.entity.ReleaseRequestApproval.Decision decision,
      String note,
      int minute) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var approval = new eu.wohlben.qits.projects.entity.ReleaseRequestApproval();
              approval.id = UUID.randomUUID().toString();
              approval.requestId = requestId;
              approval.mergedSha = fold;
              approval.decision = decision;
              approval.actor = PERSON;
              approval.note = note;
              approval.decidedAt = SEEDED_AT.plusSeconds(60L * minute);
              approval.persist();
            });
  }

  /** The request's own released tag, not yet merged to main. */
  private void releasedTag(
      String repoId,
      String requestId,
      String releasedSha,
      String publishRunId,
      eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge.PublishState publish,
      String publishDetail) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var tag = new eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge();
              tag.id = UUID.randomUUID().toString();
              tag.repoId = repoId;
              tag.tagName = RELEASED_VERSION;
              tag.releasedSha = releasedSha;
              tag.releaseRequestId = requestId;
              tag.releasedAt = SEEDED_AT.plusSeconds(60L * 13);
              tag.publishState = publish;
              tag.publishDetail = publishDetail;
              tag.publishRunId = publishRunId;
              tag.persist();
            });
  }

  /** A released tag that published and went live, not yet merged to main. */
  private void liveTag(
      String repoId, String requestId, String version, String releasedSha, String publishRunId, int minute) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var tag = new eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge();
              tag.id = UUID.randomUUID().toString();
              tag.repoId = repoId;
              tag.tagName = version;
              tag.releasedSha = releasedSha;
              tag.releaseRequestId = requestId;
              tag.releasedAt = SEEDED_AT.plusSeconds(60L * minute);
              tag.publishState =
                  eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge.PublishState.PASSED;
              tag.publishRunId = publishRunId;
              tag.deploymentActiveAt = SEEDED_AT.plusSeconds(60L * (minute + 1));
              tag.persist();
            });
  }

  /** Every automation fresh at the fold: the gate passed. */
  private void automationsFresh(String requestId, String fold) {
    automationLedger.record(
        requestId,
        new eu.wohlben.qits.projects.control.AutomationLedger.Note(
            fold,
            eu.wohlben.qits.projects.control.AutomationLedger.State.FRESH,
            List.of(
                automation("estate-pins", "Estate pins", "FRESH", fold, null, null),
                automation("screenshot-baselines", "Screenshot baselines", "FRESH", fold, null, null)),
            null,
            null));
  }

  private static eu.wohlben.qits.projects.control.AutomationLedger.Automation automation(
      String kind,
      String label,
      String state,
      String fold,
      String detail,
      eu.wohlben.qits.projects.control.AutomationLedger.Failure failure) {
    return new eu.wohlben.qits.projects.control.AutomationLedger.Automation(
        kind,
        label,
        state,
        detail,
        List.of("bump-" + UUID.randomUUID()),
        "maintenance/" + kind,
        fold,
        SEEDED_AT.plusSeconds(60L * 12),
        failure);
  }

  /**
   * The conflict a CONFLICTED row carries, as the fold wrote it: per path {@code {path, head,
   * headSha, base, ours, theirs}}, every one a file conflict on content. A null side is a deletion.
   */
  private static String conflict(String target, String[]... paths) {
    var node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    node.put("target", target);
    var conflicts = node.putArray("conflicts");
    for (String[] p : paths) {
      var entry = conflicts.addObject();
      entry.put("path", p[0]);
      entry.put("head", p[1]);
      entry.put("headSha", p[2]);
      entry.put("reason", "content");
      entry.put("kind", "file");
      entry.put("base", p[3]);
      entry.put("ours", p[4]);
      entry.put("theirs", p[5]);
    }
    return node.toString();
  }

  private void request(
      Project project,
      String repoId,
      String repoName,
      String summary,
      ReleaseRequest.State state,
      int minute,
      String ciVerdict) {
    Instant when = SEEDED_AT.plusSeconds(60L * minute);
    // A folded request has a sha; qits-ci's verdict for it lives in the ledger, keyed by that sha.
    String sha = ciVerdict == null ? null : String.format("%040d", minute);
    String id =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest row = new ReleaseRequest();
                  row.id = UUID.randomUUID().toString();
                  row.repoId = repoId;
                  row.projectId = project.id;
                  row.repoName = repoName;
                  row.summary = summary;
                  row.requester = SEEDER;
                  row.state = state;
                  if (state == ReleaseRequest.State.CONFLICTED) {
                    row.conflictDetail = "package.json: both branches changed it";
                    row.detail = "The sources cannot be folded.";
                  }
                  if (state == ReleaseRequest.State.RELEASED
                      || state == ReleaseRequest.State.FINALIZED) {
                    row.version = "2026.101." + (100000 + minute);
                  }
                  row.mergedSha = sha;
                  row.createdAt = when;
                  row.armedAt = when;
                  row.updatedAt = when;
                  row.persist();
                  if (ciVerdict != null) {
                    CommitBuildStatus verdict = new CommitBuildStatus();
                    verdict.runId = UUID.randomUUID().toString();
                    verdict.repoId = repoId;
                    verdict.projectId = project.id;
                    verdict.repoName = repoName;
                    verdict.branch = row.backingBranch();
                    verdict.commitSha = sha;
                    verdict.status = ciVerdict;
                    verdict.finishedAt = when;
                    verdict.persist();
                    seededVerdicts.add(verdict.runId);
                  }
                  return row.id;
                });
    seededRequests.add(id);
  }

  private String ticket(Project project, String title) {
    return create(
        Archetype.TICKET,
        project,
        EntityWrite.ticket(title, "Seeded work.", null, "BUG", null).withAcceptanceCriteria(TestCriteria.CRITERIA));
  }

  private String create(Archetype archetype, Project project, EntityWrite write) {
    return work.create(archetype, project.id, write, SEEDER).entity().id;
  }

  /** {@code repoName} for the name lookup, which answers the same 404 for either half missing. */
  private Setup noProjectWithTheGivenId() {
    return new Setup(
        params("projectId", UUID.randomUUID().toString(), "repoName", "contract-service"),
        List.of());
  }

  private Setup noRepositoryWithTheGivenId() {
    return new Setup(params("repositoryId", UUID.randomUUID().toString()), List.of());
  }

  // --- round 2 of qits-1149: the states other services' and the CLI's pacts asked for ----------

  /**
   * The pins answer the process's own boot config, so nothing is seeded. The versions are the
   * pinned agent and refinement images, which move with every dependency bump: they are reported
   * as unique tokens, so the recording freezes them and does not churn on a bump.
   */
  private Setup anAgentLaunchImageInUse() {
    List<String> versions =
        java.util.stream.Stream.of(
                agentContainers.imageVersion(), refinementContainers.imageVersion())
            .filter(version -> version != null && !version.isBlank())
            .distinct()
            .toList();
    return new Setup(params(), versions);
  }

  /**
   * A project, and a bare the git host serves under a storage id this service has never seen —
   * what the bootstrap leaves before it adopts. {@code repositoryId} is that storage id (it carries
   * the unique token), {@code repoName} the name to register it under.
   */
  private Setup aProjectAndAnUnadoptedRepositoryOnTheGitHost() {
    String token = token();
    Project project = project(token, A_PROJECT_AND_AN_UNADOPTED_REPOSITORY_ON_THE_GIT_HOST);
    String storageId = "contract-" + token + "-events-service";
    gitHostRepositories.ensure(storageId, "main");
    return new Setup(
        params(
            "projectId", project.id,
            "repositoryId", storageId,
            "repoName", "contract-events-service"),
        List.of(token));
  }

  /** A project and its wrapper, and nothing else: the create door adds the first component. */
  private Setup aProjectToCreateARepositoryIn() {
    String token = token();
    Project project = project(token, A_PROJECT_TO_CREATE_A_REPOSITORY_IN);
    return new Setup(params("projectId", project.id), List.of(token));
  }

  /**
   * contract-service with no open release request. The fold is the merger fake's, scripted to a
   * fixed sha, and a CI run is reported in flight so the gate holds the new request PENDING rather
   * than releasing it inside the call.
   */
  private Setup aRepositoryWithABranchToRelease() {
    String token = token();
    Project project = project(token, A_REPOSITORY_WITH_A_BRANCH_TO_RELEASE);
    String repoId = releasableRepository(project);
    return new Setup(params("projectId", project.id, "repositoryId", repoId), List.of(token));
  }

  /**
   * contract-service's open request for {@code feature/export}, opened through the domain as the
   * create door opens one. Adding {@code fix/rounding} re-folds to a second scripted sha.
   */
  private Setup aReleaseRequestOpenToAnotherBranch() {
    String token = token();
    Project project = project(token, A_RELEASE_REQUEST_OPEN_TO_ANOTHER_BRANCH);
    String repoId = releasableRepository(project);
    String requestId =
        releaseRequests.request(repoId, "feature/export", "Release the CSV export", SEEDER, null).id();
    merger
        .get()
        .answer(
            eu.wohlben.qits.projects.control.BackingBranchMerger.Outcome.merged(
                SCRIPTED_REFOLD, List.of(SCRIPTED_FOLD)));
    return new Setup(
        params("projectId", project.id, "repositoryId", repoId, "requestId", requestId),
        List.of(token));
  }

  /** contract-service, the merger scripted and a CI run in flight; see the two states above. */
  private String releasableRepository(Project project) {
    String repoId =
        projectService.createRepository(project.id, null, "contract-service", null).repository().id;
    requestRepos.add(repoId);
    mergerTouched = true;
    merger
        .get()
        .answer(
            eu.wohlben.qits.projects.control.BackingBranchMerger.Outcome.merged(
                SCRIPTED_FOLD, List.of()));
    activeBuilds.get().answer(java.util.Optional.of(1));
    return repoId;
  }

  /**
   * Three requests of contract-service, rows written straight to the table at fixed minutes: one
   * released and merged to main since (FINALIZED, its tag's {@code mergedAt} set), one RELEASED
   * whose tag has not reached main yet, and a WITHDRAWN one. {@code ?state=RELEASED} narrows to the
   * released one; {@code ?state=all} answers all three, newest first. Both states seed this.
   */
  private Setup aRepositoryWithReleases(String state) {
    String token = token();
    Project project = project(token, state);
    String repoId =
        projectService.createRepository(project.id, null, "contract-service", null).repository().id;
    seededRepos.add(repoId);
    String merged =
        releaseRow(
            project, repoId, "contract-service", "Release the CSV export", State.FINALIZED, 1,
            SCRIPTED_FOLD, row -> row.version = "2026.101.100100");
    mergedTag(repoId, merged, "2026.101.100100", "5eed5eed5eed5eed5eed5eed5eed5eed5eed0101", 2, 3);
    String released =
        releaseRow(
            project, repoId, "contract-service", "Release the rounding fix", State.RELEASED, 4,
            SCRIPTED_REFOLD, row -> row.version = "2026.101.100400");
    liveTag(repoId, released, "2026.101.100400", "5eed5eed5eed5eed5eed5eed5eed5eed5eed0104", null, 5);
    releaseRow(
        project, repoId, "contract-service", "Release the README", State.WITHDRAWN, 6, null,
        row -> row.detail = "Withdrawn: the README moves to the next release.");
    return new Setup(params("projectId", project.id, "repositoryId", repoId), List.of(token));
  }

  /** A released tag that reached main at the given minute. */
  private void mergedTag(
      String repoId, String requestId, String version, String releasedSha, int minute, int mergedMinute) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var tag = new eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge();
              tag.id = UUID.randomUUID().toString();
              tag.repoId = repoId;
              tag.tagName = version;
              tag.releasedSha = releasedSha;
              tag.releaseRequestId = requestId;
              tag.releasedAt = SEEDED_AT.plusSeconds(60L * minute);
              tag.publishState =
                  eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge.PublishState.PASSED;
              tag.mergedAt = SEEDED_AT.plusSeconds(60L * mergedMinute);
              tag.persist();
            });
  }

  /**
   * A REPORTED ticket an agent works on: the agent-waiting frame is keyed by {@code ticketId}, the
   * entity id the workspace holds, and {@code qualifiedId} names the same ticket.
   */
  private Setup aTicketWithADispatchedAgent() {
    String token = token();
    Project project = project(token, A_TICKET_WITH_A_DISPATCHED_AGENT);
    String ticket = ticket(project, "Export waits on a question nobody answered");
    return new Setup(
        params("projectId", project.id, "qualifiedId", qualified(project, ticket), "ticketId", ticket),
        List.of(token));
  }

  /**
   * One project whose contract-service carries six release tags on six commits of 2026-01-01:
   * {@code 2026.101.110000} is older than the five newest, pinned by nothing and in no gitlink (the
   * wrapper pins the template commit the seeded history replaced), so a run takes it.
   *
   * <p><b>This state empties the projects tables first</b>, as {@code PlatformStateReset} does
   * before every test method: the collection judges every repository in the database, and its
   * answer counts them, so only an otherwise empty database makes it the same on every run.
   */
  private Setup repositoriesWithDecommissionableTags() {
    new eu.wohlben.qits.projects.testsupport.PlatformStateReset().beforeEach(null);
    String token = token();
    Project project = project(token, REPOSITORIES_WITH_DECOMMISSIONABLE_TAGS);
    String repoId =
        projectService.createRepository(project.id, null, "contract-service", null).repository().id;
    try {
      SeededGit repo = SeededGit.init(gitWork, gitHost.fetchUrl(repoId));
      for (int i = 1; i <= 6; i++) {
        repo.write(Map.of("CHANGELOG.md", "Release " + i + ".\n"));
        repo.commit(i, "Release " + i);
        repo.tag("2026.101.1" + i + "0000");
      }
      repo.push("main");
      goCold(repoId);
    } catch (Exception e) {
      throw new IllegalStateException("Could not seed the tagged repository", e);
    }
    return new Setup(params("projectId", project.id, "repositoryId", repoId), List.of(token));
  }

  // --- seeding ---------------------------------------------------------------------------------

  /**
   * A project with a fixed name and description and a unique slug — {@code contract-<token>}. The
   * name is free to repeat (only the slug is unique), so it stays fixed seed data.
   */
  private Project project(String token, String state) {
    return project("Contract project", "contract-" + token, state);
  }

  private Project project(String name, String slug, String state) {
    return projectService.create(
        name, slug, "Seeded by the provider state '" + state + "'.", null, DNS);
  }

  /** Eight random hex characters — enough to keep slugs apart, short enough to fit one. */
  private static String token() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  private static Map<String, String> params(String... keysAndValues) {
    Map<String, String> out = new TreeMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      out.put(keysAndValues[i], keysAndValues[i + 1]);
    }
    return Collections.unmodifiableMap(out);
  }
}
