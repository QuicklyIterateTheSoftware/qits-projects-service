package eu.wohlben.qits.projects.contracts;

import eu.wohlben.qits.projects.control.GitExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * <b>A working tree whose shas are the same on every run and on every machine</b>, for the provider
 * states whose answers carry commits (a release request's fold, its commits, its changes).
 *
 * <p>A commit's sha is a hash of its tree, its parents, its author, its committer and both dates.
 * The tree is what the state writes and the parents follow from it, so fixing the people and the
 * clock fixes the sha: every commit is made by one seeder at {@code 2026-01-01T00:<minute>:00Z},
 * and the ambient git configuration is shut out (no global or system file), so no signing, line
 * ending or default-branch setting of the machine reaches a commit.
 *
 * <p>Files are staged by name, never with {@code add -A}: a gitlink has no directory in this tree,
 * and {@code add -A} would stage that absence as a deletion.
 */
final class SeededGit {

  /** 2026-01-01T00:00:00Z, the seeded clock's zero. */
  private static final long EPOCH = 1767225600L;

  private final GitExecutor git;
  private final Path work;

  private SeededGit(GitExecutor git, Path work) {
    this.git = git;
    this.work = work;
  }

  /** An empty repository on {@code main} whose {@code origin} is {@code remote}. */
  static SeededGit init(GitExecutor git, String remote) throws Exception {
    Path work = Files.createTempDirectory("contract-git");
    work.toFile().deleteOnExit();
    SeededGit seeded = new SeededGit(git, work);
    seeded.run(0, "git", "init", "-q", "-b", "main");
    seeded.run(0, "git", "remote", "add", "origin", remote);
    return seeded;
  }

  /** Writes and stages {@code files} (path to content). */
  void write(Map<String, String> files) throws Exception {
    for (Map.Entry<String, String> file : files.entrySet()) {
      Path path = work.resolve(file.getKey());
      Files.createDirectories(path.getParent());
      Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
      run(0, "git", "add", "--", file.getKey());
    }
  }

  /** Stages a mode-160000 entry at {@code path}: the tree entry is the whole of a gitlink. */
  void gitlink(String path, String sha) throws Exception {
    run(0, "git", "update-index", "--add", "--cacheinfo", "160000," + sha + "," + path);
  }

  /** Commits what is staged at the given minute and answers the new sha. */
  String commit(int minute, String message) throws Exception {
    run(minute, "git", "commit", "-q", "-m", message);
    return head();
  }

  /** A new branch from {@code from}, checked out. */
  void branch(String name, String from) throws Exception {
    run(0, "git", "checkout", "-q", "-b", name, from);
  }

  void checkout(String name) throws Exception {
    run(0, "git", "checkout", "-q", name);
  }

  void tag(String name) throws Exception {
    run(0, "git", "tag", name);
  }

  /** Merges {@code branches} into the checked-out branch as one merge commit, at the given minute. */
  String merge(int minute, String message, String... branches) throws Exception {
    String[] command = new String[5 + branches.length];
    command[0] = "git";
    command[1] = "merge";
    command[2] = "-q";
    command[3] = "--no-ff";
    command[4] = "-m" + message;
    System.arraycopy(branches, 0, command, 5, branches.length);
    run(minute, command);
    return head();
  }

  /**
   * Pushes every tag and the named branches to {@code origin}, forced: a repository the service
   * created already holds a first commit of its own, and the seeded history replaces it.
   */
  void push(String... branches) throws Exception {
    String[] command = new String[5 + branches.length];
    command[0] = "git";
    command[1] = "push";
    command[2] = "-q";
    command[3] = "--tags";
    command[4] = "origin";
    for (int i = 0; i < branches.length; i++) {
      command[5 + i] = "+" + branches[i];
    }
    run(0, command);
  }

  private String head() throws Exception {
    return run(0, "git", "rev-parse", "HEAD").trim();
  }

  private String run(int minute, String... command) throws Exception {
    String date = (EPOCH + 60L * minute) + " +0000";
    return git.exec(
        work.toFile(),
        Map.of(
            "GIT_CONFIG_GLOBAL", "/dev/null",
            "GIT_CONFIG_NOSYSTEM", "1",
            "GIT_AUTHOR_NAME", "Contract Seeder",
            "GIT_AUTHOR_EMAIL", "seeder@contract.example.test",
            "GIT_AUTHOR_DATE", date,
            "GIT_COMMITTER_NAME", "Contract Seeder",
            "GIT_COMMITTER_EMAIL", "seeder@contract.example.test",
            "GIT_COMMITTER_DATE", date),
        command);
  }
}
