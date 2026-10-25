package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.ProjectConfig.ReleaseRequestsSettings;
import eu.wohlben.qits.projects.entity.Repository;

/**
 * <b>The soft feature freeze's threshold in force for one repository</b> (epic qits-1580), and the
 * level that set it. The most specific value <em>set</em> wins:
 *
 * <ol>
 *   <li>{@link Source#REPOSITORY} — {@code feature_freeze} in the repository's own {@code
 *       .config/qits/release-requests.yml} at its default branch ({@link
 *       ReleaseRequestSettingsParser}), stored on {@link Repository#releaseFeatureFreeze};
 *   <li>{@link Source#PROJECT} — {@code release_requests.feature_freeze} in the wrapper's {@code
 *       .config/qits/project.yml} ({@link ProjectConfigParser}), stored on {@code
 *       Project.releaseFeatureFreeze};
 *   <li>{@link Source#PLATFORM} — {@link ReleaseRequestsSettings#DEFAULT_FEATURE_FREEZE}, where
 *       neither says anything.
 * </ol>
 *
 * <p>A value of {@code 0} is a value set, not an absence: it turns the freeze off at that level, so a
 * repository declaring 0 is never frozen whatever its project says, and a project declaring 0 is
 * never frozen unless a repository of it declares its own.
 *
 * @param threshold how many counted sources make the active request frozen; 0 means never
 * @param source which level set it
 */
public record FeatureFreezeThreshold(int threshold, Source source) {

  /** The level a threshold came from, as {@code featureFreeze.source} spells it on a read. */
  public enum Source {
    PLATFORM,
    PROJECT,
    REPOSITORY
  }

  /** The platform's fallback: three, set by nobody. */
  public static final FeatureFreezeThreshold PLATFORM =
      new FeatureFreezeThreshold(ReleaseRequestsSettings.DEFAULT_FEATURE_FREEZE, Source.PLATFORM);

  /** The threshold of {@code repositoryValue} over {@code projectValue}; either may be null. */
  public static FeatureFreezeThreshold of(Integer repositoryValue, Integer projectValue) {
    if (repositoryValue != null) {
      return new FeatureFreezeThreshold(repositoryValue, Source.REPOSITORY);
    }
    if (projectValue != null) {
      return new FeatureFreezeThreshold(projectValue, Source.PROJECT);
    }
    return PLATFORM;
  }

  /** The threshold of {@code repository}'s stored columns; a missing row is the platform's. */
  public static FeatureFreezeThreshold of(Repository repository) {
    if (repository == null) {
      return PLATFORM;
    }
    return of(
        repository.releaseFeatureFreeze,
        repository.project == null ? null : repository.project.releaseFeatureFreeze);
  }

  /** Whether the freeze is on at all: a threshold of 0 never freezes. */
  public boolean enabled() {
    return threshold > 0;
  }

  /** Whether a request holding {@code counted} counted sources is frozen under this threshold. */
  public boolean freezes(int counted) {
    return enabled() && counted >= threshold;
  }
}
