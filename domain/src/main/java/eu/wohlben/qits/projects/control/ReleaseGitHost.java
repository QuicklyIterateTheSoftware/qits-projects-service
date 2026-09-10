package eu.wohlben.qits.projects.control;

import java.util.List;
import java.util.Map;

/**
 * The git-ref and content primitives a release needs from qits-githost — read the fold's tree, read
 * a manifest, commit the rewritten manifests, tag, and delete the branches the release consumed.
 *
 * <p><b>It speaks git and nothing else</b>, the discipline {@link BackingBranchMerger} states one
 * seam over: no version, no request id, no notion of what {@code release/<id>} is for. What travels
 * is a repository id, refs, paths and bytes. Which paths are manifests, which version goes in them
 * and what order the steps come in is {@code ReleaseExecutor}'s, on this side of the boundary.
 *
 * <p>A port in the house shape: {@code Instance}-resolved, absent supported — with no git host
 * configured every release refuses with a detail saying so, which is a visible stall the sweep
 * retries rather than a silent one. <b>An implementation must not throw</b>: every answer is an
 * {@link Answer} (or, for the tag, a {@link TagAnswer}), and a failure carries its own reason and
 * its own verdict on whether asking again can change it.
 *
 * <p><b>Retryable is a statement about the moment, not about the ask.</b> An unreachable host, a
 * timeout, a 5xx and a ref that moved under a slow caller are all retryable; a repository that is
 * not there, a rev that does not resolve and a malformed request answer the same forever and are
 * not. The distinction becomes the release request's {@code retryable} flag, which is the only
 * thing standing between a final refusal and the sweep knocking on it every thirty seconds.
 */
public interface ReleaseGitHost {

  /**
   * What a call answered: a value, or why not.
   *
   * @param value the answer, null when {@link #detail} says what went wrong instead
   * @param detail the reason there is no value, null on success
   * @param retryable whether asking again can change the answer; meaningless on success
   */
  record Answer<T>(T value, String detail, boolean retryable) {

    public boolean ok() {
      return detail == null;
    }

    public static <T> Answer<T> of(T value) {
      return new Answer<>(value, null, false);
    }

    /** A refusal about the ask: it will answer the same until something changes. */
    public static <T> Answer<T> failed(String detail) {
      return new Answer<>(null, detail, false);
    }

    /** A refusal about the moment: the sweep asks again. */
    public static <T> Answer<T> failedRetryable(String detail) {
      return new Answer<>(null, detail, true);
    }
  }

  /**
   * Every blob path of one commit, repository-relative and slash-separated. Gitlinks are absent —
   * a submodule has no blob to read and no tree this host descends into.
   */
  Answer<List<String>> tree(String repoId, String rev);

  /** The UTF-8 text of one path at one commit. A binary or absent blob is a failed answer. */
  Answer<String> file(String repoId, String rev, String path);

  /**
   * One commit's worth of files onto a branch ref, answering the ref's new tip.
   *
   * <p><b>An edit that changes nothing writes nothing</b>, and the answer is the tip's own sha — so
   * a bump retried after a timeout is free rather than a second empty commit, and a repository that
   * renders no version at all is a release with no commit before its tag.
   *
   * @param ref the full {@code refs/heads/…} name
   * @param files path → the file's whole new content
   * @param gitlinks path → the full commit sha a submodule entry pins, written as a gitlink (mode
   *     160000) tree entry. <b>The release path passes none.</b> A wrapper's pins are written onto
   *     its source branch, before the gate and before anybody approves the fold, so the commit a
   *     release makes carries the version bump and nothing else; this parameter stays because the
   *     primitive is the git host's and a writer of pins is exactly what it is for.
   */
  Answer<String> commit(
      String repoId,
      String ref,
      String message,
      Map<String, String> files,
      Map<String, String> gitlinks);

  /**
   * Where a branch of a repository stands right now — the sha its {@code refs/heads/<branch>}
   * points at, asked of the host that owns the refs rather than guessed from anything cached here.
   * A branch the host does not know is a failed answer, not a null.
   */
  Answer<String> head(String repoId, String branch);

  /**
   * The commit sha the mode-160000 entry at {@code path} pins, at {@code rev} of the repository
   * registered as {@code repoName} in {@code projectId} — or an <b>ok answer carrying null</b> when
   * nothing is pinned there.
   *
   * <p><b>Addressed by name, and it has to be.</b> {@link #tree} answers a commit's blobs and skips
   * gitlinks outright — a submodule has no blob to read and no tree this host descends into — so
   * the single fact a pin consists of, its sha, is on no answer that read can give. The listing
   * that carries it is the git plane's directory read, which types a gitlink {@code commit} and
   * hands back the {@code sha} and the {@code mode} beside it, and that route is addressed by
   * project and repository name rather than by storage id. The asymmetry is the host's, not this
   * port's, and hiding it behind a storage id here would mean inventing a lookup nobody owns.
   *
   * <p>A directory that is not there, an entry that is not there and an entry that is not a gitlink
   * are all one answer — <b>nothing is pinned at this path</b> — and it is a null value rather than
   * a failure. A {@code .gitmodules} section whose gitlink has not been committed yet is a fold
   * somebody is still writing, and a port has no opinion about that; the caller decides what an
   * unpinned declaration means.
   */
  Answer<String> gitlinkAt(String projectId, String repoName, String rev, String path);

  /**
   * Whether the repository registered as {@code repoName} in {@code projectId} holds {@code sha} —
   * "does this commit exist", which is the whole of what can be asked about a pin.
   *
   * <p>A gitlink is recorded without ever being resolved: git writes the 40 hex characters it is
   * given into the superproject's tree and neither fetches nor validates the object, because the
   * object lives in another repository entirely. That is what makes a pin able to name a commit
   * that is nowhere — a rewritten branch, a repository restored from an older copy, a sha somebody
   * typed — and what makes the failure so late and so total: nothing notices until a clone runs
   * {@code submodule update} and stops.
   *
   * <p><b>False is an answer, not a failure.</b> The host said, and it said no. A failed answer is
   * the moment or the ask going wrong instead, and the two must not be confused: refusing a release
   * because the git host was briefly unreachable is a different sentence from refusing it because
   * the estate it declares does not exist.
   */
  Answer<Boolean> resolves(String projectId, String repoName, String sha);

  /**
   * What tagging said. {@link TagResult#ALREADY_EXISTS} is the one that is not a failure: it is the
   * platform's version-uniqueness guarantee arriving, and the caller's answer to it is to stamp a
   * fresh version and ask again — never to force the ref.
   */
  enum TagResult {
    CREATED,
    ALREADY_EXISTS,
    FAILED
  }

  /**
   * @param sha the tag object, on {@link TagResult#CREATED}; what the existing ref says, on {@link
   *     TagResult#ALREADY_EXISTS}
   */
  record TagAnswer(TagResult result, String sha, String detail, boolean retryable) {

    public static TagAnswer created(String sha) {
      return new TagAnswer(TagResult.CREATED, sha, null, false);
    }

    public static TagAnswer alreadyExists(String sha) {
      return new TagAnswer(TagResult.ALREADY_EXISTS, sha, null, false);
    }

    public static TagAnswer failed(String detail) {
      return new TagAnswer(TagResult.FAILED, null, detail, false);
    }

    public static TagAnswer failedRetryable(String detail) {
      return new TagAnswer(TagResult.FAILED, null, detail, true);
    }
  }

  /** An annotated tag at {@code sha}. {@code name} is the bare tag name, which is the version. */
  TagAnswer tag(String repoId, String name, String sha, String message);

  /**
   * Delete a branch, best effort. <b>It answers nothing and must never throw</b>: by the time it is
   * called the tag exists and the release has happened, so nothing after it may pretend it did not
   * — a branch that could not be deleted is a log line, not a failed release. A branch that is
   * already gone is a success, and the git host refuses a repository's default branch on its own.
   */
  void deleteBranch(String repoId, String name);
}
