package eu.wohlben.qits.entities.campaign;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.campaign.CriterionPredicate.Approval;
import eu.wohlben.qits.entities.campaign.CriterionPredicate.DeploymentActive;
import eu.wohlben.qits.entities.campaign.CriterionPredicate.EntityStatusIs;
import eu.wohlben.qits.entities.campaign.CriterionPredicate.ScmRelease;
import eu.wohlben.qits.entities.campaign.Observation.DeploymentWentActive;
import eu.wohlben.qits.entities.campaign.Observation.EntityReached;
import eu.wohlben.qits.entities.campaign.Observation.Released;
import org.junit.jupiter.api.Test;

/**
 * {@link CriterionPredicate#matches}: one hit per kind, then a near miss on every field that can
 * differ. The near misses are the point — a predicate that matched too much would dispatch work
 * early. Plain JUnit.
 */
class CriterionPredicateMatchesTest {

  // --- ENTITY_STATUS -------------------------------------------------------------------------------

  private static final EntityStatusIs A_VERIFIED = new EntityStatusIs("a", "VERIFIED");

  @Test
  void entityStatusMatchesAMoveOfThatEntityIntoThatStatus() {
    assertTrue(A_VERIFIED.matches(new EntityReached("a", "p", "VERIFIED", "IMPLEMENTED")));
    assertTrue(A_VERIFIED.matches(new EntityReached("a", "p", "VERIFIED", null)), "a created row");
  }

  @Test
  void entityStatusNearMisses() {
    assertFalse(A_VERIFIED.matches(new EntityReached("b", "p", "VERIFIED", "IMPLEMENTED")), "id");
    assertFalse(A_VERIFIED.matches(new EntityReached("a", "p", "DONE", "VERIFIED")), "status");
    assertFalse(
        A_VERIFIED.matches(new EntityReached("a", "p", "VERIFIED", "VERIFIED")),
        "a re-announcement satisfies nothing");
    assertFalse(A_VERIFIED.matches(new Released("p", "a", "1")), "another kind");
  }

  // --- DEPLOYMENT_ACTIVE ---------------------------------------------------------------------------

  private static final DeploymentActive PROJECTS_IN_DEV =
      new DeploymentActive("qits-projects", "dev", "2026.930.10");

  @Test
  void deploymentActiveMatchesTheApplicationInTheEnvironmentAtOrAboveTheFloor() {
    assertTrue(PROJECTS_IN_DEV.matches(new DeploymentWentActive("qits-projects", "dev", "2026.930.10")));
    assertTrue(
        PROJECTS_IN_DEV.matches(new DeploymentWentActive("qits-projects", "dev", "2026.1001.1")));
    DeploymentActive anywhere = new DeploymentActive("qits-projects", null, null);
    assertTrue(anywhere.matches(new DeploymentWentActive("qits-projects", "prod", "")),
        "no environment and no floor: any deployment of it, even one naming no version");
  }

  @Test
  void deploymentActiveNearMisses() {
    assertFalse(
        PROJECTS_IN_DEV.matches(new DeploymentWentActive("qits-ci", "dev", "2026.930.10")), "app");
    assertFalse(
        PROJECTS_IN_DEV.matches(new DeploymentWentActive("qits-projects", "prod", "2026.930.10")),
        "environment");
    assertFalse(
        PROJECTS_IN_DEV.matches(new DeploymentWentActive("qits-projects", "dev", "2026.930.9")),
        "one below the floor (and lexically above it)");
    assertFalse(
        PROJECTS_IN_DEV.matches(new DeploymentWentActive("qits-projects", "dev", " ")),
        "a blank version never satisfies a floor");
    assertFalse(
        PROJECTS_IN_DEV.matches(new DeploymentWentActive("qits-projects", "dev", null)),
        "nor does a missing one");
    assertFalse(PROJECTS_IN_DEV.matches(new Released(null, "qits-projects", "2026.930.10")),
        "another kind");
  }

  // --- SCM_RELEASE ---------------------------------------------------------------------------------

  private static final ScmRelease WRAPPER = new ScmRelease("qits-qits", "p-qits", "2026.1001.93000");

  @Test
  void scmReleaseMatchesTheRepositoryInTheProjectAtOrAboveTheFloor() {
    assertTrue(WRAPPER.matches(new Released("p-qits", "qits-qits", "2026.1001.93000")));
    assertTrue(
        new ScmRelease("qits-qits", null, null).matches(new Released("other", "qits-qits", null)),
        "no project and no floor: any release of it");
  }

  @Test
  void scmReleaseNearMisses() {
    assertFalse(WRAPPER.matches(new Released("p-qits", "qits-ci", "2026.1001.93000")), "repository");
    assertFalse(WRAPPER.matches(new Released("p-other", "qits-qits", "2026.1001.93000")), "project");
    assertFalse(
        WRAPPER.matches(new Released("p-qits", "qits-qits", "2026.1001.92999")), "one below the floor");
    assertFalse(WRAPPER.matches(new Released("p-qits", "qits-qits", "")), "a blank version");
    assertFalse(
        WRAPPER.matches(new DeploymentWentActive("qits-qits", null, "2026.1001.93000")),
        "another kind");
  }

  // --- APPROVAL ------------------------------------------------------------------------------------

  @Test
  void anApprovalMatchesNoObservation() {
    Approval approval = new Approval();
    assertFalse(approval.matches(new EntityReached("a", "p", "VERIFIED", "IMPLEMENTED")));
    assertFalse(approval.matches(new DeploymentWentActive("x", "dev", "1")));
    assertFalse(approval.matches(new Released("p", "x", "1")));
  }
}
