package eu.wohlben.qits.projects.control;

/**
 * A repository's release-request settings, as its {@code .config/qits/release-requests.yml} declares
 * them.
 *
 * <pre>
 *     manual-review: true
 *     feature_freeze: 5
 * </pre>
 *
 * <p>{@code feature_freeze} (epic qits-1580) is the repository's own soft feature freeze, 0..20 with
 * 0 the freeze turned off, and it overrides its project's {@code release_requests.feature_freeze}
 * ({@link FeatureFreezeThreshold}). It is spelled with the underscore of that project key rather than
 * this file's hyphen, so the one setting reads the same at both levels. Null where absent.
 *
 * <p><b>{@link #NONE} is what an absent file means, and it is not a degraded answer.</b> Almost
 * every repository on this platform carries no such file and must not grow an approval gate by
 * shipping one; absent, an absent key and an explicit {@code false} are one answer on purpose. What
 * is <em>not</em> this record is a file that could not be read or parsed — that is an error the
 * parser throws, never a quiet {@code NONE}, because a settings file that fails open on a typo is a
 * gate that disappears when somebody mistypes it.
 */
public record ReleaseRequestSettings(boolean manualReview, Integer featureFreeze) {

  /** No file, no key, or {@code false}: no approval gate, and no freeze of the repository's own. */
  public static final ReleaseRequestSettings NONE = new ReleaseRequestSettings(false, null);

  /** The approval half alone, as the file read before {@code feature_freeze} existed. */
  public ReleaseRequestSettings(boolean manualReview) {
    this(manualReview, null);
  }
}
