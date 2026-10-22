package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.approval.ApprovalRequirement;
import eu.wohlben.qits.projects.control.approval.ApprovalRole;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Which requirement a project's {@code front_desk.delivery.approves} admits its delivery desk to —
 * {@link ApprovalPolicy#deskAdmissible}, the one reading of the list (qits-1517 widened it with the
 * {@code automation} and {@code contract} families).
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

  /** config-directory, no-changes and recorded never admit the desk, whatever a list holds. */
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
    for (String id : List.of("config-directory", "no-changes", ApprovalPolicy.RECORDED_ID)) {
      assertFalse(
          ApprovalPolicy.deskAdmissible(ApprovalRequirement.person(id, "x", null), handBuilt, PROJECT),
          id);
      assertFalse(
          ApprovalPolicy.deskAdmissible(requirement(id, AGENT_OR_PERSON), handBuilt, PROJECT), id);
    }
  }
}
