package eu.wohlben.qits.projects.entitieshost;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * <b>The five shapes a commit message may take</b> (qits-706) — this service's mirror of
 * qits-githost's {@code CommitMessageVariants}, whose receive hook refuses, on every repository
 * and from every pusher, a new commit whose first line fits none of them. There is no opt-in file,
 * no exemption by identity and no break-glass; a push into a repository with no refs at all is
 * adopting history and is not checked.
 *
 * <p>Classification is in declaration order and the <b>first match wins</b>, which is why {@link
 * #MERGE} comes first (a merge's message is free) and {@link #SUBJECT} last ({@code
 * bump(qits-112): 1 dependencies} is a {@link #BUMP}, though its scope is id-shaped). <b>Only the
 * first line counts</b> — everything from the first {@code \n} on is the body.
 *
 * <p>The two halves are kept identical by a shared table of vectors, {@code
 * commit-message-vectors.tsv}, which both repositories carry verbatim and each asserts row by row.
 * A change here is a change there.
 *
 * <p>{@link #SUBJECT}'s grammar is <b>a strict subset of {@link CommitSubjectEntities}'s
 * reader</b>: every subject this accepts, that class reads to at least one qualified id. The reader
 * stays the more lenient of the two (padding around a comma, a term with other characters), so a
 * subject the guard let through is never one the platform cannot attribute.
 */
public enum CommitMessageVariants {

  /** Two or more parents, any message — but the commits a merge brings in are checked. */
  MERGE(null, "Merge branch 'side'"),

  /** qits-maintenance's dependency bump. */
  BUMP(
      Pattern.compile("^bump\\([0-9A-Za-z._-]+\\): [0-9]+ dependencies$"),
      "bump(qits-112): 2 dependencies"),

  /** The release's own version-stamping commit. */
  RELEASE(
      Pattern.compile("^release\\([0-9]+\\.[0-9]+\\.[0-9]+\\): .+$"),
      "release(2026.1104.94340): qits-1852 A release request owing a re-fold can go READY"),

  /** The platform's own housekeeping: template skeletons, wrapper membership, archive edits. */
  PLATFORM(
      Pattern.compile("^chore\\(platform\\): \\S.*$"),
      "chore(platform): add workspace development flow"),

  /** A commit that names its work: {@code term(<project>-<n>[, <project>-<n>…])[!]: message}. */
  SUBJECT(
      Pattern.compile(
          "^[A-Za-z][A-Za-z0-9_/.-]*\\((" + Ids.ID + "(?:, *" + Ids.ID + ")*)\\)!?: \\S.*$"),
      "feat(qits-1337): refuse malformed commit subjects");

  /** Holds the id grammar where the enum's constructor arguments can read it. */
  private static final class Ids {
    /** One qualified id — {@link CommitSubjectEntities}' scope grammar exactly. */
    static final String ID = "[A-Za-z0-9][A-Za-z0-9-]*-[0-9]{1,18}";
  }

  private final Pattern firstLine;

  private final String example;

  CommitMessageVariants(Pattern firstLine, String example) {
    this.firstLine = firstLine;
    this.example = example;
  }

  /** A message that fits this variant, for documentation and refusals. */
  public String example() {
    return example;
  }

  /**
   * Whether a commit with this first line and this many parents fits this variant, taken on its
   * own — {@link #classify} is what applies the order.
   */
  public boolean fits(String firstLine, int parents) {
    if (this == MERGE) {
      return parents >= 2;
    }
    return this.firstLine.matcher(firstLine == null ? "" : firstLine).matches();
  }

  /**
   * The first variant {@code message} fits, in declaration order; empty when it fits none, which
   * is a commit qits-githost refuses — or, for history older than the rule, one it would have.
   *
   * @param message the whole commit message; only its first line is read
   * @param parents how many parents the commit has
   */
  public static Optional<CommitMessageVariants> classify(String message, int parents) {
    String line = firstLine(message);
    for (CommitMessageVariants variant : values()) {
      if (variant.fits(line, parents)) {
        return Optional.of(variant);
      }
    }
    return Optional.empty();
  }

  /** Everything before the first {@code \n}, untrimmed; "" for null. */
  public static String firstLine(String message) {
    if (message == null) {
      return "";
    }
    int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }
}
