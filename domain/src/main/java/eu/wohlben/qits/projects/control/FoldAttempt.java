package eu.wohlben.qits.projects.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * <b>Which fold attempt a CONFLICTED or a fold's FAILED is about</b> (qits-1725): the key {@link
 * BranchDeliveries} deduplicates those steps on beside the fold sha.
 *
 * <p>The fold sha alone cannot tell two failed attempts apart. It is the request's {@code
 * mergedSha} — the last fold that <i>landed</i> — and a fold that conflicts, or names a ref the git
 * host does not have, lands nothing, so it leaves that sha where it was. Keyed on the sha alone, a
 * second conflict with another branch, or at a new head of the same one, read as the position the
 * row already held and was swallowed: the agent whose push caused it never heard. The attempt is
 * what moved, so the attempt is the key:
 *
 * <ul>
 *   <li>a conflict — the first 16 hex characters of the SHA-256 of the distinct {@code head@headSha}
 *       pairs its conflicts name, sorted and joined by a newline. The same heads at the same tips
 *       are the same attempt however many paths collide; a new tip of any of them is a new one.
 *   <li>a missing source — {@code missing:<ref>}, the ref as the git host named it, {@code
 *       missing:refs/heads/epic/qits-1681}: readable, and the same until that ref exists again.
 * </ul>
 *
 * <p>Every other step carries none (null), and compares as before.
 */
public final class FoldAttempt {

  /** Hex characters kept of the digest: 64 bits, ample to tell one request's attempts apart. */
  private static final int LENGTH = 16;

  private static final String MISSING = "missing:";

  private static final String HEADS = "refs/heads/";

  private FoldAttempt() {}

  /** The attempt {@code conflicts} came from; null for none, which a CONFLICT never answers. */
  public static String ofConflicts(Collection<BackingBranchMerger.Conflict> conflicts) {
    if (conflicts == null || conflicts.isEmpty()) {
      return null;
    }
    String heads =
        conflicts.stream()
            .filter(Objects::nonNull)
            .map(conflict -> conflict.head() + "@" + conflict.headSha())
            .distinct()
            .sorted()
            .collect(Collectors.joining("\n"));
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(heads.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, LENGTH);
    } catch (NoSuchAlgorithmException e) {
      // Cannot happen: every Java platform is required to ship SHA-256.
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /** The attempt that found {@code ref} missing on the git host. */
  public static String ofMissingSource(String ref) {
    return MISSING + ref;
  }

  /**
   * The ref a {@link #ofMissingSource} attempt names, {@code refs/heads/epic/qits-1681}; null for
   * any other attempt (qits-1725: whose owner is told).
   */
  public static String missingRef(String attempt) {
    return attempt != null && attempt.startsWith(MISSING) ? attempt.substring(MISSING.length())
        : null;
  }

  /**
   * The branch a fold source spells, as the ledger names it: {@code refs/heads/<b>} and a bare
   * {@code <b>} are both {@code <b>} — {@code ReleaseRequests} hands the host {@code
   * "refs/heads/" + source.name}, and the host forwards a conflict's {@code head} as it was spelled
   * to it. A tag ({@code refs/tags/...}) or any other {@code refs/} is no branch: null.
   */
  public static String branchOf(String ref) {
    if (ref == null || ref.isBlank()) {
      return null;
    }
    if (ref.startsWith(HEADS)) {
      return ref.substring(HEADS.length());
    }
    return ref.startsWith("refs/") ? null : ref;
  }
}
