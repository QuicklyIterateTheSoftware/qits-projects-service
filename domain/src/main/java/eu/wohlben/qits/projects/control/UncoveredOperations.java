package eu.wohlben.qits.projects.control;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>What a QA run says about operations no golden master covers, in this service's words</b>
 * (qits-1940).
 *
 * <p>qits-ci records a run report of kind {@value #REPORT_KIND} on the QA step of a java-service
 * release. Its summary carries {@code uncovered operations: <n>} first ({@link #COUNT}) and then up
 * to nine {@code uncovered: <name>} lines ({@link #NAME}), one per uncovered operation in document
 * order — the name an operationId or {@code METHOD /path}, possibly cut at 80 characters ending in
 * {@code …}. This record is that summary read back, and {@link #note} the sentence the request, its
 * gate ticket and the branch's {@code fyi} notice append, {@link StaleOutputs#note}'s sibling.
 *
 * @param count the {@code n} of the count line, never negative
 * @param names the operations the report lists, in its order; at most {@code count} of them matter
 */
public record UncoveredOperations(int count, List<String> names) {

  /** The qits-ci report kind the golden-master coverage check records. */
  public static final String REPORT_KIND = "golden-master-coverage";

  /** The report's first highlight: {@code uncovered operations: <n>}. */
  public static final Pattern COUNT = Pattern.compile("^uncovered operations: (\\d+)$");

  /** One uncovered operation: {@code uncovered: <name>}. */
  public static final Pattern NAME = Pattern.compile("^uncovered: (.+)$");

  public UncoveredOperations {
    names = names == null ? List.of() : List.copyOf(names);
  }

  /**
   * The report read off its highlights' texts, in their order, or null where no highlight is a
   * readable {@link #COUNT} — a report without its headline says nothing this service repeats.
   * Texts matching neither pattern are skipped.
   */
  public static UncoveredOperations of(List<String> highlights) {
    if (highlights == null) {
      return null;
    }
    Integer count = null;
    List<String> names = new ArrayList<>();
    for (String text : highlights) {
      if (text == null) {
        continue;
      }
      Matcher c = COUNT.matcher(text);
      if (c.matches()) {
        if (count == null) {
          try {
            count = Integer.parseInt(c.group(1));
          } catch (NumberFormatException e) {
            return null;
          }
        }
        continue;
      }
      Matcher n = NAME.matcher(text);
      if (n.matches()) {
        names.add(n.group(1));
      }
    }
    return count == null ? null : new UncoveredOperations(count, names);
  }

  /**
   * {@code uncovered operations: <n> — <name>, <name> and <k> more — add a golden-master state for
   * each}, {@code k} the operations the report counted but did not list, the {@code and <k> more}
   * left out at zero. Null where there is no report or nothing uncovered, so the caller's sentence
   * stays exactly as it was.
   */
  public static String note(UncoveredOperations uncovered) {
    if (uncovered == null || uncovered.count() <= 0) {
      return null;
    }
    List<String> listed =
        uncovered.names().size() > uncovered.count()
            ? uncovered.names().subList(0, uncovered.count())
            : uncovered.names();
    int more = uncovered.count() - listed.size();
    String which =
        listed.isEmpty()
            ? ""
            : " — " + String.join(", ", listed) + (more == 0 ? "" : " and " + more + " more");
    return "uncovered operations: "
        + uncovered.count()
        + which
        + " — add a golden-master state for each";
  }
}
