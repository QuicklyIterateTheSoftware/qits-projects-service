package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.GitExecutor;
import eu.wohlben.qits.projects.control.GitIdentity;
import eu.wohlben.qits.projects.control.GitMirrorRegistry;
import eu.wohlben.qits.projects.control.ProjectConfigParser;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Commits a {@code .config/qits/project.yml} onto a wrapper's {@code main} with no working tree —
 * {@code amendTree} + {@code commit-tree} + {@code update-ref}, which is what every commit this
 * service makes does — so a suite can drive the project configuration reconcile off real bytes.
 */
@ApplicationScoped
public class WrapperProjectYml {

  @Inject GitMirrorRegistry gitMirrors;
  @Inject GitExecutor git;
  @Inject GitIdentity gitIdentity;

  /**
   * Commits {@code content} at {@link ProjectConfigParser#CONFIG_PATH} on the wrapper's {@code main}.
   *
   * @return the new head of {@code main}
   */
  public String commit(Repository wrapper, String content) throws Exception {
    RepoMirror mirror = gitMirrors.of(wrapper.id);
    String tip = git.exec(mirror.gitDir().toFile(), "git", "rev-parse", "refs/heads/main").trim();
    String tree =
        mirror.amendTree(
            tip,
            List.of(
                new RepoMirror.TreeEntry(
                    ProjectConfigParser.CONFIG_PATH,
                    "100644",
                    content.getBytes(StandardCharsets.UTF_8))),
            List.of(),
            List.of());
    String commit =
        mirror.commitTree(tree, List.of(tip), "Declare the project", gitIdentity.asCommitIdentity());
    git.exec(mirror.gitDir().toFile(), "git", "update-ref", "refs/heads/main", commit);
    return commit;
  }
}
