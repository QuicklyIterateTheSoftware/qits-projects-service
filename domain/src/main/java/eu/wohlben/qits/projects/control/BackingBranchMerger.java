package eu.wohlben.qits.projects.control;

import java.util.List;

/**
 * Folds a release request's sources into its backing branch — qits-githost's octopus-merge
 * primitive, seen from this side of the boundary.
 *
 * <p>A port in the house shape: {@code Instance}-resolved, absent supported. A deployment with no
 * git-host address leaves every request standing with a detail that says why, which is a visible
 * stall the sweep re-tries rather than a silent one. <b>An implementation must not throw</b>: every
 * answer is an {@link Outcome}, and {@link Result#UNREACHABLE} carries the reason.
 *
 * <p><b>It speaks refs and nothing else.</b> The git host owns no vocabulary about releases and this
 * port keeps it that way: no version, no request id, no notion of what {@code release/<id>} is for.
 * What travels is a target ref, source refs in the order they should become parents, and a message.
 */
public interface BackingBranchMerger {

  /**
   * Fold {@code sources} into {@code target}, resolving nothing — the shape every caller had before
   * directives existed, kept because most folds have nothing to direct.
   */
  default Outcome merge(String repoId, String target, List<String> sources, String message) {
    return merge(repoId, target, sources, message, List.of());
  }

  /**
   * Fold {@code sources} into {@code target}, with {@code resolutions} deciding the paths named in
   * them instead of letting the merge fail on them.
   *
   * <p><b>The resolutions carry no release vocabulary and this port grows none.</b> A directive is a
   * path and the sha that path is to hold; why that sha is the right one — that it is the later of
   * two releases of the same sibling, and that it contains the other — is decided by {@link
   * ConflictResolver} on this side of the boundary and is nothing the git host is told. What travels
   * stays what has always travelled here: refs, paths and shas, with no version, no request id and
   * no notion of what {@code release/<id>} is for.
   *
   * <p>An empty list is exactly today's behaviour, and an implementation must make it exactly
   * today's <em>request</em> too: a git host that has never heard of directives must not be able to
   * tell the difference.
   *
   * @param repoId the repository's storage id — the git host's own key
   * @param target the full backing-branch ref, {@code refs/heads/release/<id>}
   * @param sources fully qualified refs ({@code refs/heads/main}, {@code refs/tags/2026.903.1}), in
   *     the order they should become parents of the fold
   * @param message the merge commit's message, for a person reading the log
   * @param resolutions at most one entry per path; a repeated path is refused by the far side
   */
  Outcome merge(
      String repoId,
      String target,
      List<String> sources,
      String message,
      List<Resolution> resolutions);

  /**
   * "Whatever the merge would have made of {@code path}, put this gitlink there instead."
   *
   * @param path the conflicting path, repository-relative and slash-separated
   * @param gitlink the 40-hex commit sha the submodule entry is to pin
   */
  record Resolution(String path, String gitlink) {}

  /**
   * What the fold did. The three success words are the git host's own — {@code merged} (a new commit
   * and the ref moved), {@code fast-forward} (the ref was created at or moved onto an existing
   * commit) and {@code unchanged} (every head was already contained; same sha, no new commit) — and
   * the difference between them is load-bearing here: <b>{@code UNCHANGED} is not a change</b>, so
   * it re-arms nothing and announces nothing, which is what makes a trigger that fires on an event
   * with no content behind it (a pending tag leaving the set, a duplicate delivery) free.
   *
   * <p>{@link Result#CONFLICT} is the git host's 409: no ref moved, and {@link Outcome#conflicts}
   * names the paths and the head that introduced each. {@link Result#UNREACHABLE} is everything
   * else — an unconfigured address, a timeout, a 5xx, a refusal nobody predicted — which is a fact
   * about the moment and not about the request, so the sweep asks again.
   *
   * <p>{@link Outcome#resolved} names the paths a directive decided, as the host reports them back.
   * It is empty for every fold that carried none, which is almost all of them; a caller that asked
   * for directives reads it to say <em>what it did</em> rather than inferring it from what it asked
   * for — the two differ the moment the far side finds a path it no longer has to decide.
   */
  record Outcome(
      Result result,
      String sha,
      List<String> parents,
      List<Conflict> conflicts,
      String detail,
      List<String> resolved) {

    public static Outcome merged(String sha, List<String> parents) {
      return new Outcome(Result.MERGED, sha, List.copyOf(parents), List.of(), null, List.of());
    }

    /** A fold that took directives and applied them — the paths it decided, in the host's order. */
    public static Outcome merged(String sha, List<String> parents, List<String> resolved) {
      return new Outcome(
          Result.MERGED, sha, List.copyOf(parents), List.of(), null, List.copyOf(resolved));
    }

    public static Outcome fastForward(String sha, List<String> parents) {
      return new Outcome(Result.FAST_FORWARD, sha, List.copyOf(parents), List.of(), null, List.of());
    }

    public static Outcome fastForward(String sha, List<String> parents, List<String> resolved) {
      return new Outcome(
          Result.FAST_FORWARD, sha, List.copyOf(parents), List.of(), null, List.copyOf(resolved));
    }

    public static Outcome unchanged(String sha) {
      return new Outcome(Result.UNCHANGED, sha, List.of(), List.of(), null, List.of());
    }

    public static Outcome conflict(String target, List<Conflict> conflicts) {
      return new Outcome(
          Result.CONFLICT, null, List.of(), List.copyOf(conflicts), target, List.of());
    }

    public static Outcome unreachable(String detail) {
      return new Outcome(Result.UNREACHABLE, null, List.of(), List.of(), detail, List.of());
    }

    /** Whether the target ref now names {@link #sha} — true for all three success words. */
    public boolean folded() {
      return result == Result.MERGED
          || result == Result.FAST_FORWARD
          || result == Result.UNCHANGED;
    }
  }

  enum Result {
    MERGED,
    FAST_FORWARD,
    UNCHANGED,
    CONFLICT,
    UNREACHABLE
  }

  /**
   * One conflicting path, forwarded from the git host unchanged.
   *
   * <p>{@code head} is the source as it was spelled to the host and {@code headSha} what it pointed
   * at — which is also the commit whose {@code .gitmodules} declares the path, if anything does, and
   * therefore the only rev a reader of that declaration may use.
   *
   * <p>The last four components are what make a mechanical resolution possible at all. {@code kind}
   * is {@code gitlink} or {@code file} and is never null; {@code base}, {@code ours} and {@code
   * theirs} are 40-hex or null, and a null means that side does not have the path (a deletion, which
   * is a person's call and never a machine's). <b>For a gitlink they are commits of the SUBMODULE's
   * repository</b>, not of the one being folded — so nothing about them can be looked up in the
   * repository this conflict is a conflict in.
   *
   * <p>The four-component form is kept for a caller that is describing an ordinary content conflict
   * and has nothing to say about sides: it reads {@code file}, with no base, ours or theirs.
   */
  record Conflict(
      String path,
      String head,
      String headSha,
      String reason,
      String kind,
      String base,
      String ours,
      String theirs) {

    public Conflict(String path, String head, String headSha, String reason) {
      this(path, head, headSha, reason, KIND_FILE, null, null, null);
    }
  }

  /** The {@code kind} of a conflict over a submodule pin — the one kind anything here can decide. */
  String KIND_GITLINK = "gitlink";

  /** The {@code kind} of a conflict over bytes. */
  String KIND_FILE = "file";
}
