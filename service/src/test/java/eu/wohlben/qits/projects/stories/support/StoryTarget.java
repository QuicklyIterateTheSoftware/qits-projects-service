package eu.wohlben.qits.projects.stories.support;

/**
 * The one launched process, addressed the way each of its surfaces is addressed — and named the way
 * a diagram names it.
 *
 * <p>Everything qits-projects serves to a machine hangs off <b>one segment</b>. {@code
 * quarkus.rest.path=/projects/api} is the JSON API; {@code
 * quarkus.http.non-application-root-path=/projects/q} is what Quarkus itself serves, and the
 * framework's shipped RestAssured tap skips any path carrying a {@code /q/} segment — which is
 * exactly right here, so no story class overrides the predicate. The segment is not decoration: the
 * platform edge path-routes every application's segment on every host, so dropping it is the
 * difference between this service being reachable and not.
 *
 * <p>The <b>port is random</b> — failsafe launches the artifact with {@code
 * quarkus.http.test-port=0} — so nothing here is a constant except the paths. RestAssured is
 * configured with the port by the Quarkus integration-test extension, so an API call needs no base
 * url at all; only {@link StoryPlatform}'s tap-invisible fixture client builds one, and it reads
 * {@code RestAssured.port} for exactly that reason.
 */
public final class StoryTarget {

  /** How every diagram in this catalogue names the service under test, on both sides of an edge. */
  public static final String SERVICE = "qits-projects";

  /** {@code /projects/api} — {@code quarkus.rest.path}. A resource's {@code @Path} is relative. */
  public static final String API_PATH = "/projects/api";

  /** The projects collection: {@code GET} is the overview, {@code POST} creates one. */
  public static final String PROJECTS_PATH = API_PATH + "/projects";

  /** The flat repository catalogue — qits-ci's trigger catalogue, and the platform inventory. */
  public static final String REPOSITORIES_PATH = API_PATH + "/repositories";

  private StoryTarget() {}

  /** One project: {@code /projects/api/projects/<id>}. */
  public static String projectPath(String projectId) {
    return PROJECTS_PATH + "/" + projectId;
  }

  /** The project's components, plus its wrapper's manifest as the UI reads it. */
  public static String projectRepositoriesPath(String projectId) {
    return projectPath(projectId) + "/repositories";
  }

  /** The bootstrap's door: register a repository the git host already serves. {@code qits:system}. */
  public static String adoptPath(String projectId) {
    return projectRepositoriesPath(projectId) + "/adopt";
  }

  /** qits-githost's own read: a project-scoped repository name becomes a storage id. */
  public static String byNamePath(String projectId, String repoName) {
    return projectRepositoriesPath(projectId) + "/by-name/" + repoName;
  }

  /** One repository by id — qits-workspaces' lookup and the workspaces detail screen. */
  public static String repositoryPath(String repoId) {
    return REPOSITORIES_PATH + "/" + repoId;
  }

  /**
   * The project's work, every archetype: {@code GET} lists it, narrowed by {@code ?archetype=}. The
   * query never reaches a diagram — the tap labels an edge by the path alone.
   */
  public static String projectWorkPath(String projectId) {
    return projectPath(projectId) + "/work";
  }

  /**
   * The one create door for a root (qits-976): {@code POST} an epic, a ticket or a campaign, the
   * archetype in the body. A node is added under its parent at {@link #workChildrenPath}.
   */
  public static final String WORK_PATH = API_PATH + "/work";

  /**
   * One work entity by id — a qualified id or a UUID; the stories pass the UUID, which the tap
   * scrubs to {@code {id}}. {@code PATCH} is the merge patch where acceptance criteria and the
   * implemented markers are written; {@code PUT} is the one-entry transition.
   */
  public static String workPath(String id) {
    return WORK_PATH + "/" + id;
  }

  /** The entity's children — an epic's features, a feature's tasks: {@code POST} adds one. */
  public static String workChildrenPath(String id) {
    return workPath(id) + "/children";
  }

  /** The door that moves an entity's status — the scope freeze, the schedule, the terminal moves. */
  public static String workStatusPath(String id) {
    return workPath(id) + "/status";
  }

  /** The entity subtree's whole change history, newest first — it outlives the rows it describes. */
  public static String workAuditPath(String id) {
    return workPath(id) + "/audit";
  }
}
