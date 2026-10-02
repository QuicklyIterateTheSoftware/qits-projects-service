package eu.wohlben.qits.projects.contracts;

import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
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
  public static final String A_PROJECT_WITH_3_REPOSITORIES = "a project with 3 repositories";
  public static final String A_REPOSITORY_EXISTS = "a repository exists";
  public static final String A_PROJECT_WITH_REFINED_WORK = "a project with refined work";
  public static final String A_PROJECT_WITH_NO_WORK = "a project with no work";
  public static final String A_PROJECT_WITH_PENDING_RELEASE_REQUESTS =
      "a project with pending release requests";
  public static final String A_PROJECT_WITH_NO_RELEASE_REQUESTS =
      "a project with no release requests";
  public static final String A_PROJECT_WITH_REPOSITORIES_IN_COMPONENTS =
      "a project with repositories in components";
  public static final String A_PROJECT_WITH_WORK_IN_EVERY_STATUS =
      "a project with work in every status";
  public static final String NO_PROJECT_WITH_THE_GIVEN_ID = "no project with the given id";
  public static final String NO_REPOSITORY_WITH_THE_GIVEN_ID = "no repository with the given id";

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

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  /** Release requests and CI verdicts the states wrote, removed again by {@link #cleanUp()}. */
  private final List<String> seededRequests = new ArrayList<>();

  private final List<String> seededVerdicts = new ArrayList<>();

  public ProviderStates() {
    states.put(A_PROJECT_EXISTS, this::aProjectExists);
    states.put(A_PROJECT_WITH_3_REPOSITORIES, this::aProjectWith3Repositories);
    states.put(A_REPOSITORY_EXISTS, this::aRepositoryExists);
    states.put(A_PROJECT_WITH_REFINED_WORK, this::aProjectWithRefinedWork);
    states.put(A_PROJECT_WITH_NO_WORK, this::aProjectWithNoWork);
    states.put(A_PROJECT_WITH_PENDING_RELEASE_REQUESTS, this::aProjectWithPendingReleaseRequests);
    states.put(A_PROJECT_WITH_NO_RELEASE_REQUESTS, this::aProjectWithNoReleaseRequests);
    states.put(
        A_PROJECT_WITH_REPOSITORIES_IN_COMPONENTS, this::aProjectWithRepositoriesInComponents);
    states.put(A_PROJECT_WITH_WORK_IN_EVERY_STATUS, this::aProjectWithWorkInEveryStatus);
    states.put(NO_PROJECT_WITH_THE_GIVEN_ID, this::noProjectWithTheGivenId);
    states.put(NO_REPOSITORY_WITH_THE_GIVEN_ID, this::noRepositoryWithTheGivenId);
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
    if (seededRequests.isEmpty() && seededVerdicts.isEmpty()) {
      return;
    }
    List<String> ids = List.copyOf(seededRequests);
    List<String> runs = List.copyOf(seededVerdicts);
    seededRequests.clear();
    seededVerdicts.clear();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (!ids.isEmpty()) ReleaseRequest.delete("id in ?1", ids);
              if (!runs.isEmpty()) CommitBuildStatus.delete("runId in ?1", runs);
            });
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
        params("projectId", project.id, "repositoryId", created.repository().id), List.of(token));
  }

  /**
   * Five work items: three REFINED (an epic and two tickets), one ticket still REPORTED and one
   * DONE, so a status filter has something to leave out on both sides. Created and moved through
   * the service layer in a fixed order, so their numbers are fixed too.
   */
  private Setup aProjectWithRefinedWork() {
    String token = token();
    Project project = project(token, A_PROJECT_WITH_REFINED_WORK);
    String epic = create(Archetype.EPIC, project, EntityWrite.epic("Refined epic", "Seeded work."));
    String first = ticket(project, "Refined ticket");
    String second = ticket(project, "Second refined ticket");
    ticket(project, "Reported ticket");
    String done = ticket(project, "Done ticket");
    work.transition(Archetype.EPIC, epic, "REFINED", SEEDER);
    work.transition(Archetype.TICKET, first, "REFINED", SEEDER);
    work.transition(Archetype.TICKET, second, "REFINED", SEEDER);
    for (String status : List.of("REFINED", "IMPLEMENTED", "VERIFIED", "DONE")) {
      work.transition(Archetype.TICKET, done, status, SEEDER);
    }
    return new Setup(params("projectId", project.id), List.of(token));
  }

  /** A project and nothing in it: every work list answers empty. */
  /**
   * One epic and one ticket in each status, DROPPED included: the Work page's board, backlog and
   * archive all have something to show. Each item is moved through the lifecycle to its status.
   */
  private Setup aProjectWithWorkInEveryStatus() {
    String token = token();
    Project project = project(token, A_PROJECT_WITH_WORK_IN_EVERY_STATUS);
    Map<String, List<String>> paths = new LinkedHashMap<>();
    paths.put("Reported", List.of());
    paths.put("Refined", List.of("REFINED"));
    paths.put("Implemented", List.of("REFINED", "IMPLEMENTED"));
    paths.put("Verified", List.of("REFINED", "IMPLEMENTED", "VERIFIED"));
    paths.put("Done", List.of("REFINED", "IMPLEMENTED", "VERIFIED", "DONE"));
    paths.put("Dropped", List.of("DROPPED"));
    for (Map.Entry<String, List<String>> path : paths.entrySet()) {
      String epic =
          create(Archetype.EPIC, project, EntityWrite.epic(path.getKey() + " epic", "Seeded work."));
      String ticket = ticket(project, path.getKey() + " ticket");
      for (String status : path.getValue()) {
        work.transition(Archetype.EPIC, epic, status, SEEDER);
        work.transition(Archetype.TICKET, ticket, status, SEEDER);
      }
    }
    return new Setup(params("projectId", project.id), List.of(token));
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
        EntityWrite.ticket(title, "Seeded work.", null, "BUG", null));
  }

  private String create(Archetype archetype, Project project, EntityWrite write) {
    return work.create(archetype, project.id, write, SEEDER).entity().id;
  }

  private Setup noProjectWithTheGivenId() {
    return new Setup(params("projectId", UUID.randomUUID().toString()), List.of());
  }

  private Setup noRepositoryWithTheGivenId() {
    return new Setup(params("repositoryId", UUID.randomUUID().toString()), List.of());
  }

  // --- seeding ---------------------------------------------------------------------------------

  /**
   * A project with a fixed name and description and a unique slug — {@code contract-<token>}. The
   * name is free to repeat (only the slug is unique), so it stays fixed seed data.
   */
  private Project project(String token, String state) {
    return projectService.create(
        "Contract project",
        "contract-" + token,
        "Seeded by the provider state '" + state + "'.",
        null,
        DNS);
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
