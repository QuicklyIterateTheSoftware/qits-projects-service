package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.approval.ApprovalRequirement;
import eu.wohlben.qits.projects.control.approval.ApprovalRole;
import eu.wohlben.qits.projects.control.approval.ApprovalSubject;
import eu.wohlben.qits.projects.control.approval.ConfigDirectorySource;
import eu.wohlben.qits.projects.control.approval.FoldUnreadable;
import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Which requirement a project's {@code front_desk.delivery.approves} admits its delivery desk to —
 * {@link ApprovalPolicy#deskAdmissible}, the one reading of the list (qits-1517 widened it with the
 * {@code automation} and {@code contract} families, qits-1560 with {@code config-directory}).
 */
class ApprovalPolicyDeskAdmissionTest {

  private static final String PROJECT = "project-a";

  private static final Set<String> EVERYTHING = ProjectConfig.DeliverySettings.APPROVABLE;

  private static final List<ApprovalRole> AGENT_OR_PERSON =
      List.of(ApprovalRole.AGENT, ApprovalRole.PERSON);

  private static ApprovalRequirement requirement(String id, List<ApprovalRole> admits) {
    return new ApprovalRequirement(id, "because", admits, false, null);
  }

  @Test
  void anAutomationRequirementIsAdmittedOnlyWhenTheListNamesAutomation() {
    ApprovalRequirement diagram = requirement("automation:entity-diagram", AGENT_OR_PERSON);
    assertTrue(ApprovalPolicy.deskAdmissible(diagram, Set.of("automation"), PROJECT));
    assertTrue(ApprovalPolicy.deskAdmissible(diagram, EVERYTHING, PROJECT));
    assertFalse(ApprovalPolicy.deskAdmissible(diagram, Set.of("manual-review"), PROJECT));
    assertFalse(
        ApprovalPolicy.deskAdmissible(diagram, Set.of("automation:entity-diagram"), PROJECT),
        "the list names the family, not a member");
  }

  /** The source's fail-closed readings ask a person alone, and stay a person's. */
  @Test
  void aFailClosedAutomationRequirementIsNeverAdmitted() {
    assertFalse(
        ApprovalPolicy.deskAdmissible(
            ApprovalRequirement.person("automation:catalogue", "unread", null),
            EVERYTHING,
            PROJECT));
    assertFalse(
        ApprovalPolicy.deskAdmissible(
            requirement("automation:catalogue", AGENT_OR_PERSON), EVERYTHING, PROJECT));
    assertFalse(
        ApprovalPolicy.deskAdmissible(
            ApprovalRequirement.person("automation:screenshot-baselines", "unreadable", null),
            EVERYTHING,
            PROJECT));
  }

  /** A contract requirement is the desk's only where its consumer is the request's own project. */
  @Test
  void aContractRequirementIsAdmittedOnlyForTheRequestsOwnProject() {
    ApprovalRequirement own =
        requirement(
            "contract:a", List.of(ApprovalRole.PERSON, ApprovalRole.agentOf(PROJECT)));
    ApprovalRequirement other =
        requirement(
            "contract:b", List.of(ApprovalRole.PERSON, ApprovalRole.agentOf("project-b")));
    assertTrue(ApprovalPolicy.deskAdmissible(own, Set.of("contract"), PROJECT));
    assertFalse(ApprovalPolicy.deskAdmissible(own, Set.of("automation"), PROJECT));
    assertFalse(ApprovalPolicy.deskAdmissible(other, EVERYTHING, PROJECT));
  }

  @Test
  void theTwoNamedRequirementsAreAdmittedByTheirOwnName() {
    ApprovalRequirement manual = ApprovalRequirement.person("manual-review", "configured", null);
    assertTrue(ApprovalPolicy.deskAdmissible(manual, Set.of("manual-review"), PROJECT));
    assertFalse(ApprovalPolicy.deskAdmissible(manual, Set.of("automation", "contract"), PROJECT));
    ApprovalRequirement declarations = requirement("artifact-declarations", AGENT_OR_PERSON);
    assertTrue(
        ApprovalPolicy.deskAdmissible(declarations, Set.of("artifact-declarations"), PROJECT));
  }

  /**
   * qits-1560: a fold changing {@code .config/qits/} admits the desk where the list names {@code
   * config-directory}, and only there.
   */
  @Test
  void aConfigDirectoryChangeIsAdmittedOnlyWhenTheListNamesIt() {
    ApprovalRequirement change =
        ApprovalRequirement.person(
            ConfigDirectorySource.ID, ConfigDirectorySource.REASON, ".config/qits/project.yml");
    assertTrue(ApprovalPolicy.deskAdmissible(change, Set.of("config-directory"), PROJECT));
    assertTrue(ApprovalPolicy.deskAdmissible(change, EVERYTHING, PROJECT));
    assertFalse(
        ApprovalPolicy.deskAdmissible(
            change, Set.of("manual-review", "artifact-declarations", "automation", "contract"),
            PROJECT),
        "unlisted");
    assertFalse(ApprovalPolicy.deskAdmissible(change, Set.of(), PROJECT));
  }

  /**
   * The id is reused for the fail-closed readings — an unreadable fold, and one not read yet — and
   * those stay a person's whatever the list holds: "could not look" is no change to judge.
   */
  @Test
  void anUnreadOrUnreadableConfigDirectoryRequirementIsNeverAdmitted() {
    Set<String> listed = Set.of("config-directory");
    for (ApprovalRequirement failClosed :
        List.of(
            ApprovalContentRecorder.notYetRead().get(0),
            ApprovalRequirement.person(
                ApprovalSubject.UNREADABLE_CHANGES_ID,
                "the changes to " + ConfigDirectorySource.DIRECTORY + " could not be read",
                "mirror unreachable"),
            ApprovalRequirement.person(
                ApprovalSubject.UNREADABLE_CHANGES_ID,
                ".config/qits/release.yml at the fold could not be read",
                null))) {
      assertFalse(ApprovalPolicy.deskAdmissible(failClosed, listed, PROJECT), failClosed.reason());
      assertFalse(
          ApprovalPolicy.deskAdmissible(failClosed, EVERYTHING, PROJECT), failClosed.reason());
    }
  }

  /** The subject's real fail-closed readings carry a reason the admission refuses. */
  @Test
  void theSubjectsFailClosedReadingsAreNeverTheConfigDirectoryReason() {
    FoldChanges unreadable =
        new FoldChanges() {
          @Override
          public List<CommitFileChangeDto> changes(
              String repoId, String mergedSha, String pathspec) {
            throw new IllegalStateException("mirror unreachable");
          }

          @Override
          public List<String> pathsBetween(String repoId, String foldSha, String previousFoldSha) {
            throw new IllegalStateException("mirror unreachable");
          }

          @Override
          public boolean addsNothingToMain(String repoId, String mergedSha) {
            throw new IllegalStateException("mirror unreachable");
          }

          @Override
          public String blob(String repoId, String blobSha) {
            throw new IllegalStateException("mirror unreachable");
          }

          @Override
          public String fileAt(String repoId, String mergedSha, String path) {
            throw new IllegalStateException("mirror unreachable");
          }
        };
    ApprovalSubject subject =
        new ApprovalSubject(
            "repo", PROJECT, "abc", ReleaseRequest.State.PENDING, null, true, unreadable);
    FoldUnreadable thrown = assertThrows(FoldUnreadable.class, subject::changes);
    ApprovalRequirement requirement = thrown.requirement();
    assertEquals(ConfigDirectorySource.ID, requirement.id());
    assertFalse(
        ApprovalPolicy.deskAdmissible(requirement, Set.of("config-directory"), PROJECT),
        requirement.reason());
  }

  /** no-changes and recorded never admit the desk, whatever a list holds. */
  @Test
  void theOwnersRequirementsAreNeverAdmitted() {
    Set<String> handBuilt =
        Set.of(
            "manual-review",
            "artifact-declarations",
            "automation",
            "contract",
            "config-directory",
            "no-changes",
            "recorded");
    for (String id : List.of("no-changes", ApprovalPolicy.RECORDED_ID)) {
      assertFalse(
          ApprovalPolicy.deskAdmissible(ApprovalRequirement.person(id, "x", null), handBuilt, PROJECT),
          id);
      assertFalse(
          ApprovalPolicy.deskAdmissible(requirement(id, AGENT_OR_PERSON), handBuilt, PROJECT), id);
    }
  }
}
