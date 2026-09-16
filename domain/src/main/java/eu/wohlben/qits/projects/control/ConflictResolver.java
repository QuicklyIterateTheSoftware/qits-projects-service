package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.persistence.ReleasedTagPendingMergeRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * <b>Decide a conflicted fold, or say why it cannot be decided</b> — one mechanical attempt at the
 * commonest conflict this service hits, made once, between the git host's 409 and the CONFLICTED
 * state a person has to act on.
 *
 * <h2>The one conflict worth deciding</h2>
 *
 * <p>A wrapper's fold brings several sources together and each of them may have bumped the same
 * submodule pin. Git cannot merge a gitlink: it is forty hex characters in a tree entry, two sides
 * wrote two different ones, and there is nothing to merge textually — so a release request that is
 * otherwise perfectly foldable goes CONFLICTED and waits for somebody. But this service already
 * knows the answer whenever both shas are releases of that sibling: both are rows in {@code
 * released_tag_pending_merge}, one of them is later, and later is what the estate wants pinned. That
 * is the whole of what this class decides, and it decides nothing else.
 *
 * <h2>Seven conditions, and a path that misses one is not resolved</h2>
 *
 * <p>For each conflicting path, all seven must answer:
 *
 * <ol>
 *   <li>the conflict's {@code kind} is {@code gitlink} — a text conflict is content somebody wrote
 *       and no table here has an opinion about it;
 *   <li>{@code ours} and {@code theirs} are both present and different — a side that has no sha is
 *       deleting the submodule, which is a decision about the estate's membership and a person's;
 *   <li>the path is declared in the {@code .gitmodules} of the head that introduced the conflict
 *       (read at {@code headSha}, because that commit is the only rev whose declaration is the one
 *       this conflict is about);
 *   <li>that section's <em>name</em> resolves to a repository of this project — {@link
 *       RepositoryNameRepository#findRepositoryByProjectAndName}, the lookup {@code
 *       EstatePinRefresh.released} makes and for the reason stated there;
 *   <li>both shas are recorded releases of that repository;
 *   <li>one of them is strictly later by {@code releasedAt} — the ordering {@link
 *       ReleasedTagPendingMergeRepository#latestReleased} already uses;
 *   <li>the later one <b>contains</b> the earlier one in the sibling's own repository.
 * </ol>
 *
 * <p><b>The seventh is not decoration.</b> {@code released_tag_pending_merge} orders by when a
 * release happened and knows nothing about lineage, so a release cut from a side branch is later in
 * time while containing none of what the older one shipped. Picking it by time alone would drop
 * shipped work out of the estate silently: the fold would succeed, the wrapper would name a member
 * commit that is missing released changes, and nothing anywhere would fail. {@link
 * ReleaseGitHost#contains} is what turns "newer" into "newer and a descendant", and a {@code false}
 * or an unreadable answer both give the conflict back to a person.
 *
 * <h2>All of them or none of them</h2>
 *
 * <p><b>A partial directive set is never sent.</b> A fold half-decided by a machine and half by a
 * person is worse than one decided entirely by a person: the person then has to reason about a tree
 * that is neither side and that nobody wrote. So one unanswerable path makes the whole attempt
 * unanswerable, and {@link Attempt#detail()} names that path and the condition it missed.
 *
 * <p><b>A path can appear more than once in one report</b> — once per head that introduced it — and
 * the directive list may not repeat a path (the far side answers 400 to one that does). Each
 * occurrence is decided on its own, keyed by path, and <b>the answers have to agree</b>: two heads
 * that would pin the same path at two different shas are two different decisions about one entry,
 * and choosing between them is exactly the judgement this class is not making. Disagreement is a no
 * answer like any other.
 *
 * <h2>Posture</h2>
 *
 * <p><b>Nothing here throws</b>, {@link EstatePinRefresh}'s rule and for {@link EstatePinRefresh}'s
 * reason: it is called from a seam where a fold has already happened, and no enrichment of a landed
 * fold may undo it. A port bug, a lazy-loading surprise or anything else costs one WARN and a no
 * answer — which is the CONFLICTED the request was going to get anyway.
 *
 * <p>Every read is made <b>outside every transaction</b>, like the fold itself: the git-host calls
 * are plain HTTP round trips and the database reads are short {@code requiringNew()} transactions of
 * their own, the way {@code EstatePinRefresh} splits {@code gather} from {@code released}.
 *
 * <p>It has no trigger, no schedule and no loop. {@code ReleaseRequests.remerge} asks it once per
 * conflicting fold and folds at most one more time on the strength of the answer.
 */
@ApplicationScoped
public class ConflictResolver {

  private static final Logger LOG = Logger.getLogger(ConflictResolver.class);

  /** The one path that says a fold declares submodules. It is the file git itself reads. */
  private static final String GITMODULES = ".gitmodules";

  @Inject RepositoryNameRepository names;

  @Inject ReleasedTagPendingMergeRepository pendingTags;

  @Inject Instance<ReleaseGitHost> gitHosts;

  /**
   * One decided path: the pin moves from {@code from} to {@code to}, which is release {@code
   * version} of the sibling declared as {@code name}. It exists so the decision can be written into
   * the merge commit's own message — a database column is not where a person reading {@code git log}
   * looks.
   */
  public record Decision(String path, String name, String from, String to, String version) {}

  /**
   * What one attempt came to: a complete set of directives, or a sentence saying which path could
   * not be answered for and why.
   *
   * <p>The two are exclusive by construction — there is no half answer — and {@link #complete()} is
   * the only thing a caller branches on.
   */
  public record Attempt(
      List<BackingBranchMerger.Resolution> directives, List<Decision> decisions, String detail) {

    public static Attempt noAnswer(String detail) {
      return new Attempt(List.of(), List.of(), detail);
    }

    static Attempt of(List<Decision> decisions) {
      return new Attempt(
          decisions.stream()
              .map(decision -> new BackingBranchMerger.Resolution(decision.path(), decision.to()))
              .toList(),
          List.copyOf(decisions),
          null);
    }

    /** Whether there is a directive for every conflicting path — the caller's whole question. */
    public boolean complete() {
      return detail == null && !directives.isEmpty();
    }

    /**
     * The decisions as git trailers, one line per path, for the message of the fold that applies
     * them.
     *
     * <p>The backing branch's log is where this belongs and a column here is not enough: the commit
     * outlives this row, travels with the tag and is what somebody bisecting a wrapper reads. The
     * shape is a trailer so that {@code git log} renders it where trailers are expected and {@code
     * git interpret-trailers} can find it.
     */
    public String trailer() {
      StringBuilder out = new StringBuilder();
      for (Decision decision : decisions) {
        out.append("Resolved-Gitlink: ")
            .append(decision.path())
            .append(' ')
            .append(decision.from())
            .append(" -> ")
            .append(decision.to())
            .append(" (")
            .append(decision.name())
            .append(' ')
            .append(decision.version())
            .append(")\n");
      }
      return out.toString();
    }
  }

  /**
   * Attempt {@code outcome}'s conflicts. Never throws; a request nothing can be decided for gets an
   * {@link Attempt} whose {@link Attempt#detail()} is one sentence for a person.
   *
   * @param requestId the release request, for the log line only — no decision is made from it
   * @param repoId the repository being folded, whose {@code .gitmodules} declares the paths
   * @param projectId the project whose members a declaration may name
   */
  public Attempt attempt(
      String requestId, String repoId, String projectId, BackingBranchMerger.Outcome outcome) {
    try {
      return decide(repoId, projectId, outcome);
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not attempt a resolution of release request %s's conflict; it stands as a"
              + " conflict",
          requestId);
      return Attempt.noAnswer("the resolution attempt failed: " + e);
    }
  }

  private Attempt decide(
      String repoId, String projectId, BackingBranchMerger.Outcome outcome) {
    List<BackingBranchMerger.Conflict> conflicts = outcome.conflicts();
    if (conflicts.isEmpty()) {
      // A conflict with no paths on it is one nothing can be said about — including by a person,
      // which is why it is worth saying so rather than silently answering "nothing to do".
      return Attempt.noAnswer("the git host named no conflicting path");
    }
    ReleaseGitHost gitHost = gitHost();
    if (gitHost == null) {
      return Attempt.noAnswer(
          "no git host is configured, so the submodule declarations cannot be read");
    }

    // One read per distinct head, not per conflict: several paths routinely conflict on one head.
    Map<String, Map<String, String>> declarations = new HashMap<>();
    Map<String, Decision> decided = new LinkedHashMap<>();

    for (BackingBranchMerger.Conflict conflict : conflicts) {
      String path = conflict.path();
      if (!BackingBranchMerger.KIND_GITLINK.equals(conflict.kind())) {
        return no(path, "it is a conflict over content and not over a submodule pin");
      }
      if (conflict.ours() == null
          || conflict.theirs() == null
          || conflict.ours().isBlank()
          || conflict.theirs().isBlank()) {
        return no(path, "one side does not have the submodule at all, which is a person's call");
      }
      if (conflict.ours().equals(conflict.theirs())) {
        // Not reachable from a git host that only reports real conflicts, and cheap to refuse: a
        // directive naming the sha both sides already hold would decide nothing.
        return no(path, "both sides pin the same commit, so there is nothing to decide");
      }
      if (conflict.headSha() == null || conflict.headSha().isBlank()) {
        return no(path, "the git host named no head commit, so no declaration can be read");
      }

      Map<String, String> declared = declarations.get(conflict.headSha());
      if (declared == null) {
        ReleaseGitHost.Answer<String> content =
            gitHost.file(repoId, conflict.headSha(), GITMODULES);
        if (!content.ok()) {
          // Includes "there is no .gitmodules at all", which the file read cannot tell apart from a
          // read that failed — and both are the same no answer here, so nothing is lost by it.
          return no(
              path,
              "the submodule declarations at "
                  + conflict.head()
                  + " could not be read: "
                  + content.detail());
        }
        declared = new LinkedHashMap<>();
        for (WrapperGitmodules.Entry entry : WrapperGitmodules.entries(content.value())) {
          if (entry.path() != null && !entry.path().isBlank() && entry.name() != null) {
            declared.put(entry.path(), entry.name());
          }
        }
        declarations.put(conflict.headSha(), declared);
      }
      String name = declared.get(path);
      if (name == null) {
        return no(path, "no submodule is declared there at " + conflict.head());
      }

      SiblingReleases releases =
          releasesOf(projectId, name, conflict.ours(), conflict.theirs());
      if (releases == null) {
        return no(path, "'" + name + "' names no repository of this project");
      }
      if (releases.ours() == null) {
        return no(path, shortSha(conflict.ours()) + " is no recorded release of '" + name + "'");
      }
      if (releases.theirs() == null) {
        return no(path, shortSha(conflict.theirs()) + " is no recorded release of '" + name + "'");
      }
      if (releases.ours().releasedAt.equals(releases.theirs().releasedAt)) {
        return no(
            path, "both releases of '" + name + "' were cut at the same instant, so neither is later");
      }
      boolean theirsIsLater = releases.theirs().releasedAt.isAfter(releases.ours().releasedAt);
      ReleasedTagPendingMerge later = theirsIsLater ? releases.theirs() : releases.ours();
      ReleasedTagPendingMerge earlier = theirsIsLater ? releases.ours() : releases.theirs();

      ReleaseGitHost.Answer<Boolean> contains =
          gitHost.contains(releases.siblingRepoId(), earlier.releasedSha, later.releasedSha);
      if (!contains.ok()) {
        return no(
            path,
            "whether "
                + later.tagName
                + " of '"
                + name
                + "' contains "
                + earlier.tagName
                + " could not be read: "
                + contains.detail());
      }
      if (!Boolean.TRUE.equals(contains.value())) {
        return no(
            path,
            later.tagName
                + " of '"
                + name
                + "' is the later release but does not contain "
                + earlier.tagName
                + ", so pinning it would drop released work");
      }

      Decision decision =
          new Decision(
              path, name, earlier.releasedSha, later.releasedSha, later.tagName);
      Decision already = decided.put(path, decision);
      if (already != null && !already.to().equals(decision.to())) {
        // Two heads introduced the same path and the two readings disagree about what it should
        // hold. Choosing between them is the judgement this class deliberately does not make.
        return no(
            path,
            "two heads decide it differently ("
                + shortSha(already.to())
                + " and "
                + shortSha(decision.to())
                + ")");
      }
    }
    return Attempt.of(List.copyOf(decided.values()));
  }

  /**
   * The git host, or null where the deployment has none — absent is a supported configuration for
   * every port here, and a resolver that cannot read a declaration decides nothing.
   *
   * <p>Package-private and overridable so the unit suite can drive the seven conditions against a
   * hand-built host without a container; nothing in production replaces it.
   */
  ReleaseGitHost gitHost() {
    return gitHosts.isResolvable() ? gitHosts.get() : null;
  }

  /** The two releases behind one conflict, or nulls where there is no release to prefer. */
  record SiblingReleases(
      String siblingRepoId, ReleasedTagPendingMerge ours, ReleasedTagPendingMerge theirs) {}

  /**
   * The database half — the section name resolved to a member of this project, and each candidate
   * sha looked up as a release of it. One short transaction of its own, outside the fold's, {@code
   * EstatePinRefresh.released}'s shape.
   *
   * <p>Null is "this project has no such repository", which is an answer and not a failure: a
   * wrapper is edited by people and reconciled asynchronously, so a section naming a member nobody
   * has adopted yet is an ordinary intermediate state — and a member whose releases are unknown is
   * one nothing here can decide for.
   *
   * <p>Package-private and overridable so the unit suite can drive the seven conditions without a
   * container; nothing in production replaces it.
   */
  SiblingReleases releasesOf(String projectId, String name, String ours, String theirs) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Repository sibling =
                  names.findRepositoryByProjectAndName(projectId, name).orElse(null);
              if (sibling == null) {
                return null;
              }
              return new SiblingReleases(
                  sibling.id,
                  released(sibling.id, ours),
                  released(sibling.id, theirs));
            });
  }

  private ReleasedTagPendingMerge released(String repoId, String sha) {
    return pendingTags
        .findByReleasedSha(repoId, sha)
        .filter(row -> row.releasedAt != null && row.tagName != null)
        .orElse(null);
  }

  private static Attempt no(String path, String reason) {
    return Attempt.noAnswer(
        "the conflict at " + path + " is not one this service can decide: " + reason);
  }

  private static String shortSha(String sha) {
    if (sha == null) {
      return "(nothing)";
    }
    return sha.length() <= 10 ? sha : sha.substring(0, 10);
  }
}
