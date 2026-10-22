package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.GitIdentity;
import eu.wohlben.qits.projects.control.GitMirrorRegistry;
import eu.wohlben.qits.projects.control.ProjectConfigParser;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.gitmirror.PushOutcome;
import eu.wohlben.qits.projects.gitmirror.PushSpec;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Commits a {@code .config/qits/project.yml} onto a wrapper's {@code main} with no working tree —
 * {@code amendTree} + {@code commit-tree} + a real {@link RepoMirror#push}, which is what every
 * commit this service makes does — so a suite can drive the project configuration reconcile off
 * real bytes.
 *
 * <p><b>The commit lands on the git host, not merely in the mirror's local ref.</b> A plain {@code
 * update-ref} in the mirror's own {@code gitDir} used to stand in for this — cheaper, and it read
 * back correctly while {@code readFile} trusted a throttled {@link RepoMirror#refresh()} inside its
 * freshness window. Once {@code readFile} fetches unconditionally ({@link RepoMirror#refreshNow()},
 * qits-1440), that fetch overwrites the hand-moved local ref back to whatever the host still holds
 * — nothing, since nothing was ever pushed — and every reconcile reading a "committed" change saw
 * the old content. Pushing for real is what a wrapper commit always was on the wire; see {@code
 * WrapperSubmoduleWriter#commit}.
 */
@ApplicationScoped
public class WrapperProjectYml {

  @Inject GitMirrorRegistry gitMirrors;
  @Inject GitIdentity gitIdentity;

  /**
   * Commits {@code content} at {@link ProjectConfigParser#CONFIG_PATH} on the wrapper's {@code main}.
   *
   * @return the new head of {@code main}
   */
  public String commit(Repository wrapper, String content) throws Exception {
    RepoMirror mirror = gitMirrors.of(wrapper.id);
    mirror.refreshNow();
    String tip =
        mirror
            .resolve("refs/heads/main")
            .orElseThrow(() -> new IllegalStateException("the wrapper has no main branch yet"));
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
    PushOutcome outcome = mirror.push(PushSpec.of(PushSpec.Ref.branch(commit, "main")));
    if (!outcome.accepted()) {
      throw new IllegalStateException(
          "Could not push project.yml onto the wrapper's main: " + outcome.output());
    }
    return commit;
  }
}
