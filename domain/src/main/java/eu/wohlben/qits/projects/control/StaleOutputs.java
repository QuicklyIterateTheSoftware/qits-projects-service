package eu.wohlben.qits.projects.control;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * <b>What a red QA run says about stale outputs, in this service's words</b> (qits-1920).
 *
 * <p>qits-ci records a run report of kind {@value #REPORT_KIND} when the QA step's output check
 * finds a committed output its generator would change. Its summary carries one highlight per stale
 * kind, {@code stale outputs: <kind>} ({@link #HIGHLIGHT}); this class reads the kind back off it
 * and composes the sentence the request, its gate ticket and the branch's {@code fyi} notice
 * append: {@code stale outputs: <kind> — regenerate with: <command>}, several joined with {@code ;
 * }. The command is {@link #command}, the same one a DEFERRED automation check names.
 */
public final class StaleOutputs {

  /** The qits-ci report kind the output check records. */
  public static final String REPORT_KIND = "stale-outputs";

  /** One highlight of that report: {@code stale outputs: <kind>}, nothing else on the line. */
  public static final Pattern HIGHLIGHT = Pattern.compile("^stale outputs: (\\S+)$");

  private StaleOutputs() {}

  /** The kind a highlight names, or null for a highlight that is not {@link #HIGHLIGHT}. */
  public static String kindOf(String highlight) {
    if (highlight == null) {
      return null;
    }
    Matcher m = HIGHLIGHT.matcher(highlight);
    return m.matches() ? m.group(1) : null;
  }

  /**
   * {@code qits release-request regenerate --project <projectId> --repository <repoName> --kind
   * <kind>} — the address qits-ci's own output check prints. A request that lacks either half of
   * the pair names the kind alone.
   */
  public static String command(String projectId, String repoName, String kind) {
    String address =
        projectId != null && repoName != null
            ? "--project " + projectId + " --repository " + repoName + " "
            : "";
    return "qits release-request regenerate " + address + "--kind " + kind;
  }

  /**
   * {@code stale outputs: <kind> — regenerate with: <command>} per kind, joined with {@code ; };
   * null where there is no kind, so the caller's sentence stays exactly as it was.
   */
  public static String note(String projectId, String repoName, List<String> kinds) {
    if (kinds == null || kinds.isEmpty()) {
      return null;
    }
    String note =
        kinds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .map(
                kind ->
                    "stale outputs: "
                        + kind
                        + " — regenerate with: "
                        + command(projectId, repoName, kind))
            .collect(Collectors.joining("; "));
    return note.isEmpty() ? null : note;
  }
}
