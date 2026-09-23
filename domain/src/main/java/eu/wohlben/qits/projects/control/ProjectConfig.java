package eu.wohlben.qits.projects.control;

/**
 * A project's own configuration, as its wrapper's {@code .config/qits/project.yml} declares it.
 *
 * <pre>
 *     supports_environments: false
 * </pre>
 *
 * <p>One key today, and the record exists so a second one costs a component rather than a new file
 * — the shape {@link ReleaseRequestSettings} has for the repository-level file beside it.
 *
 * <p><b>{@link #DEFAULT} is what an absent file means, and it is not a degraded answer.</b> Every
 * project on this platform predates the file and every one of them is deployed once per
 * environment, so absent, an absent key and an explicit {@code true} are one answer on purpose:
 * <em>only</em> an explicit {@code false} changes anything. What is <b>not</b> this record is a file
 * that could not be read or parsed — that is an error {@link ProjectConfigParser} throws, never a
 * quiet {@link #DEFAULT}, because a declaration that fails open on a typo re-routes a project
 * silently.
 */
public record ProjectConfig(boolean supportsEnvironments) {

  /** No file, no key, or {@code true}: the project is deployed once per environment. */
  public static final ProjectConfig DEFAULT = new ProjectConfig(true);

  /** The one thing an explicit {@code false} says. */
  public static final ProjectConfig SINGLE_ENVIRONMENT = new ProjectConfig(false);
}
