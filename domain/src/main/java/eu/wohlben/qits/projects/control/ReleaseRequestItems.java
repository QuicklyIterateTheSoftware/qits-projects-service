package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.approval.BranchWorkItems;
import eu.wohlben.qits.projects.dto.ReleaseRequestItemDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestWorkItemDto;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * <b>What a release request is about, read off its sources</b> (qits-1673) — the items that
 * replaced the typed {@code summary}, and the text they join into.
 *
 * <p>The summary was a sentence the first asker typed, which repeated the work items the request
 * already carries and said nothing about anything that joined after. What a request is about is
 * derivable: one item per named source other than the default branch, in source order, labelled
 *
 * <ul>
 *   <li>{@code <qualifiedId> <title>} for a branch that names a work item ({@link
 *       BranchWorkItems#releaseItemOf}: {@code ticket/}, {@code epic/}, {@code task/}, an agent's
 *       {@code <that branch>-<anything>}, and {@code external/<project>-<n>-…}), read live;
 *   <li>the head commit subject of a {@code maintenance/*} branch, which the fold stores on the
 *       source row ({@link ReleaseRequestSource#label}) so a read never asks the git host — the
 *       branch name until a fold has read it;
 *   <li>the branch name for anything else.
 * </ul>
 *
 * <p>The released tags a request folds implicitly are <b>not</b> items: nobody put them on, and a
 * main-only bump labelled with the version it carries forward would read as a release of that
 * version.
 *
 * <p>{@link #text} is what the fold's merge message, the release commit and its tag, and the desk's
 * approval line carry — each derived from the sources when it is needed, and stored nowhere.
 */
@ApplicationScoped
public class ReleaseRequestItems {

  private static final Logger LOG = Logger.getLogger(ReleaseRequestItems.class);

  /** How long the joined text may be before it is cut. */
  public static final int TEXT_LIMIT = 200;

  /** The text of a request with no items: a main-only request nothing has joined yet. */
  public static final String MAIN_ONLY = "main";

  /** The prefix whose branches are labelled by their head commit's subject. */
  public static final String MAINTENANCE_PREFIX = "maintenance/";

  /**
   * A leading conventional-commit prefix whose scope is a list of work-item ids — {@code
   * chore(qits-112, qits-142): } — which {@link #maintenanceLabelOf} strips. The ids are {@code
   * <project-slug>-<number>}, comma-separated; the type is one word, a breaking-change {@code !}
   * allowed after the scope.
   */
  private static final Pattern WORK_ITEM_SCOPE_PREFIX =
      Pattern.compile(
          "^[A-Za-z][A-Za-z0-9_-]*\\("
              + "\\s*[A-Za-z0-9][A-Za-z0-9-]*-\\d+(?:\\s*,\\s*[A-Za-z0-9][A-Za-z0-9-]*-\\d+)*\\s*"
              + "\\)!?:\\s+(.*)$");

  /** What has to remain for {@link #WORK_ITEM_SCOPE_PREFIX} to be stripped: {@code type(scope): text}. */
  private static final Pattern CONVENTIONAL_SUBJECT =
      Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*\\([^)]+\\)!?:\\s+\\S.*$");

  @Inject Instance<BranchWorkItems> workItems;

  /** Whether {@code branch} is labelled by its head commit subject, read at fold time. */
  public static boolean labelledByCommit(String branch) {
    return branch != null && branch.startsWith(MAINTENANCE_PREFIX);
  }

  /**
   * The items of a request whose named sources are {@code named}, the default branch {@code main}
   * left out. Reads the epics database for each work-item branch, each read its own short
   * transaction, so it is called outside the caller's. A work item that cannot be read is labelled
   * by its branch name rather than failing the read.
   */
  public List<ReleaseRequestItemDto> items(List<ReleaseRequestSource> named, String main) {
    return items(named, main, new HashMap<>());
  }

  /**
   * {@link #items}, sharing {@code seen} across the rows of one page, so a branch on many requests
   * is looked up once.
   */
  public List<ReleaseRequestItemDto> items(
      List<ReleaseRequestSource> named, String main, Map<String, Optional<ReleaseRequestWorkItemDto>> seen) {
    List<ReleaseRequestItemDto> items = new ArrayList<>();
    for (ReleaseRequestSource source : named) {
      if (source.kind != ReleaseRequestSource.Kind.BRANCH || source.name.equals(main)) {
        continue;
      }
      items.add(item(source.name, source.label, seen));
    }
    return List.copyOf(items);
  }

  /** One branch's item; {@code stored} is the source row's label, read for a maintenance branch. */
  public ReleaseRequestItemDto item(
      String branch, String stored, Map<String, Optional<ReleaseRequestWorkItemDto>> seen) {
    if (labelledByCommit(branch)) {
      return new ReleaseRequestItemDto(
          branch, stored == null || stored.isBlank() ? branch : stored, null);
    }
    ReleaseRequestWorkItemDto workItem =
        seen.computeIfAbsent(branch, this::workItemOf).orElse(null);
    if (workItem == null) {
      return new ReleaseRequestItemDto(branch, branch, null);
    }
    String label =
        workItem.title() == null || workItem.title().isBlank()
            ? workItem.qualifiedId()
            : workItem.qualifiedId() + " " + workItem.title();
    return new ReleaseRequestItemDto(branch, label, workItem);
  }

  private Optional<ReleaseRequestWorkItemDto> workItemOf(String branch) {
    if (!workItems.isResolvable()) {
      return Optional.empty();
    }
    try {
      return workItems
          .get()
          .releaseItemOf(branch)
          .map(found -> new ReleaseRequestWorkItemDto(found.qualifiedId(), found.title()));
    } catch (RuntimeException e) {
      // The port says it answers empty rather than throwing; a label is never worth a failed read.
      LOG.debugf(e, "Could not read the work item of branch %s", branch);
      return Optional.empty();
    }
  }

  /**
   * The items' labels joined with {@code "; "}, cut to {@value #TEXT_LIMIT} characters with {@code
   * …} where they are longer, and {@value #MAIN_ONLY} where there are none.
   */
  public static String text(List<ReleaseRequestItemDto> items) {
    String joined =
        items.stream()
            .map(ReleaseRequestItemDto::label)
            .filter(label -> label != null && !label.isBlank())
            .collect(Collectors.joining("; "));
    if (joined.isEmpty()) {
      return MAIN_ONLY;
    }
    return joined.length() <= TEXT_LIMIT
        ? joined
        : joined.substring(0, TEXT_LIMIT - 1).stripTrailing() + "…";
  }

  /** The first line of a commit message, trimmed, or null where it has none. */
  public static String subjectOf(String message) {
    if (message == null) {
      return null;
    }
    String first = message.strip().lines().findFirst().orElse("").strip();
    if (first.isEmpty()) {
      return null;
    }
    return first.length() <= 512 ? first : first.substring(0, 511) + "…";
  }

  /**
   * The label a {@code maintenance/*} source is stored with: its head commit's {@link #subjectOf
   * subject}, less one leading conventional prefix whose scope lists work items. qits-maintenance
   * folds a bump's commits under {@code chore(<every ticket it carries>): <inner subject>}, so the
   * prefix is a list of ids, often a hundred long, that says nothing about the bump; {@code
   * chore(qits-112, qits-142): bump(qits-112): 1 dependencies} is labelled {@code bump(qits-112): 1
   * dependencies}. Only one prefix goes, and only when what remains is itself a conventional
   * subject ({@code type(scope): text}): a bare {@code bump(qits-112): 1 dependencies}, whose scope
   * merely looks like a work item, keeps its whole subject rather than shrinking to {@code 1
   * dependencies}. A scope that is not a list of work-item ids is left alone. Null where the
   * message has no subject.
   */
  public static String maintenanceLabelOf(String message) {
    String subject = subjectOf(message);
    if (subject == null) {
      return null;
    }
    Matcher matcher = WORK_ITEM_SCOPE_PREFIX.matcher(subject);
    if (matcher.matches()) {
      String rest = matcher.group(1).strip();
      if (CONVENTIONAL_SUBJECT.matcher(rest).matches()) {
        return rest;
      }
    }
    return subject;
  }
}
