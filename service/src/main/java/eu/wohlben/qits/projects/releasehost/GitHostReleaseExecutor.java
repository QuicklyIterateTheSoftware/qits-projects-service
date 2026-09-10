package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ManifestVersionBump;
import eu.wohlben.qits.projects.control.ReleaseAnnouncer;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import eu.wohlben.qits.projects.control.ReleaseGitHost;
import eu.wohlben.qits.projects.control.VersionStamp;
import eu.wohlben.qits.projects.control.WrapperGitmodules;
import eu.wohlben.qits.projects.error.ManifestBumpException;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>The release.</b> A gated release request arrives here and leaves as a tag on qits-githost.
 *
 * <p>This is what replaced the qits-workspaces release door on 2026-09-03, and the shape of the
 * flow changed with the caller: that door merged a branch into {@code main}, bumped, committed,
 * pushed, tagged and promoted onto a deploy branch — one operation whose every step wrote a ref
 * somebody else's CI was watching. <b>A release is a tag now.</b> {@code main} is finalized after
 * the deployment, by a later arm of this epic; what happens here is the smallest thing that can be
 * called a release:
 *
 * <ol>
 *   <li><b>Stamp</b> a calver, {@link VersionStamp} — {@code YYYY.MMDD.HHMMSS}, UTC, once per
 *       attempt and threaded through, because a slow bump would otherwise write two versions into
 *       one commit.
 *   <li><b>Bump</b> the manifests, {@link ManifestVersionBump} — read the fold's tree and its poms
 *       and package.jsons through {@link ReleaseGitHost}, splice the version into them, and hand
 *       back the new bytes. There is no worktree on this side: the fold exists only on the git host.
 *   <li><b>Commit</b> them onto the backing branch. This is the last commit before the tag and its
 *       sha is what gets tagged, and <b>the version bump is the whole of what it carries</b>. A
 *       repository that renders no version commits nothing and tags the fold itself, which is a
 *       release like any other.
 *   <li><b>Tag</b> that commit with the version. A {@code 409 tag-exists} is <b>the platform's
 *       version-uniqueness guarantee</b>, not an error: somebody released that second already, so
 *       the whole attempt starts again with a fresh stamp. Bounded at {@link #ATTEMPTS}, because a
 *       name that stays taken past three seconds is not a same-second tie.
 *   <li><b>Delete</b> the branches the release consumed — the named sources and the backing branch
 *       — best effort, because by now the tag exists and nothing after it may pretend it does not.
 *       <b>Never the default branch</b>, which is stated by the caller rather than guessed at.
 *   <li><b>Announce</b> {@code SCMRelease}, over {@link ReleaseAnnouncer}, at the moment the tag was
 *       accepted. qits-projects is that event's publisher now; its payload is unchanged.
 * </ol>
 *
 * <p><b>The WRAPPER's release used to bank its estate here, and it must not.</b> That arm rewrote
 * every declared gitlink to the head of each submodule's {@code main} at the instant of the release
 * — after the gate, after the person approving had read the fold, and inside the commit the tag then
 * named. So the estate that shipped was neither built nor reviewed: CI could not check it, no diff
 * could show it, and a wrapper release could not be *said* to release anything in particular,
 * because what it pinned was decided by what happened to be on other people's branches a second
 * after somebody said yes. A wrapper's pins are part of its content — they <em>are</em> its content
 * — so they are written onto the source branch now, ahead of the fold, where they are gated like
 * every other byte and approved like every other byte. A release that rewrote them would be
 * shipping something nobody read, which is exactly what the approval exists to stop, so this class
 * writes no gitlink at all and asks nothing about archetypes.
 *
 * <p>What is left of that arm is one guard about the <b>fold</b>, {@link #unresolvablePin} — a
 * declared pin naming a commit that is nowhere breaks every clone, and the old {@code head()} read
 * made that impossible by construction. It runs against the approved fold before anything is
 * stamped.
 *
 * <p>Recording the tag as pending a merge to {@code main}, and moving the request to RELEASED, are
 * deliberately <b>not</b> here: they are rows in this service's own database and belong to {@code
 * ReleaseRequests}, which calls this and settles what comes back.
 *
 * <p><b>Never throws, and every failure is classified.</b> {@code retryable} is the port's word for
 * "the moment failed" — an unreachable git host, a 5xx, a ref that moved under us, a tag name that
 * stayed taken — and the sweep retries exactly those. A manifest that will not parse, a backing
 * branch that is gone and a rev that does not resolve answer the same forever and are refusals about
 * the ask, which the sweep leaves standing until a re-arm changes it.
 *
 * <p><b>Where the retry restarts is the whole of the tag-exists arm.</b> A fresh attempt re-reads
 * the tree at the tip the previous attempt left behind and re-bumps it to the new version, rather
 * than re-tagging the same commit under another name: the manifests inside the commit have to carry
 * the version the tag says, or the released artifacts would name a version nothing built.
 */
@ApplicationScoped
@DefaultBean
public class GitHostReleaseExecutor implements ReleaseExecutor {

  private static final Logger LOG = Logger.getLogger(GitHostReleaseExecutor.class);

  /**
   * How many calvers one release will burn on {@code tag-exists}. The stamp has one-second
   * resolution, so a tie is a genuine same-second race with a sibling release and one more attempt
   * settles it; a name still taken on the third attempt is something else, and knocking is not what
   * fixes it.
   */
  static final int ATTEMPTS = 3;

  /**
   * The one path that says a fold is a superproject. It is the file git itself reads, so a fold that
   * declares it declares submodules whoever the repository belongs to and whatever archetype its row
   * carries — which is why the pin guard keys off this and not off a kind.
   */
  private static final String GITMODULES = ".gitmodules";

  @Inject ReleaseGitHost gitHost;

  @Inject Instance<ReleaseAnnouncer> announcers;

  @Override
  public Outcome release(Release release) {
    Outcome broken = unresolvablePin(release);
    if (broken != null) {
      return broken;
    }
    String ref = "refs/heads/" + release.backingBranch();
    // Where the next attempt reads its tree from: the fold to begin with, then whatever the last
    // attempt's bump commit left on the branch.
    String tip = release.mergedSha();

    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      String version = VersionStamp.of(Instant.now());

      ManifestVersionBump.Result bumped;
      try {
        bumped = ManifestVersionBump.stamp(new GitHostTree(release.repoId(), tip), version);
      } catch (Unreadable e) {
        // A read that failed for a reason of the moment: the port already classified it.
        return e.retryable()
            ? Outcome.refusedRetryable("the manifests could not be read: " + e.getMessage())
            : Outcome.refused("the manifests could not be read: " + e.getMessage());
      } catch (ManifestBumpException e) {
        // A manifest that will not parse, or declares no version. Final until something changes it.
        return Outcome.refused("the manifests could not be stamped: " + e.getMessage());
      }

      String tagged;
      if (bumped.files().isEmpty()) {
        // Nothing renders a version. The fold itself is what the tag names — a stackless repository
        // releases exactly like every other one, it just has no commit before its tag. A wrapper
        // carrying nothing but pins is that case now, and the tag names the approved fold exactly:
        // empty means empty, and there is no second thing to weigh here.
        tagged = tip;
      } else {
        ReleaseGitHost.Answer<String> commit =
            gitHost.commit(
                release.repoId(),
                ref,
                "release(" + version + "): " + summaryOf(release),
                bumped.files(),
                Map.of());
        if (!commit.ok()) {
          return refusal("the version bump could not be committed: " + commit.detail(), commit.retryable());
        }
        tagged = commit.value();
      }

      ReleaseGitHost.TagAnswer tag =
          gitHost.tag(
              release.repoId(), version, tagged, "release(" + version + "): " + summaryOf(release));
      switch (tag.result()) {
        case CREATED -> {
          Instant releasedAt = Instant.now();
          LOG.infof(
              "Release request %s tagged %s at %s (%d manifest(s) bumped)",
              release.requestId(), version, tagged, bumped.files().size());
          // Everything past here is after the fact: the tag exists and the release happened, so
          // neither a failed branch delete nor a failed announcement may turn it into a failure.
          deleteConsumedBranches(release);
          announce(release, version, tagged, releasedAt);
          return Outcome.released(version, tagged);
        }
        case ALREADY_EXISTS -> {
          // Somebody released this second. Stamp again — and re-bump, because the manifests inside
          // the commit have to carry the version the tag says.
          LOG.infof(
              "Release request %s: %s is already tagged; re-stamping (attempt %d of %d)",
              release.requestId(), version, attempt, ATTEMPTS);
          tip = tagged;
        }
        case FAILED -> {
          return refusal("the release could not be tagged: " + tag.detail(), tag.retryable());
        }
      }
    }
    // Every attempt collided. That is not a fact about this request, so the sweep tries again.
    return Outcome.refusedRetryable(
        "every one of " + ATTEMPTS + " stamped versions was already tagged; something else is"
            + " releasing this repository");
  }

  private static Outcome refusal(String detail, boolean retryable) {
    return retryable ? Outcome.refusedRetryable(detail) : Outcome.refused(detail);
  }

  /**
   * <b>A declared pin naming a commit the git host cannot resolve refuses the release.</b> The one
   * thing kept from the banking arm, and it is cheaper and about something else: banking read every
   * submodule's head, so a pin could not name a commit that was not there — it was made of one.
   * Nothing writes the pins here any more, so a fold can now arrive carrying a sha that is nowhere:
   * a rewritten branch, a repository restored from an older copy, a hand-edited entry. Git records
   * a gitlink without ever resolving it, so nothing notices — not the commit, not the gate, not the
   * person approving, and not the tag — until somebody's {@code submodule update --init} stops, and
   * by then the release is out and the tag is immutable. Refusing here is the last moment it costs
   * nothing.
   *
   * <p><b>It runs whenever the fold declares {@code .gitmodules}, not when the repository is a
   * WRAPPER</b>, and that is the whole point rather than a shortcut around the catalog this class no
   * longer carries. The archetype was never what made the check necessary — the pins were, and a
   * superproject is a fold that declares submodules whatever anybody has typed on a row. Reading it
   * off the fold means the guard cannot be turned off by mislabelling a repository, cannot go stale
   * against an archetype somebody changes later, and asks nothing of the release path that the
   * release path does not already hold. A boolean on the ask would have read honestly too, and it
   * would have been the same mistake one size smaller: a second statement of a fact the content
   * already makes.
   *
   * <p>It costs one tree listing on every release and nothing else on a repository with no
   * submodules — {@code .gitmodules} is either in that listing or it is not, and the listing is a
   * read this class was going to make anyway one line later.
   *
   * <p>Three answers, deliberately distinct. An entry {@code .gitmodules} declares but the tree does
   * not pin is <b>not</b> a refusal: that is a fold somebody is still writing, and the guard is
   * about pins that lie rather than about pins that are missing. A read that failed is classified as
   * the port classified it, so an unreachable git host stalls the release instead of condemning the
   * estate. And a pin the host answered "no" about is a plain refusal naming the entry, final until
   * the fold moves — because it will answer the same until somebody fixes the pin.
   *
   * @return the refusal, or null when there is nothing to refuse
   */
  private Outcome unresolvablePin(Release release) {
    ReleaseGitHost.Answer<List<String>> tree = gitHost.tree(release.repoId(), release.mergedSha());
    if (!tree.ok()) {
      return refusal("the fold's tree could not be read: " + tree.detail(), tree.retryable());
    }
    if (!tree.value().contains(GITMODULES)) {
      return null; // Not a superproject. Nothing declares a pin, so nothing can pin a ghost.
    }
    if (release.projectId() == null || release.repoName() == null) {
      // Both reads below are name-addressed, which is the only scheme the pinned repositories can be
      // reached under (see ReleaseGitHost.gitlinkAt). A fold with no project or no registered name
      // has no address to ask about, and a guard that cannot ask says so rather than refusing a
      // release on the strength of a question it never put.
      LOG.warnf(
          "Release request %s declares submodules, but %s has no project and name to resolve their"
              + " pins under; the pins go unchecked",
          release.requestId(), release.repoId());
      return null;
    }
    ReleaseGitHost.Answer<String> gitmodules =
        gitHost.file(release.repoId(), release.mergedSha(), GITMODULES);
    if (!gitmodules.ok()) {
      return refusal(
          "the fold declares " + GITMODULES + " but it could not be read: " + gitmodules.detail(),
          gitmodules.retryable());
    }
    for (WrapperGitmodules.Entry entry : WrapperGitmodules.entries(gitmodules.value())) {
      if (entry.path() == null || entry.path().isBlank()) {
        continue; // A section with no path pins nothing; WrapperGitmodules keeps it for the name.
      }
      ReleaseGitHost.Answer<String> pin =
          gitHost.gitlinkAt(
              release.projectId(), release.repoName(), release.mergedSha(), entry.path());
      if (!pin.ok()) {
        return refusal(
            "the pin " + entry.name() + " declares at " + entry.path()
                + " could not be read: " + pin.detail(),
            pin.retryable());
      }
      if (pin.value() == null) {
        continue; // Declared, not pinned. An unfinished fold, and not this guard's business.
      }
      ReleaseGitHost.Answer<Boolean> resolves =
          gitHost.resolves(release.projectId(), entry.name(), pin.value());
      if (!resolves.ok()) {
        return refusal(
            "whether " + entry.name() + " holds the commit " + pin.value()
                + " that this fold pins could not be established: " + resolves.detail(),
            resolves.retryable());
      }
      if (!Boolean.TRUE.equals(resolves.value())) {
        return Outcome.refused(
            "This fold pins "
                + entry.name()
                + " at "
                + entry.path()
                + " to the commit "
                + pin.value()
                + ", which the git host cannot resolve. A pin naming a commit that is not there"
                + " breaks every clone of this repository, so it is not released; correct the"
                + " gitlink on the branch and fold again.");
      }
    }
    return null;
  }

  private static String summaryOf(Release release) {
    return release.summary() == null || release.summary().isBlank()
        ? "release request " + release.requestId()
        : release.summary();
  }

  /**
   * The branches this release consumed: its named sources and its own backing branch.
   *
   * <p><b>The default branch is never among them</b>, and the exclusion is made from the value the
   * caller read off the repository row rather than from the string {@code "main"} — a repository
   * whose default branch is called something else must not have it deleted by a spelling mistake.
   * qits-githost refuses its own default branch too, which makes this the near half of a seatbelt
   * rather than the only one.
   */
  private void deleteConsumedBranches(Release release) {
    Set<String> branches = new LinkedHashSet<>(release.namedSources());
    branches.remove(release.defaultBranch());
    branches.add(release.backingBranch());
    for (String branch : branches) {
      gitHost.deleteBranch(release.repoId(), branch);
    }
  }

  /**
   * Fire and forget, outside everything, and never able to fail a release that already happened.
   *
   * <p>{@code tagged} is the commit the tag was created at — the bump commit, or the fold where
   * nothing renders a version — and it rides out as the event's {@code commitSha}. It is the same
   * value {@link ReleaseExecutor.Outcome#released} returns and the same one {@code
   * ReleasedTagPendingMerge.releasedSha} records, deliberately: three statements about one release
   * that a reader can join, where before this the bus half named a tag and no commit and a release
   * pipeline had to go and find the commit itself.
   */
  private void announce(Release release, String version, String tagged, Instant releasedAt) {
    if (!announcers.isResolvable()) {
      return;
    }
    try {
      announcers
          .get()
          .onReleased(
              release.projectId(),
              release.repoId(),
              release.repoName(),
              release.backingBranch(),
              version,
              tagged,
              releasedAt,
              release.priority());
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not announce the release of %s as %s", release.repoId(), version);
    }
  }

  /**
   * A git-host read that failed, carried out of {@link ManifestVersionBump.Source#read} — which may
   * only throw {@link ManifestBumpException} — with the port's classification intact. Extending that
   * type is what lets the bump engine stay ignorant of HTTP while the executor still tells "the host
   * was unreachable" from "this pom is malformed", which are opposite answers to "retry?".
   */
  private static final class Unreadable extends ManifestBumpException {
    private final boolean retryable;

    Unreadable(String message, boolean retryable) {
      super(message);
      this.retryable = retryable;
    }

    boolean retryable() {
      return retryable;
    }
  }

  /** One commit's tree at the git host, as the bump engine reads it. */
  private final class GitHostTree implements ManifestVersionBump.Source {

    private final String repoId;
    private final String rev;
    private List<String> paths;

    private GitHostTree(String repoId, String rev) {
      this.repoId = repoId;
      this.rev = rev;
    }

    @Override
    public List<String> paths() {
      if (paths == null) {
        ReleaseGitHost.Answer<List<String>> answer = gitHost.tree(repoId, rev);
        if (!answer.ok()) {
          throw new Unreadable(answer.detail(), answer.retryable());
        }
        paths = answer.value();
      }
      return paths;
    }

    @Override
    public String read(String path) {
      ReleaseGitHost.Answer<String> answer = gitHost.file(repoId, rev, path);
      if (!answer.ok()) {
        throw new Unreadable(answer.detail(), answer.retryable());
      }
      return answer.value();
    }
  }
}
