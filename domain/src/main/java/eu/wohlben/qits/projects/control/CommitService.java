package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.InternalServerErrorException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.dto.CommitChangesDto;
import eu.wohlben.qits.projects.dto.CommitDto;
import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import eu.wohlben.qits.projects.dto.CommitFileDiffDto;
import eu.wohlben.qits.projects.dto.CommitLogDto;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.gitmirror.GitMirrorException;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the commit log for a branch, scoped to the commits unique to it. The "parent" a branch is
 * compared against is the parent of the workspace that owns the branch, falling back to the
 * repository's main branch when the branch isn't workspace-backed.
 */
@ApplicationScoped
public class CommitService {

  /** Field separator between log fields ({@code %x1f}); never appears in any single field. */
  private static final String FIELD_SEP = "\u001f";

  /**
   * Record separator ({@code %x1e}) prefixing each commit, so the per-commit file lists that {@code
   * --name-only} appends (each on its own line) can be split back apart.
   */
  private static final String RECORD_SEP = "\u001e";

  private static final String LOG_FORMAT = "--format=%x1e%H%x1f%h%x1f%an%x1f%ae%x1f%cI%x1f%s";

  @Inject RepositoryRepository repositoryRepository;

  /**
   * SEAM (migration-plan.md §6): was {@code WorkspaceRepository}. The `workspace` table is
   * qits-workspaces', in another database — see {@link WorkspaceLookup} for the absent-behaviour
   * contract.
   */
  @Inject Instance<WorkspaceLookup> workspaces;

  @Inject GitExecutor git;

  @Inject GitMirrorRegistry gitMirrors;

  @Inject GitHostRepositories gitHostRepositories;

  /**
   * Lists the commits on {@code branch} that are not on its parent ({@code git log
   * parent..branch}). When the parent can't be resolved (or equals the branch) the full branch
   * history is returned instead, so the view degrades gracefully rather than erroring.
   */
  public CommitLogDto listCommits(String repoId, String branch) {
    // `branch` is user-supplied: reject blank or dash-leading names so a value like
    // "-D"/"--all" can't be smuggled to git as a flag (argv flag injection).
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      throw new BadRequestException("Invalid branch name: " + branch);
    }

    Repository repo =
        repositoryRepository
            .findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    RepoMirror mirror = requireMirror(repoId);

    String parent = resolveParent(repoId, repo, branch);
    boolean usableParent =
        parent != null && !parent.isBlank() && !parent.startsWith("-") && !parent.equals(branch);
    String range = usableParent ? parent + ".." + branch : branch;

    try {
      // `--` terminates option parsing so the refspec is never read as a flag.
      String output =
          git.exec(mirror.gitDir().toFile(), "git", "log", "--name-only", LOG_FORMAT, range, "--");
      return new CommitLogDto(branch, usableParent ? parent : null, parseCommits(output));
    } catch (Exception e) {
      throw new InternalServerErrorException("Git log failed: " + e.getMessage());
    }
  }

  /**
   * Lists the commits a fast-forward or merge would bring into a workspace's branch from its parent
   * — the commits the parent has that the branch doesn't yet ({@code git log branch..parent}),
   * newest first. Returns an empty list when the workspace has no resolvable parent, is already up
   * to date, or the refs can't be read. Used for the "commits about to be pulled in" hover popover.
   */
  public CommitLogDto listIncomingCommits(String repoId, String workspaceId) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
    WorkspaceLookup.WorkspaceView workspace =
        workspaces.isUnsatisfied()
            ? null
            : workspaces.get().findActive(repoId, workspaceId).orElse(null);
    if (workspace == null) {
      // With no workspaces implementation wired in there is no such workspace as far as this
      // context can tell — the same 404 an unknown id already produced.
      throw new NotFoundException("Workspace not found: " + workspaceId);
    }

    RepoMirror mirror = requireMirror(repoId);

    // The branch is the workspace's stored column (there is no host checkout to read it from — the
    // checkout lives in the container). The log range below runs against the mirror, which holds
    // every workspace branch as a ref.
    String branch = workspace.branch();
    String parent = workspace.parent();
    boolean usable =
        branch != null
            && !branch.isBlank()
            && !branch.startsWith("-")
            && parent != null
            && !parent.isBlank()
            && !parent.startsWith("-")
            && !parent.equals(branch);
    if (!usable) {
      return new CommitLogDto(branch, null, List.of());
    }

    // `branch..parent` = commits reachable from the parent but not the branch — exactly what a
    // fast-forward (or merge) would add. `--` terminates options so the range can't be read as
    // flag.
    String range = branch + ".." + parent;
    try {
      String output =
          git.exec(mirror.gitDir().toFile(), "git", "log", "--name-only", LOG_FORMAT, range, "--");
      return new CommitLogDto(branch, parent, parseCommits(output));
    } catch (Exception e) {
      throw new InternalServerErrorException("Git log failed: " + e.getMessage());
    }
  }

  /**
   * What a merge commit brought in, and whether the commit is still there to be asked.
   *
   * @param present whether the mirror holds the merge object at all. False is an ordinary answer
   *     rather than a failure: a release request that was withdrawn has had its backing branch
   *     deleted, and a fold nothing references is pruned by the git host's own housekeeping.
   * @param commits the commits in the range, newest first; empty on a fold that added nothing
   */
  public record MergeRange(boolean present, List<CommitDto> commits) {}

  /**
   * The commits a release request's fold merged: everything the fold reaches that no earlier
   * release shipped.
   *
   * <p><b>The base is the released tags, not {@code ^1} and not {@code main}.</b> {@code ^1..}
   * under-reports twice over: a re-folded request's first parent is its own <em>previous fold</em>
   * (so the range shrinks to whatever the last re-fold added), and a first fold with nothing on the
   * target fast-forwards onto a source head (so there is no fold commit to have parents at all).
   * {@code main} as it stands now answers differently every time somebody else releases, and
   * nothing at all once this release itself reaches it. What is stable is the platform's own
   * invariant: {@code main} only ever advances by merging released tags — so "already shipped" IS
   * "reachable from a release tag", and the honest range is the fold minus every tag that does not
   * contain it. A tag that <em>does</em> contain the fold is this release itself or a later one,
   * and subtracting it would erase the answer.
   *
   * <p>Two consequences are deliberate. A sibling release folded in as an implicit source is
   * excluded — its tag does not contain this fold, so its commits read as shipped, which they are.
   * And a repository that has never released answers its whole history, because its first release
   * ships exactly that.
   *
   * <p><b>A missing object is an answer, never a 500.</b> The fold is probed with {@code cat-file
   * -e} first, because {@code git log} against an unknown rev exits non-zero the same way a real
   * failure does and the caller must be able to tell "that commit is gone" from "the read broke".
   */
  public MergeRange listMergeRange(String repoId, String mergedSha) {
    requireRef(mergedSha, "commit");
    RepoMirror mirror = requireMirror(repoId);
    try {
      GitExecutor.ExecResult probe =
          git.execAllowNonZero(
              mirror.gitDir().toFile(), "git", "cat-file", "-e", mergedSha + "^{commit}");
      if (probe.exitCode() != 0) {
        return new MergeRange(false, List.of());
      }
      List<String> command = new ArrayList<>();
      command.addAll(List.of("git", "log", "--name-only", LOG_FORMAT, mergedSha));
      String shipped =
          git.exec(mirror.gitDir().toFile(), "git", "tag", "--no-contains", mergedSha, "--");
      for (String tag : shipped.split("\n")) {
        if (!tag.isBlank()) {
          // The full spelling, so a tag can never be read as anything else; the names are the
          // platform's own calvers, but the range must not depend on that being true forever.
          command.add("^refs/tags/" + tag.trim());
        }
      }
      // `--` terminates option parsing so no rev is ever read as a flag.
      command.add("--");
      String output = git.exec(mirror.gitDir().toFile(), command.toArray(String[]::new));
      return new MergeRange(true, parseCommits(output));
    } catch (Exception e) {
      throw new InternalServerErrorException("Git log failed: " + e.getMessage());
    }
  }

  /**
   * The one commit a fold's diff is taken against, and whether the fold is there to be asked at all.
   *
   * @param present whether the mirror holds the fold object — same ordinary answer as {@link
   *     MergeRange#present()}, for the same reasons (a withdrawn request's branch is deleted, and a
   *     fold nothing references is pruned)
   * @param base the commit to diff against, or null for "the empty tree" ({@code --root}) — a
   *     repository that has never released has no earlier release to stand on
   * @param baseTag the release tag the base was resolved from, so a page can say "since
   *     2026.910.180413" rather than print a sha. Null exactly when {@code base} is null.
   */
  public record MergeDiffBase(boolean present, String base, String baseTag) {}

  /**
   * The diff base for a release request's fold: {@code merge-base(mergedSha, newest release tag that
   * does not contain it)}, or null — the empty tree — when the repository has never released.
   *
   * <p><b>It is not {@code mergedSha^1}.</b> A re-folded request's first parent is its own previous
   * fold, so {@code ^1} shows only what the last push added; and a fold whose sources are all
   * contained in one head is a fast-forward, writing no merge commit at all, so {@code ^1} is then
   * just the branch's own previous commit. Worse, {@code git diff-tree} against a merge commit with
   * no base takes the first parent and reports <em>nothing</em> — a silently empty answer for the
   * octopus case this exists for.
   *
   * <p><b>It is not the same base {@link #listMergeRange} uses.</b> That answers a commit
   * <em>list</em> with the same invariant but expressed as N negative tips ({@code ^refs/tags/…}),
   * which a diff cannot express: one diff has one base tree, so the set is resolved to a single
   * commit. The two agree in the ordinary case; where they differ <b>the commit list is the
   * authority</b> and this read names the base it actually used.
   *
   * <p><b>It is not {@code merge-base(mergedSha, main)}</b>, however identical the answer looks
   * today. {@code main} only ever advances by merging released tags, so the newest non-containing
   * tag <em>is</em> the main the fold was built on — until this release reaches {@code main}, at
   * which point that merge base collapses to {@code mergedSha} itself and the whole diff goes empty.
   * A released request's page is read months later, so that is not an edge case.
   *
   * <p>The newest tag is newest <b>by {@code creatordate}, never by name</b>: the names are the
   * platform's calvers today and this arithmetic must not depend on that staying true.
   *
   * <p>Unrelated histories are the one case where a tag exists and no merge base does ({@code
   * merge-base} exits non-zero); that answers the empty tree too, which is what a fold sharing no
   * history with any release has in fact added.
   */
  public MergeDiffBase resolveDiffBase(String repoId, String mergedSha) {
    requireRef(mergedSha, "commit");
    RepoMirror mirror = requireMirror(repoId);
    try {
      GitExecutor.ExecResult probe =
          git.execAllowNonZero(
              mirror.gitDir().toFile(), "git", "cat-file", "-e", mergedSha + "^{commit}");
      if (probe.exitCode() != 0) {
        return new MergeDiffBase(false, null, null);
      }
      Set<String> shipped = new LinkedHashSet<>();
      for (String tag :
          git.exec(mirror.gitDir().toFile(), "git", "tag", "--no-contains", mergedSha, "--")
              .split("\n")) {
        if (!tag.isBlank()) {
          shipped.add(tag.trim());
        }
      }
      if (shipped.isEmpty()) {
        return new MergeDiffBase(true, null, null);
      }
      String newest = null;
      for (String tag :
          git.exec(
                  mirror.gitDir().toFile(),
                  "git",
                  "for-each-ref",
                  "--sort=-creatordate",
                  "--format=%(refname:short)",
                  "refs/tags")
              .split("\n")) {
        if (shipped.contains(tag.trim())) {
          newest = tag.trim();
          break;
        }
      }
      if (newest == null) {
        return new MergeDiffBase(true, null, null);
      }
      // The full spelling, so a tag can never be read as anything else.
      GitExecutor.ExecResult mergeBase =
          git.execAllowNonZero(
              mirror.gitDir().toFile(),
              "git",
              "merge-base",
              "refs/tags/" + newest,
              mergedSha,
              "--");
      if (mergeBase.exitCode() != 0 || mergeBase.output().isBlank()) {
        return new MergeDiffBase(true, null, null);
      }
      return new MergeDiffBase(true, mergeBase.output().trim(), newest);
    } catch (Exception e) {
      throw new InternalServerErrorException("Git merge-base failed: " + e.getMessage());
    }
  }

  /**
   * Lists the files a commit changed relative to its diff base. The base is the explicit {@code
   * parent} when given, otherwise the commit's own first parent ({@code --root} so a root commit
   * still reports its added files). {@code parent} in the result is the resolved base ({@code null}
   * for a root commit).
   */
  public CommitChangesDto listChanges(String repoId, String commit, String parent) {
    return listChanges(repoId, commit, parent, null);
  }

  /**
   * {@link #listChanges(String, String, String)} narrowed to a single {@code pathspec}, for a caller
   * that already knows which entry it is asking about and must not pay for the whole tree's diff to
   * find out what that entry is. A path that the commit did not touch answers an empty list, which
   * is the honest answer rather than an error.
   *
   * <p><b>The read is {@code --raw -z}, and both flags are load-bearing.</b>
   *
   * <p>{@code --raw} is what carries the tree <em>modes</em> and the two object ids. Without them a
   * gitlink is indistinguishable from a file: the change set said {@code MODIFIED
   * components/qits-ci/qits-ci-service} and the only way to discover that the entry was a {@code
   * 160000} at all was to fetch its patch and read the {@code Subproject commit} lines back out of
   * the text — the very opacity this read exists to remove.
   *
   * <p>{@code -z} is what makes the parse honest. {@code --name-status} separates its fields with a
   * tab and <b>git-quotes</b> any path holding a tab, a newline or a non-ASCII byte — wrapping it in
   * double quotes and C-escaping the offending bytes — so the old split-on-tab parser answered
   * {@code "sch\303\266n.txt"} for a path that is simply {@code schön.txt}, and could be made to
   * split a path in half outright. With {@code -z} every field is NUL-terminated and nothing is
   * quoted or escaped at all, so a path arrives as the bytes it is.
   *
   * <p>The framing, verified against git rather than assumed, is one flat run of NUL-terminated
   * fields with <b>no separator between records</b>:
   *
   * <pre>
   * :&lt;oldMode&gt; &lt;newMode&gt; &lt;oldSha&gt; &lt;newSha&gt; &lt;STATUS&gt; NUL &lt;path&gt; NUL
   * :100644 100644 587be6b… d735d34… M NUL a file.txt NUL
   * :100644 100644 d735d34… d735d34… R100 NUL a file.txt NUL renamed file.txt NUL
   * :160000 160000 f796365… 51a476a… M NUL sub/child NUL
   * </pre>
   *
   * The status sits inside the header field with no tab before it, and {@code R}/{@code C} carry a
   * second path field for the destination. That is why the parser walks fields rather than lines.
   */
  public CommitChangesDto listChanges(
      String repoId, String commit, String parent, String pathspec) {
    requireRef(commit, "commit");
    if (pathspec != null && (pathspec.isBlank() || pathspec.startsWith("-"))) {
      throw new BadRequestException("Invalid path: " + pathspec);
    }
    String base = normalizeParent(parent);
    RepoMirror mirror = requireMirror(repoId);

    List<String> cmd =
        new ArrayList<>(List.of("git", "diff-tree", "-r", "--raw", "-M", "-z", "--no-commit-id"));
    if (base != null) {
      cmd.add(base);
    } else {
      cmd.add("--root");
    }
    cmd.add(commit);
    cmd.add("--"); // terminate options so refs/paths can't be read as flags
    if (pathspec != null) {
      cmd.add(pathspec);
    }

    try {
      String output = git.exec(mirror.gitDir().toFile(), cmd.toArray(String[]::new));
      return new CommitChangesDto(commit, base, parseChanges(output));
    } catch (Exception e) {
      throw new InternalServerErrorException("Git diff-tree failed: " + e.getMessage());
    }
  }

  /**
   * The commits in {@code from..to} — what {@code to} reaches that {@code from} does not — newest
   * first, in exactly the shape {@link #listMergeRange} answers.
   *
   * <p>It exists for the one question a wrapper's gitlink asks: the pair of pins says the submodule
   * moved from one commit to another, and what a person wants to read is the <em>sibling's</em>
   * commits in between. The range is one-sided on purpose — {@code from..to} is "what the new pin
   * adds", not the symmetric difference — so a pin that moved sideways onto a rebuilt branch answers
   * what it brings rather than a list mixing in what it dropped. The caller is the one holding the
   * context to say so, and {@link ReleaseRequests} does say so, in a sentence, when {@code from} is
   * not an ancestor of {@code to}.
   *
   * <p>A rev that names nothing is the caller's to rule out first — this method's non-zero exit is
   * an {@code InternalServerErrorException} like every other broken git read, and "that pin is not
   * in this repository's history" is a fact a caller should be answering with a sentence rather than
   * discovering here.
   */
  public List<CommitDto> listCommitRange(String repoId, String from, String to) {
    requireRef(from, "commit");
    requireRef(to, "commit");
    RepoMirror mirror = requireMirror(repoId);
    try {
      // `--` terminates option parsing so the range is never read as a flag.
      String output =
          git.exec(
              mirror.gitDir().toFile(),
              "git",
              "log",
              "--name-only",
              LOG_FORMAT,
              from + ".." + to,
              "--");
      return parseCommits(output);
    } catch (Exception e) {
      throw new InternalServerErrorException("Git log failed: " + e.getMessage());
    }
  }

  /**
   * Whether {@code repoId}'s mirror holds {@code sha} as a commit — the same {@code cat-file -e}
   * probe {@link #listMergeRange} opens with, exposed so a caller holding a pin read out of somebody
   * else's tree can tell "that commit is not here" from "the read broke". A blank or dash-leading
   * value is simply absent rather than a 400: a pin comes out of a tree entry, so refusing it would
   * turn a malformed manifest into an error page.
   */
  public boolean hasCommit(String repoId, String sha) {
    if (sha == null || sha.isBlank() || sha.startsWith("-")) {
      return false;
    }
    RepoMirror mirror = requireMirror(repoId);
    try {
      return git.execAllowNonZero(
                  mirror.gitDir().toFile(), "git", "cat-file", "-e", sha + "^{commit}")
              .exitCode()
          == 0;
    } catch (Exception e) {
      throw new InternalServerErrorException("Git cat-file failed: " + e.getMessage());
    }
  }

  /**
   * Whether {@code ancestor} is reachable from {@code descendant} in {@code repoId}. Both are
   * assumed present — probe with {@link #hasCommit} first, because {@code merge-base --is-ancestor}
   * exits non-zero for "no" and for "no such commit" alike, and this answers false to both.
   */
  public boolean isAncestor(String repoId, String ancestor, String descendant) {
    requireRef(ancestor, "commit");
    requireRef(descendant, "commit");
    RepoMirror mirror = requireMirror(repoId);
    try {
      return git.execAllowNonZero(
                  mirror.gitDir().toFile(),
                  "git",
                  "merge-base",
                  "--is-ancestor",
                  ancestor,
                  descendant,
                  "--")
              .exitCode()
          == 0;
    } catch (Exception e) {
      throw new InternalServerErrorException("Git merge-base failed: " + e.getMessage());
    }
  }

  /**
   * The bytes of a blob at {@code revision:path} in {@code repoId}, or null when the revision holds
   * no such path.
   *
   * <p>Absent is an answer rather than a failure because the one caller is reading a wrapper's
   * {@code .gitmodules} at a fold, and a fold that declares no submodules at all is an ordinary
   * commit — the same reading {@link WrapperReconcileService}'s "an empty manifest is not a
   * manifest" rule already takes. {@code git show} spells "no such path in that tree" and "that
   * revision does not exist" as the same non-zero exit, and neither is worth a 500 here.
   */
  public String readBlob(String repoId, String revision, String path) {
    requireRef(revision, "commit");
    if (path == null || path.isBlank() || path.startsWith("-")) {
      throw new BadRequestException("Invalid path: " + path);
    }
    RepoMirror mirror = requireMirror(repoId);
    try {
      GitExecutor.ExecResult result =
          git.execAllowNonZero(mirror.gitDir().toFile(), "git", "show", revision + ":" + path);
      return result.exitCode() == 0 ? result.output() : null;
    } catch (Exception e) {
      throw new InternalServerErrorException("Git show failed: " + e.getMessage());
    }
  }

  /**
   * The unified diff of a single {@code path} in {@code commit}, relative to the same base as
   * {@link #listChanges}. The diff text is empty when the file has no textual change (binary or
   * pure rename).
   */
  public CommitFileDiffDto getFileDiff(String repoId, String commit, String parent, String path) {
    requireRef(commit, "commit");
    if (path == null || path.isBlank() || path.startsWith("-")) {
      throw new BadRequestException("Invalid path: " + path);
    }
    String base = normalizeParent(parent);
    RepoMirror mirror = requireMirror(repoId);

    List<String> cmd = new ArrayList<>(List.of("git", "diff-tree", "-p", "-M", "--no-commit-id"));
    if (base != null) {
      cmd.add(base);
    } else {
      cmd.add("--root");
    }
    cmd.add(commit);
    cmd.add("--");
    cmd.add(path);

    try {
      String diff = git.exec(mirror.gitDir().toFile(), cmd.toArray(String[]::new));
      return new CommitFileDiffDto(path, changeTypeFromPatch(diff), diff);
    } catch (Exception e) {
      throw new InternalServerErrorException("Git diff-tree failed: " + e.getMessage());
    }
  }

  /**
   * Rejects null/blank/dash-leading refs so a value like {@code -D} can't be smuggled as a flag.
   */
  private void requireRef(String ref, String name) {
    if (ref == null || ref.isBlank() || ref.startsWith("-")) {
      throw new BadRequestException("Invalid " + name + ": " + ref);
    }
  }

  /** Null/blank parent means "diff against the commit's first parent"; otherwise validate it. */
  private String normalizeParent(String parent) {
    if (parent == null || parent.isBlank()) {
      return null;
    }
    if (parent.startsWith("-")) {
      throw new BadRequestException("Invalid parent: " + parent);
    }
    return parent;
  }

  /**
   * The mirror for {@code repoId}, cloned from the git host on first use and refreshed when the
   * freshness window has lapsed — same rule as {@code RepositoryService#requireMirror}
   * (projects-volume-decoupling-plan.md §3.5). The row check is unchanged (a 404 for an unknown id).
   *
   * <p>A refresh failure is ambiguous between "no such repository on the host" and "the host is
   * unreachable": on failure only, {@link GitHostRepositories#find} disambiguates — absent is the
   * same 404 an unknown row already gives, present (or the host still refusing to say) is a 500.
   */
  private RepoMirror requireMirror(String repoId) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
    RepoMirror mirror = gitMirrors.of(repoId);
    try {
      mirror.refresh();
    } catch (GitMirrorException e) {
      boolean existsOnHost;
      try {
        existsOnHost = gitHostRepositories.find(repoId).isPresent();
      } catch (GitHostException hostError) {
        throw new InternalServerErrorException(
            "Git host unreachable for " + repoId + ": " + hostError.getMessage());
      }
      if (!existsOnHost) {
        throw new NotFoundException("Repository not found on the git host: " + repoId);
      }
      throw new InternalServerErrorException(
          "Could not refresh the mirror for " + repoId + ": " + e.getMessage());
    }
    return mirror;
  }

  /**
   * Parses {@code diff-tree -r --raw -M -z --no-commit-id} — see {@link #listChanges(String, String,
   * String, String)} for the framing this walks and why it is not lines.
   *
   * <p>The whole output is one run of NUL-terminated fields with nothing between records, so the
   * only way to know where a record ends is to read its status: {@code R} and {@code C} take two
   * path fields, everything else takes one. Splitting on anything would be guessing, which is
   * precisely what the previous spelling did.
   */
  private List<CommitFileChangeDto> parseChanges(String output) {
    List<CommitFileChangeDto> files = new ArrayList<>();
    if (output == null || output.isEmpty()) {
      return files;
    }
    // -1 keeps the trailing empty the final NUL produces; it is skipped below like any other.
    String[] fields = output.split("\0", -1);
    int i = 0;
    while (i < fields.length) {
      String header = fields[i];
      if (header.isEmpty() || header.charAt(0) != ':') {
        // Anything that is not a header is either the trailing empty or output this parse does not
        // understand; skipping beats consuming a path field as though it were a record.
        i++;
        continue;
      }
      // ":<oldMode> <newMode> <oldSha> <newSha> <STATUS>" — five space-separated tokens, and the
      // status is inside this field rather than behind a tab.
      String[] head = header.substring(1).trim().split(" +");
      if (head.length < 5) {
        i++;
        continue;
      }
      // --raw scores renames and copies: "R100", "C75". The letter is the status; the number is a
      // similarity percentage this record does not carry.
      char code = head[4].charAt(0);
      boolean twoPaths = code == 'R' || code == 'C';
      if (i + (twoPaths ? 2 : 1) >= fields.length) {
        break; // a truncated final record: better dropped than half-read
      }
      String first = fields[i + 1];
      String second = twoPaths ? fields[i + 2] : null;
      files.add(
          new CommitFileChangeDto(
              twoPaths ? second : first,
              twoPaths ? first : null,
              changeType(code),
              mode(head[0]),
              mode(head[1]),
              objectId(head[2]),
              objectId(head[3]),
              null));
      i += twoPaths ? 3 : 2;
    }
    return files;
  }

  /** A mode of all zeroes is git's "this side does not exist"; that is an absence, not a mode. */
  private String mode(String raw) {
    return raw == null || raw.isBlank() || raw.chars().allMatch(c -> c == '0') ? null : raw;
  }

  /**
   * An object id, or null for the all-zero sentinel git prints for an absent side. Handing {@code
   * 0000000…} on would give a caller a value that looks like an id and names no object, which is the
   * kind of thing that becomes a {@code cat-file} against a commit that has never existed. Same
   * all-zeroes rule as {@link #mode}, and the same reading, so it is the same test.
   */
  private String objectId(String raw) {
    return mode(raw);
  }

  private String changeType(char code) {
    return switch (code) {
      case 'A' -> "ADDED";
      case 'D' -> "DELETED";
      case 'R' -> "RENAMED";
      case 'C' -> "COPIED";
      case 'T' -> "TYPE_CHANGED";
      default -> "MODIFIED";
    };
  }

  /** Derives the change type from the patch header, avoiding a second git call. */
  private String changeTypeFromPatch(String diff) {
    if (diff == null || diff.isBlank()) {
      return "MODIFIED";
    }
    if (diff.contains("new file mode")) {
      return "ADDED";
    }
    if (diff.contains("deleted file mode")) {
      return "DELETED";
    }
    if (diff.contains("rename from ")) {
      return "RENAMED";
    }
    if (diff.contains("copy from ")) {
      return "COPIED";
    }
    return "MODIFIED";
  }

  /**
   * The branch a workspace forked from, when {@code branch} is owned by a workspace; otherwise the
   * repository's main branch. Matched against each workspace's stored {@code branch} column (the
   * checkout lives in the container now — there is no host path to read the branch from).
   */
  private String resolveParent(String repoId, Repository repo, String branch) {
    // SEAM: with no WorkspaceLookup wired in, no branch is workspace-backed, so every branch falls
    // back to the repository's main branch — the same answer this loop already gave for a branch
    // that no workspace owns.
    if (!workspaces.isUnsatisfied()) {
      for (WorkspaceLookup.WorkspaceView wt : workspaces.get().findActiveByRepository(repoId)) {
        if (branch.equals(wt.branch())) {
          return wt.parent();
        }
      }
    }
    return repo.mainBranch;
  }

  /**
   * Parses {@code git log --name-only} output: each commit is prefixed with {@link #RECORD_SEP},
   * its first line holds the {@link #FIELD_SEP}-separated fields, and the remaining non-blank lines
   * are the paths it changed (absent for merge commits).
   */
  private List<CommitDto> parseCommits(String output) {
    List<CommitDto> commits = new ArrayList<>();
    for (String block : output.split(RECORD_SEP)) {
      if (block.isBlank()) {
        continue;
      }
      String[] lines = block.split("\n", -1);
      // Keep trailing empties (-1) so an empty commit message still yields a 6th field.
      String[] f = lines[0].split(FIELD_SEP, -1);
      if (f.length != 6) {
        continue;
      }
      List<String> files = new ArrayList<>();
      for (int i = 1; i < lines.length; i++) {
        if (!lines[i].isBlank()) {
          files.add(lines[i]);
        }
      }
      commits.add(new CommitDto(f[0], f[1], f[2], f[3], f[4], f[5], files));
    }
    return commits;
  }
}
