package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ReleaseGitHost;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The suite's {@link ReleaseGitHost}: a git host that is a map. An ordinary bean over the {@code
 * @DefaultBean} HTTP adapter, so it wins the injection simply by existing and no test reaches a git
 * host; state through <b>methods</b>, the package convention (the injected reference is a CDI client
 * proxy).
 *
 * <p><b>It is a real little repository, not a stub that says yes.</b> The tree is a path → content
 * map, a commit rewrites those entries and mints a fresh sha, and a tag is a name in a set — so the
 * happy path really reads the manifests it bumps, really commits the bytes the bump produced, and
 * the assertions can be about <em>what the poms say afterwards</em> rather than about which methods
 * were called. That is what makes the tag-exists retry arm meaningful: the second attempt has to
 * re-read the tree the first attempt's commit left behind.
 */
@ApplicationScoped
public class RecordingReleaseGitHost implements ReleaseGitHost {

  /** One commit as it was asked for. */
  public record Commit(
      String ref,
      String message,
      Map<String, String> files,
      Map<String, String> gitlinks,
      String sha) {}

  /** One tag as it was asked for, and what the host said. */
  public record Tag(String name, String sha, String message, TagResult result) {}

  /** The tree, keyed by the sha it belongs to. */
  private final Map<String, Map<String, String>> trees =
      Collections.synchronizedMap(new LinkedHashMap<>());

  private final List<Commit> commits = Collections.synchronizedList(new ArrayList<>());
  private final List<Tag> tags = Collections.synchronizedList(new ArrayList<>());
  private final List<String> deletedBranches = Collections.synchronizedList(new ArrayList<>());

  /** Tag names the host already holds — the version-uniqueness refusal, staged. */
  private final List<String> taken = Collections.synchronizedList(new ArrayList<>());

  /** Branch heads by {@code <repoId>@<branch>} — the port's branch-resolution read, staged. */
  private final Map<String, Answer<String>> heads =
      Collections.synchronizedMap(new LinkedHashMap<>());

  /**
   * Gitlink pins by {@code <rev>|<path>} — the mode-160000 entries of a staged tree. Kept beside the
   * trees rather than in them, because a pin is not content: the file map is what {@code tree} and
   * {@code file} answer from, and a gitlink has neither bytes nor a path a blob read could hit.
   */
  private final Map<String, Answer<String>> pins =
      Collections.synchronizedMap(new LinkedHashMap<>());

  /**
   * Commits the fake's repositories hold, keyed {@code <repoName>@<sha>} — what {@code resolves}
   * says yes to. A pin nobody staged is a pin the host cannot resolve, which is the guard's whole
   * subject and the state a test gets by simply not staging one.
   */
  private final Map<String, Answer<Boolean>> resolvable =
      Collections.synchronizedMap(new LinkedHashMap<>());

  public RecordingReleaseGitHost() {
    // A class that never calls reset() still has an ordinary repository's main to be gated by.
    trees.put("refs/heads/main", new LinkedHashMap<>(GATED_MAIN));
  }

  private final AtomicInteger commitCounter = new AtomicInteger();

  /** How many more tags answer {@code tag-exists} whatever they are named. */
  private final AtomicInteger tagCollisions = new AtomicInteger();

  /** Scripted failures, each replacing the ordinary answer of one verb. */
  private final AtomicReference<Answer<List<String>>> treeFailure = new AtomicReference<>();

  private final AtomicReference<Answer<String>> commitFailure = new AtomicReference<>();

  private final AtomicReference<TagAnswer> tagFailure = new AtomicReference<>();

  // ---------------------------------------------------------------------------------------------
  // Staging
  // ---------------------------------------------------------------------------------------------

  /**
   * The per-release-request CI recipe, and the reason it has a name here: its presence on a
   * repository's {@code main} is the <b>CI gate</b>, which {@code ReleaseGates} reads through this
   * fake. Almost every repository on the platform carries it, so {@link #reset} stages it — see
   * {@link #GATED_MAIN}.
   */
  public static final String CI_RECIPE = ".config/qits/ci-event-release-request.yml";

  /**
   * What an ordinary repository's {@code main} looks like to the gate resolver: a release-request
   * recipe and nothing else, so the CI gate applies and neither the approval nor the deployment gate
   * does. <b>{@link #reset} stages it at {@code refs/heads/main}</b>, because that is the state
   * almost every repository on this platform is in and the state every suite here was written
   * against — a fake whose main could not be read would put every request in front of an unknown
   * gate set instead.
   *
   * <p>A test wanting a different configuration stages {@code refs/heads/main} itself, which
   * replaces this; one wanting an <em>unreadable</em> one calls {@link #mainUnreadable}.
   */
  public static final Map<String, String> GATED_MAIN = Map.of(CI_RECIPE, "steps: []\n");

  /** Put a tree at a sha — what a release will read its manifests out of. */
  public void tree(String sha, Map<String, String> files) {
    trees.put(sha, new LinkedHashMap<>(files));
  }

  /**
   * The same, with the CI recipe added — for a test that stages {@code refs/heads/main} for some
   * other reader (the estate gate reads the wrapper's branches) and does not mean to change which
   * gates the repository configures.
   */
  public void gatedTree(String rev, Map<String, String> files) {
    Map<String, String> tree = new LinkedHashMap<>(files);
    tree.putAll(GATED_MAIN);
    trees.put(rev, tree);
  }

  /** Leave {@code main} unreadable, so the gate set resolves UNKNOWN. */
  public void mainUnreadable() {
    trees.remove("refs/heads/main");
  }

  /**
   * A tree belonging to ONE repository at one rev, which wins over the rev-keyed staging above.
   *
   * <p>The fake was keyed by rev alone because every reader here was a release, and a release is
   * about one repository at a time. {@code ReleaseGates} broke that: a test with two repositories in
   * it — a wrapper that requires manual review and a plain repository that does not — needs two
   * different {@code refs/heads/main}s at once, and a rev-keyed map can only hold one.
   */
  public void treeFor(String repoId, String rev, Map<String, String> files) {
    trees.put(repoId + "|" + rev, new LinkedHashMap<>(files));
  }

  /** {@link #treeFor} with the CI recipe added, so staging a main does not remove the CI gate. */
  public void gatedTreeFor(String repoId, String rev, Map<String, String> files) {
    Map<String, String> tree = new LinkedHashMap<>(files);
    tree.putAll(GATED_MAIN);
    trees.put(repoId + "|" + rev, tree);
  }

  /** The tree a reader of {@code (repoId, rev)} sees: this repository's own, else the rev's. */
  private Map<String, String> treeOf(String repoId, String rev) {
    Map<String, String> own = trees.get(repoId + "|" + rev);
    return own != null ? own : trees.get(rev);
  }

  /** A tag name the host already holds, so the next attempt at it answers {@code tag-exists}. */
  public void alreadyTagged(String name) {
    taken.add(name);
  }

  /** Stage a branch head, as {@code head} will answer it. */
  public void headOf(String repoId, String branch, String sha) {
    heads.put(repoId + "@" + branch, Answer.of(sha));
  }

  /** Stage a branch whose head read fails. */
  public void headUnreadable(String repoId, String branch, Answer<String> answer) {
    heads.put(repoId + "@" + branch, answer);
  }

  /** Pin {@code path} at {@code rev} to a commit, as a mode-160000 entry of that tree. */
  public void pin(String rev, String path, String sha) {
    pins.put(rev + "|" + path, Answer.of(sha));
  }

  /** Stage a pin read that fails, so the guard's classified refusal arm is reachable. */
  public void pinUnreadable(String rev, String path, Answer<String> answer) {
    pins.put(rev + "|" + path, answer);
  }

  /** Say that the repository named {@code repoName} holds {@code sha}. */
  public void holds(String repoName, String sha) {
    resolvable.put(repoName + "@" + sha, Answer.of(true));
  }

  /** Stage a resolution read that fails outright — neither a yes nor a no. */
  public void resolutionUnreadable(String repoName, String sha, Answer<Boolean> answer) {
    resolvable.put(repoName + "@" + sha, answer);
  }

  /**
   * Refuse the next {@code times} tags as already existing, whatever they are named.
   *
   * <p>Staged by count rather than by name because the caller cannot know the name: the version is
   * stamped from the clock inside the attempt. That is the same reason the real collision is
   * reachable at all — two releases in one second stamp one string.
   */
  public void refuseTagsAsExisting(int times) {
    tagCollisions.set(times);
  }

  public void failTreeWith(Answer<List<String>> answer) {
    treeFailure.set(answer);
  }

  public void failCommitWith(Answer<String> answer) {
    commitFailure.set(answer);
  }

  public void failTagWith(TagAnswer answer) {
    tagFailure.set(answer);
  }

  public void reset() {
    trees.clear();
    trees.put("refs/heads/main", new LinkedHashMap<>(GATED_MAIN));
    commits.clear();
    tags.clear();
    deletedBranches.clear();
    taken.clear();
    pins.clear();
    resolvable.clear();
    commitCounter.set(0);
    tagCollisions.set(0);
    treeFailure.set(null);
    commitFailure.set(null);
    tagFailure.set(null);
  }

  // ---------------------------------------------------------------------------------------------
  // Reading back
  // ---------------------------------------------------------------------------------------------

  public List<Commit> commits() {
    return List.copyOf(commits);
  }

  public List<Tag> tags() {
    return List.copyOf(tags);
  }

  /** Only the tags that were actually created — the released versions. */
  public List<String> createdTags() {
    return tags().stream()
        .filter(tag -> tag.result() == TagResult.CREATED)
        .map(Tag::name)
        .toList();
  }

  public List<String> deletedBranches() {
    return List.copyOf(deletedBranches);
  }

  /** The tree at a sha, as it stands — a commit's effect, read back. */
  public Map<String, String> treeAt(String sha) {
    Map<String, String> tree = trees.get(sha);
    return tree == null ? Map.of() : Map.copyOf(tree);
  }

  // ---------------------------------------------------------------------------------------------
  // The port
  // ---------------------------------------------------------------------------------------------

  /**
   * <b>A scripted tree failure does not reach {@code refs/heads/main} while one is staged there.</b>
   * Two different readers ask this verb now — a release reading the fold it is about to bump, and
   * {@code ReleaseGates} reading what the repository configures — and {@link #failTreeWith} exists
   * for the first. Letting it answer the second too would turn every test about a git host that
   * could not be read at the release into a test about a request held by an unknown gate set, which
   * is a different assertion and not the one those tests make. A test that wants the whole host
   * unreadable calls {@link #mainUnreadable} as well.
   */
  @Override
  public Answer<List<String>> tree(String repoId, String rev) {
    Answer<List<String>> failure = treeFailure.get();
    if (failure != null && !("refs/heads/main".equals(rev) && trees.containsKey(rev))) {
      return failure;
    }
    Map<String, String> tree = treeOf(repoId, rev);
    return tree == null
        ? Answer.failed("no-such-rev: " + rev)
        : Answer.of(List.copyOf(tree.keySet()));
  }

  @Override
  public Answer<String> file(String repoId, String rev, String path) {
    Map<String, String> tree = treeOf(repoId, rev);
    if (tree == null) {
      return Answer.failed("no-such-rev: " + rev);
    }
    String content = tree.get(path);
    return content == null ? Answer.failed("no-such-path: " + path) : Answer.of(content);
  }

  @Override
  public Answer<String> commit(
      String repoId,
      String ref,
      String message,
      Map<String, String> files,
      Map<String, String> gitlinks) {
    Answer<String> failure = commitFailure.get();
    if (failure != null) {
      return failure;
    }
    // The tip a commit lands on is the newest tree this fake holds, which is what the executor's
    // own sequencing produces: it reads a tree, bumps it and commits onto the branch that tree is.
    // A gitlink is not readable content, so it joins the recorded commit and never the tree.
    String parent = newestSha();
    Map<String, String> tree = new LinkedHashMap<>(trees.getOrDefault(parent, Map.of()));
    tree.putAll(files);
    String sha = "bumped-" + commitCounter.incrementAndGet();
    trees.put(sha, tree);
    commits.add(
        new Commit(
            ref,
            message,
            Map.copyOf(files),
            gitlinks == null ? Map.of() : Map.copyOf(gitlinks),
            sha));
    return Answer.of(sha);
  }

  @Override
  public Answer<String> head(String repoId, String branch) {
    Answer<String> staged = heads.get(repoId + "@" + branch);
    return staged != null ? staged : Answer.failed("no-such-branch: " + branch + " of " + repoId);
  }

  /**
   * The pin at a path, or an ok answer carrying null where nothing was staged — the port's own
   * "nothing is pinned here", which is what a declared-but-unpinned entry looks like.
   */
  @Override
  public Answer<String> gitlinkAt(String projectId, String repoName, String rev, String path) {
    Answer<String> staged = pins.get(rev + "|" + path);
    return staged != null ? staged : Answer.of(null);
  }

  /**
   * Whether a repository holds a commit. Nothing staged is a plain <b>no</b> rather than a failure:
   * this fake is a git host that holds only what a test put in it, and a pin naming a commit nobody
   * put anywhere is exactly the broken estate the guard exists to catch.
   */
  @Override
  public Answer<Boolean> resolves(String projectId, String repoName, String sha) {
    Answer<Boolean> staged = resolvable.get(repoName + "@" + sha);
    return staged != null ? staged : Answer.of(false);
  }

  @Override
  public TagAnswer tag(String repoId, String name, String sha, String message) {
    TagAnswer failure = tagFailure.get();
    if (failure != null) {
      tags.add(new Tag(name, sha, message, TagResult.FAILED));
      return failure;
    }
    if (tagCollisions.get() > 0 || taken.contains(name)) {
      tagCollisions.decrementAndGet();
      tags.add(new Tag(name, sha, message, TagResult.ALREADY_EXISTS));
      return TagAnswer.alreadyExists("existing-tag-object");
    }
    taken.add(name);
    tags.add(new Tag(name, sha, message, TagResult.CREATED));
    return TagAnswer.created("tag-object-of-" + name);
  }

  @Override
  public void deleteBranch(String repoId, String name) {
    deletedBranches.add(name);
  }

  private String newestSha() {
    String newest = null;
    synchronized (trees) {
      for (String sha : trees.keySet()) {
        newest = sha;
      }
    }
    return newest;
  }
}
