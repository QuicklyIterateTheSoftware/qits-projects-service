package eu.wohlben.qits.projects.control.approval;

import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * <b>The fold's only change under {@code .config/qits/} declares what a release publishes</b> — the
 * owner's 2026-10-18 rule, done by hand until now. The change is in {@code release.yml} and touches
 * only comments, blank lines, {@code artifacts:} list entries and the {@code contracts:} block; see
 * {@link ReleaseDeclarations} for the exact reading. A person <b>or the request's own agent</b> may
 * approve it (the epic's judgement call: declaring what a release publishes is the agent's own change
 * to acknowledge). Any other change under the directory is {@link ConfigDirectorySource}'s, and a slot
 * ({@code release-request:}, {@code release:}) is never a declaration.
 */
@ApplicationScoped
public class ArtifactDeclarationSource implements ApprovalRequirementSource {

  public static final String ID = "artifact-declarations";

  public static final String REASON = "declares what the release publishes in " + ReleaseDeclarations.PATH;

  @Override
  public List<ApprovalRequirement> requirementsFor(ApprovalSubject subject) {
    List<CommitFileChangeDto> touched = ConfigDirectorySource.touched(subject.changes());
    if (touched.isEmpty() || !ReleaseDeclarations.declarationOnly(subject, touched)) {
      return List.of();
    }
    return List.of(
        new ApprovalRequirement(
            ID,
            REASON,
            List.of(ApprovalRole.PERSON, ApprovalRole.AGENT),
            false,
            ConfigDirectorySource.paths(touched)));
  }

  @Override
  public int order() {
    return 300;
  }
}
