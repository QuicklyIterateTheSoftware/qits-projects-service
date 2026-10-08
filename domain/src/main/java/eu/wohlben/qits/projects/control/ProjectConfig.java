package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;

/**
 * A project's own configuration, as its wrapper's {@code .config/qits/project.yml} declares it.
 *
 * <pre>
 *     supports_environments: false
 *     front_desk:
 *       lifecycle: ALWAYS_ON
 * </pre>
 *
 * <p>One component per key — the shape {@link ReleaseRequestSettings} has for the repository-level
 * file beside it. {@code frontDeskLifecycle} is never null: absent means {@link
 * FrontDeskLifecycle#ON_DEMAND}.
 *
 * <p><b>{@link #DEFAULT} is what an absent file means, and it is not a degraded answer.</b> Every
 * project on this platform predates the file and every one of them is deployed once per
 * environment, so absent, an absent key and an explicit {@code true} are one answer on purpose:
 * <em>only</em> an explicit {@code false} changes anything. What is <b>not</b> this record is a file
 * that could not be read or parsed — that is an error {@link ProjectConfigParser} throws, never a
 * quiet {@link #DEFAULT}, because a declaration that fails open on a typo re-routes a project
 * silently.
 */
public record ProjectConfig(boolean supportsEnvironments, FrontDeskLifecycle frontDeskLifecycle) {

  public ProjectConfig {
    if (frontDeskLifecycle == null) {
      frontDeskLifecycle = FrontDeskLifecycle.ON_DEMAND;
    }
  }

  /**
   * No file, no key, or {@code true}: the project is deployed once per environment, and its front
   * desk is {@link FrontDeskLifecycle#ON_DEMAND}.
   */
  public static final ProjectConfig DEFAULT = new ProjectConfig(true, FrontDeskLifecycle.ON_DEMAND);

  /** The one thing an explicit {@code false} says. */
  public static final ProjectConfig SINGLE_ENVIRONMENT =
      new ProjectConfig(false, FrontDeskLifecycle.ON_DEMAND);
}
