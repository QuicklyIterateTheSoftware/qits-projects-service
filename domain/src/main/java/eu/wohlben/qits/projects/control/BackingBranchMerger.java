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
 * What travels is a target ref, source refs in the order they should become parents, and a message
 * — plus the repository's public address pair, which is no release vocabulary either: it is what the
 * git host needs to announce the fold like any other ref move.
 */
public interface BackingBranchMerger {

  /**
   * Fold {@code sources} into {@code target}, resolving nothing — the shape every caller had before
   * directives existed, kept because most folds have nothing to direct.
   */
  default Outcome merge(
      String repoId,
      String projectId,
      String repoName,
      String target,
      List<String> sources,
      String message) {
    return merge(repoId, projectId, repoName, target, sources, message, List.of());
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
   * <p>This form asks for no version-pin decisions; see the eight-argument form.
   *
   * @param repoId the repository's storage id — the git host's own key
   * @param projectId the project the repository belongs to, or null. With {@code repoName} it is the
   *     public address pair the git host stamps on the {@code SCMPublishCommit} it announces for the
   *     move — the git host stores no names, so a door write knows them only if it is told, exactly
   *     as a push knows them only from its URL. Addressing still rides {@code repoId} alone.
   * @param repoName the repository's registered name, or null
   * @param target the full backing-branch ref, {@code refs/heads/release/<id>}
   * @param sources fully qualified refs ({@code refs/heads/main}, {@code refs/tags/2026.903.1}), in
   *     the order they should become parents of the fold
   * @param message the merge commit's message, for a person reading the log
   * @param resolutions at most one entry per path; a repeated path is refused by the far side
   */
  default Outcome merge(
      String repoId,
      String projectId,
      String repoName,
      String target,
      List<String> sources,
      String message,
      List<Resolution> resolutions) {
    return merge(repoId, projectId, repoName, target, sources, message, resolutions, false);
  }

  /**
   * The full form: {@code versionPins} asks the git host to decide, <b>itself and during this
   * call</b>, any text conflict in a {@code pom.xml} or {@code package.json} whose every conflicting
   * hunk differs only in version tokens — the newer version of each winning, numerically and never
   * as a string. A file it cannot decide stays a conflict exactly as it would have without the flag.
   *
   * <p>Unlike a {@link Resolution} this is not a decision made here and carried across: the git
   * host holds the conflict chunks and this side does not, so it is a permission, and what the far
   * side decided comes back as {@link Outcome#resolvedVersions}. A git host that predates the flag
   * ignores it, which is the fold it always made. The flag is still release vocabulary-free: it
   * says "version tokens may be ordered", not what a release is.
   *
   * <p>This form folds onto the target's tip; see the nine-argument form for a rebuild.
   */
  default Outcome merge(
      String repoId,
      String projectId,
      String repoName,
      String target,
      List<String> sources,
      String message,
      List<Resolution> resolutions,
      boolean versionPins) {
    return merge(
        repoId, projectId, repoName, target, sources, message, resolutions, versionPins, false);
  }

  /**
   * The full form: {@code rebuild} asks the git host to leave the target's own tip out of the
   * fold, so the result is built from {@code sources} alone and the target is moved onto it with a
   * lease, ancestor of the old tip or not. The parents of a rebuilt merge are the effective sources
   * in the order given; a target whose tip already has exactly those parents is {@code unchanged}.
   *
   * <p>Without it every fold is made onto the previous one, so a source that moves N times leaves
   * N two-parent merges on the target (51 commits on one request, measured 2026-10-09). A git host
   * that predates the flag ignores it and folds onto the tip, as it always did. Still no release
   * vocabulary: it says "this branch is a function of its sources", not what a release is.
   */
  Outcome merge(
      String repoId,
      String projectId,
      String repoName,
      String target,
      List<String> sources,
      String message,
      List<Resolution> resolutions,
      boolean versionPins,
      boolean rebuild);

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
   *
   * <p>{@link Outcome#resolvedVersions} is what the git host decided under {@code versionPins}, one
   * entry per pin; those paths are in {@link Outcome#resolved} too. Empty on every other fold, and
   * on every fold of a git host that predates the flag.
   */
  record Outcome(
      Result result,
      String sha,
      List<String> parents,
      List<Conflict> conflicts,
      String detail,
      List<String> resolved,
      List<ResolvedVersion> resolvedVersions) {

    public static Outcome merged(String sha, List<String> parents) {
      return merged(sha, parents, List.of());
    }

    /** A fold that took directives and applied them — the paths it decided, in the host's order. */
    public static Outcome merged(String sha, List<String> parents, List<String> resolved) {
      return merged(sha, parents, resolved, List.of());
    }

    /** A fold that also decided version pins on the git host's side. */
    public static Outcome merged(
        String sha,
        List<String> parents,
        List<String> resolved,
        List<ResolvedVersion> resolvedVersions) {
      return new Outcome(
          Result.MERGED,
          sha,
          List.copyOf(parents),
          List.of(),
          null,
          List.copyOf(resolved),
          List.copyOf(resolvedVersions));
    }

    public static Outcome fastForward(String sha, List<String> parents) {
      return fastForward(sha, parents, List.of());
    }

    public static Outcome fastForward(String sha, List<String> parents, List<String> resolved) {
      return new Outcome(
          Result.FAST_FORWARD,
          sha,
          List.copyOf(parents),
          List.of(),
          null,
          List.copyOf(resolved),
          List.of());
    }

    public static Outcome unchanged(String sha) {
      return new Outcome(Result.UNCHANGED, sha, List.of(), List.of(), null, List.of(), List.of());
    }

    public static Outcome conflict(String target, List<Conflict> conflicts) {
      return new Outcome(
          Result.CONFLICT, null, List.of(), List.copyOf(conflicts), target, List.of(), List.of());
    }

    public static Outcome unreachable(String detail) {
      return new Outcome(
          Result.UNREACHABLE, null, List.of(), List.of(), detail, List.of(), List.of());
    }

    /** Whether the target ref now names {@link #sha} — true for all three success words. */
    public boolean folded() {
      return result == Result.MERGED
          || result == Result.FAST_FORWARD
          || result == Result.UNCHANGED;
    }
  }

  /**
   * One version pin the git host decided under {@code versionPins}: in {@code path}, at {@code line}
   * (1-based, in the merged file), the two sides held {@code ours} and {@code theirs} and the fold
   * holds {@code chosen}.
   */
  record ResolvedVersion(String path, int line, String ours, String theirs, String chosen) {

    /**
     * The decision as one git trailer line, the shape of {@code ConflictResolver}'s {@code
     * Resolved-Gitlink}, so a person auditing either reads the same form.
     */
    public String trailer() {
      return "Resolved-Version: "
          + path
          + ':'
          + line
          + " ours="
          + ours
          + " theirs="
          + theirs
          + " -> "
          + chosen;
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

  /**
   * The {@code kind} of a conflict over bytes. A fold asked with {@code versionPins} has already had
   * the git host decide the manifests it could; one reported here is one it could not.
   */
  String KIND_FILE = "file";
}
