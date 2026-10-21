package eu.wohlben.qits.projects.control.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The approval model's pure parts (epic qits-1038): roles written and read, the standing a fold's
 * decisions give its requirements, the release.yml declaration rule, and the stored shape.
 */
class ApprovalModelTest {

  private static final Map<String, String> SLUGS = Map.of("p-a", "alpha");

  // --- roles ----------------------------------------------------------------------------------

  @Test
  void rolesRenderAndParseWithTheProjectSlug() {
    Function<String, String> slugOf = id -> SLUGS.get(id);
    Function<String, String> idOf =
        slug -> SLUGS.entrySet().stream()
            .filter(entry -> entry.getValue().equals(slug))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    assertEquals("person", ApprovalRole.PERSON.render(slugOf));
    assertEquals("agent", ApprovalRole.AGENT.render(slugOf));
    assertEquals("agent@alpha", ApprovalRole.agentOf("p-a").render(slugOf));
    assertEquals("agent@p-b", ApprovalRole.agentOf("p-b").render(slugOf), "no slug: the id");
    assertEquals(ApprovalRole.agentOf("p-a"), ApprovalRole.parse("agent@alpha", idOf));
    assertEquals(ApprovalRole.PERSON, ApprovalRole.parse(" person ", idOf));
    assertNull(ApprovalRole.parse("admin", idOf));
    assertEquals(
        List.of(ApprovalRole.AGENT, ApprovalRole.PERSON),
        ApprovalRole.parseAll("agent person agent", idOf));
    assertEquals(
        List.of("agent", "agent@alpha", "person"),
        ApprovalRole.renderAll(
            List.of(ApprovalRole.PERSON, ApprovalRole.agentOf("p-a"), ApprovalRole.AGENT), slugOf));
  }

  @Test
  void anAgentOfTheRequestsOwnProjectIsAgent() {
    assertEquals(ApprovalRole.AGENT, ApprovalRole.agentOf("p-a").relativeTo("p-a"));
    assertEquals(ApprovalRole.agentOf("p-b"), ApprovalRole.agentOf("p-b").relativeTo("p-a"));
    assertEquals(ApprovalRole.PERSON, new ApprovalRole(ApprovalRole.Kind.PERSON, "p-a"));
  }

  @Test
  void aRequirementAdmitsSomebody() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ApprovalRequirement("x", "reason", List.of(), false, null));
  }

  // --- standing -------------------------------------------------------------------------------

  private static final ApprovalRequirement MANUAL =
      ApprovalRequirement.person("manual-review", "configured by manual-review", null);
  private static final ApprovalRequirement DECLARATIONS =
      new ApprovalRequirement(
          "artifact-declarations",
          "declares",
          List.of(ApprovalRole.PERSON, ApprovalRole.AGENT),
          false,
          ".config/qits/release.yml");
  private static final ApprovalRequirement REVIEW =
      new ApprovalRequirement(
          "contract:beta",
          "a consumer relies on it",
          List.of(ApprovalRole.PERSON, ApprovalRole.agentOf("p-b")),
          true,
          null);

  @Test
  void oneApprovalSatisfiesEveryRequirementItQualifiesFor() {
    ApprovalStanding standing =
        standing(
            List.of(MANUAL, DECLARATIONS),
            row("ada", ReleaseRequestApproval.Decision.APPROVED, "person", 1));
    assertEquals(ReleaseRequest.ApprovalState.APPROVED, standing.state());
    assertTrue(standing.open().isEmpty());
    assertEquals("ada", standing.entries().get(1).satisfiedBy().actor);
  }

  @Test
  void anAgentSatisfiesWhatAdmitsAgentsAndNotWhatDoesNot() {
    ApprovalStanding standing =
        standing(
            List.of(MANUAL, DECLARATIONS),
            row("agent-1", ReleaseRequestApproval.Decision.APPROVED, "agent", 1));
    assertEquals(ReleaseRequest.ApprovalState.WAITING, standing.state());
    assertEquals(List.of(MANUAL), standing.open());
    assertEquals(List.of(ApprovalRole.AGENT), standing.entries().get(1).roles());
  }

  @Test
  void anExcludedAuthorDoesNotSatisfy() {
    ApprovalStanding standing =
        ApprovalStanding.of(
            List.of(REVIEW),
            List.of(row("author", ReleaseRequestApproval.Decision.APPROVED, "person", 1)),
            Set.of("author"),
            ApprovalModelTest::rolesOf);
    assertEquals(List.of(REVIEW), standing.open());
    assertFalse(
        ApprovalStanding.qualifies(REVIEW, List.of(ApprovalRole.PERSON), "author", Set.of("author")));
    assertTrue(
        ApprovalStanding.qualifies(
            REVIEW, List.of(ApprovalRole.agentOf("p-b")), "reviewer", Set.of("author")));
  }

  /** An author recorded under an agent's sub is excluded though the agent decides under another name. */
  @Test
  void anAuthorIsExcludedUnderAnyOfItsNames() {
    Approver agent = Approver.agent("workspace-agent-b", "agent-client-b", "p-b");
    List<ApprovalRole> held = agent.heldRelativeTo("p-a");
    assertTrue(ApprovalStanding.qualifies(REVIEW, held, agent.names(), Set.of("somebody-else")));
    assertFalse(ApprovalStanding.qualifies(REVIEW, held, agent.names(), Set.of("agent-client-b")));
    assertFalse(
        ApprovalStanding.qualifies(REVIEW, held, agent.names(), Set.of("workspace-agent-b")));
  }

  @Test
  void anAgentsDeclineDoesNotOverturnAPersonsApprovalOfWhatItCannotSatisfy() {
    ApprovalStanding standing =
        standing(
            List.of(MANUAL),
            row("ada", ReleaseRequestApproval.Decision.APPROVED, "person", 1),
            row("agent-1", ReleaseRequestApproval.Decision.DECLINED, "agent", 2));
    assertEquals(ReleaseRequest.ApprovalState.APPROVED, standing.state(), "it never qualified");
    assertNull(standing.decline());
    assertEquals("agent-1", standing.newest().actor, "the newest row is still the newest");
  }

  @Test
  void aQualifyingDeclineHoldsUntilTheSameApproverApproves() {
    ApprovalStanding declined =
        standing(
            List.of(MANUAL, DECLARATIONS),
            row("agent-1", ReleaseRequestApproval.Decision.APPROVED, "agent", 1),
            row("ada", ReleaseRequestApproval.Decision.DECLINED, "person", 2));
    assertEquals(ReleaseRequest.ApprovalState.DECLINED, declined.state());
    assertEquals("ada", declined.decline().actor);
    assertEquals("DECLINED", declined.entries().get(1).state(), "ada qualified for it too");
    ApprovalStanding lifted =
        standing(
            List.of(MANUAL, DECLARATIONS),
            row("ada", ReleaseRequestApproval.Decision.DECLINED, "person", 2),
            row("ada", ReleaseRequestApproval.Decision.APPROVED, "person", 3));
    assertEquals(ReleaseRequest.ApprovalState.APPROVED, lifted.state());
  }

  @Test
  void noRequirementsIsNotRequired() {
    assertEquals(ReleaseRequest.ApprovalState.NOT_REQUIRED, standing(List.of()).state());
  }

  private static ApprovalStanding standing(
      List<ApprovalRequirement> requirements, ReleaseRequestApproval... rows) {
    return ApprovalStanding.of(requirements, List.of(rows), Set.of(), ApprovalModelTest::rolesOf);
  }

  private static List<ApprovalRole> rolesOf(ReleaseRequestApproval row) {
    return ApprovalRole.parseAll(row.approverRoles, Function.identity());
  }

  private static ReleaseRequestApproval row(
      String actor, ReleaseRequestApproval.Decision decision, String roles, int second) {
    ReleaseRequestApproval row = new ReleaseRequestApproval();
    row.id = actor + second;
    row.actor = actor;
    row.decision = decision;
    row.approverRoles = roles;
    row.decidedAt = Instant.parse("2026-10-21T10:00:00Z").plusSeconds(second);
    return row;
  }

  // --- the declaration rule -------------------------------------------------------------------

  private static final String BEFORE =
      "archetype: app\n"
          + "artifacts:\n"
          + "  - { type: docker, name: qits/x }\n"
          + "contracts:\n"
          + "  application: x\n"
          + "  pacts:\n"
          + "    qits-ci-service: { packages: [maven] }\n";

  @Test
  void commentsArtifactsAndContractsAreDeclarations() {
    assertTrue(ReleaseDeclarations.declarationOnly(BEFORE, BEFORE + "# a comment\n\n"));
    assertTrue(
        ReleaseDeclarations.declarationOnly(
            BEFORE, BEFORE.replace("qits/x }\n", "qits/x }\n  - { type: npm, name: \"@q/x\" }\n")));
    assertTrue(
        ReleaseDeclarations.declarationOnly(
            BEFORE, BEFORE + "    qits-projects-service: { packages: [maven] }\n"));
    assertTrue(
        ReleaseDeclarations.declarationOnly(
            BEFORE,
            BEFORE.replace(
                "  pacts:\n", "  golden-masters: { from: golden-masters/, packages: [maven] }\n  pacts:\n")));
    assertTrue(ReleaseDeclarations.declarationOnly(null, "artifacts:\n  - { type: oci, name: q }\n"));
  }

  @Test
  void anAddedDockerImageIsADeclaration() {
    assertTrue(
        ReleaseDeclarations.declarationOnly(
            BEFORE,
            BEFORE.replace(
                "qits/x }\n",
                "qits/x }\n  - { type: docker, name: qits/x-worker, sbom: .sbom/worker.json }\n")));
  }

  @Test
  void slotsArchetypesAndOtherTypesAreNot() {
    assertFalse(ReleaseDeclarations.declarationOnly(BEFORE, BEFORE + "release: [a]\n"));
    assertFalse(ReleaseDeclarations.declarationOnly(BEFORE, BEFORE + "release-request: [a]\n"));
    assertFalse(
        ReleaseDeclarations.declarationOnly(BEFORE, BEFORE.replace("archetype: app", "archetype: x")));
    assertFalse(
        ReleaseDeclarations.declarationOnly(
            BEFORE, BEFORE.replace("qits/x }\n", "qits/x }\n  - { type: shell, name: x }\n")));
    assertFalse(
        ReleaseDeclarations.declarationOnly(BEFORE, BEFORE + "    qits-x: { packages: [maven], run: y }\n"));
    assertFalse(ReleaseDeclarations.declarationOnly(BEFORE, BEFORE + "  other: thing\n"));
    assertFalse(ReleaseDeclarations.declarationOnly(BEFORE, ": : not yaml : ["));
  }

  // --- stored shape ---------------------------------------------------------------------------

  @Test
  void requirementsRoundTripTheStoredShape() {
    List<ApprovalRequirement> requirements =
        List.of(MANUAL, DECLARATIONS, REVIEW);
    assertEquals(
        Optional.of(requirements),
        StoredRequirements.read(StoredRequirements.write(requirements)));
    assertEquals(Optional.of(List.of()), StoredRequirements.read("[]"));
  }

  @Test
  void theReasonsStoredBeforeTheEpicReadAsNotReadYet() {
    assertEquals(Optional.empty(), StoredRequirements.read("[\"changes .config/qits/: a\"]"));
    assertEquals(Optional.empty(), StoredRequirements.read("not json"));
  }
}
