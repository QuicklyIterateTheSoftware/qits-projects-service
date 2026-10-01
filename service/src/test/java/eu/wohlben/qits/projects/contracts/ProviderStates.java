package eu.wohlben.qits.projects.contracts;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ProjectDnsRecord;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
  public static final String NO_PROJECT_WITH_THE_GIVEN_ID = "no project with the given id";
  public static final String NO_REPOSITORY_WITH_THE_GIVEN_ID = "no repository with the given id";

  /** The three component repositories {@link #A_PROJECT_WITH_3_REPOSITORIES} creates. */
  static final List<String> THREE_REPOSITORIES =
      List.of("contract-service", "contract-frontend", "contract-daemon");

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

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  public ProviderStates() {
    states.put(A_PROJECT_EXISTS, this::aProjectExists);
    states.put(A_PROJECT_WITH_3_REPOSITORIES, this::aProjectWith3Repositories);
    states.put(A_REPOSITORY_EXISTS, this::aRepositoryExists);
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
