package eu.wohlben.qits.projects.control.approval;

import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>The fold changes {@value #DIRECTORY}</b> — a platform rule, not a setting. That directory tells
 * the platform how to gate, release and deploy the repository, so a request rewriting it changes the
 * rules every later request is held by, and a person sees that before it ships. Added, modified,
 * deleted and renamed paths all count, a rename by both of its paths, and machine requesters are not
 * exempt. <b>Gitlinks never count</b>: a wrapper moving a pin moves somebody else's tree, which that
 * repository's own request has already answered for.
 *
 * <p><b>Except a change that only declares what a release publishes</b>: that is {@link
 * ArtifactDeclarationSource}'s, which an agent may approve, and this source then says nothing.
 *
 * <p>Also the requirement an <b>unreadable</b> fold becomes ({@link ApprovalSubject#changes()}),
 * failing closed into a person.
 */
@ApplicationScoped
public class ConfigDirectorySource implements ApprovalRequirementSource {

  public static final String ID = "config-directory";

  /** The directory whose change in a fold puts a person in front of it. */
  public static final String DIRECTORY = ".config/qits/";

  /** The sentence; the paths are the detail. */
  public static final String REASON = "changes " + DIRECTORY;

  @Override
  public List<ApprovalRequirement> requirementsFor(ApprovalSubject subject) {
    List<CommitFileChangeDto> touched = touched(subject.changes());
    if (touched.isEmpty() || ReleaseDeclarations.declarationOnly(subject, touched)) {
      return List.of();
    }
    return List.of(ApprovalRequirement.person(ID, REASON, paths(touched)));
  }

  @Override
  public int order() {
    return 200;
  }

  /** The entries of {@code changes} under {@link #DIRECTORY}, gitlinks left out. */
  static List<CommitFileChangeDto> touched(List<CommitFileChangeDto> changes) {
    return changes.stream()
        .filter(file -> !file.touchesGitlink())
        .filter(file -> under(file.oldPath()) || under(file.path()))
        .toList();
  }

  /** The paths of {@code touched}, a rename by its old path and its new one, joined. */
  static String paths(List<CommitFileChangeDto> touched) {
    Set<String> paths = new LinkedHashSet<>();
    for (CommitFileChangeDto file : touched) {
      if (under(file.oldPath())) {
        paths.add(file.oldPath());
      }
      if (under(file.path())) {
        paths.add(file.path());
      }
    }
    return String.join(ApprovalRequirement.PATH_SEPARATOR, paths);
  }

  private static boolean under(String path) {
    return path != null && path.startsWith(DIRECTORY);
  }
}
