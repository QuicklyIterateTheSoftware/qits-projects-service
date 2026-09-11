package eu.wohlben.qits.projects.control;

/**
 * A repository's release-request settings, as its {@code .config/qits/release-requests.yml} declares
 * them.
 *
 * <pre>
 *     manual-review: true
 * </pre>
 *
 * <p>One key today, and the record exists so a second one costs a component rather than a new file.
 *
 * <p><b>{@link #NONE} is what an absent file means, and it is not a degraded answer.</b> Almost
 * every repository on this platform carries no such file and must not grow an approval gate by
 * shipping one; absent, an absent key and an explicit {@code false} are one answer on purpose. What
 * is <em>not</em> this record is a file that could not be read or parsed — that is an error the
 * parser throws, never a quiet {@code NONE}, because a settings file that fails open on a typo is a
 * gate that disappears when somebody mistypes it.
 */
public record ReleaseRequestSettings(boolean manualReview) {

  /** No file, no key, or {@code false}: no approval gate. */
  public static final ReleaseRequestSettings NONE = new ReleaseRequestSettings(false);
}
