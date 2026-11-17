package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.DeploymentRequests.DeploymentRequestView;
import eu.wohlben.qits.projects.control.ReleaseDeploymentVerdict.State;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ReleaseDeploymentVerdict} (qits-2296): the main row decides on its own, and every variant
 * it declares must be ACTIVE too. qits-edge-service-rr-165 is the case — main {@code qits-edge}
 * ACTIVE, variant {@code qits-edge-private} FAILED on an image pull — and it must read FAILED.
 */
class ReleaseDeploymentVerdictTest {

  private static final Instant AT = Instant.parse("2026-11-16T03:14:21Z");

  private static DeploymentRequestView main(String id, String status, String... variants) {
    return new DeploymentRequestView(
        id, status, AT, null, null, null, null, null, null, "qits-edge", null, List.of(variants));
  }

  private static DeploymentRequestView privateEdge(String id, String status) {
    return new DeploymentRequestView(
        id, status, AT, null, null, null, null, null, null, "qits-edge-private", "private", null);
  }

  @Test
  void rr165TheMainLiveAndItsPrivateEdgeFailedIsFailed() {
    ReleaseDeploymentVerdict verdict =
        ReleaseDeploymentVerdict.of(
            List.of(privateEdge("v1", "FAILED"), main("m1", "ACTIVE", "private")));

    assertEquals(State.FAILED, verdict.state());
    assertEquals(State.FAILED, verdict.variantsState());
    assertEquals("qits-edge-private", verdict.failedVariant().orElseThrow().applicationName());
    assertEquals("FAILED", verdict.failedVariant().orElseThrow().status());
  }

  @Test
  void everyDeclaredVariantActiveIsLive() {
    ReleaseDeploymentVerdict verdict =
        ReleaseDeploymentVerdict.of(
            List.of(privateEdge("v1", "ACTIVE"), main("m1", "ACTIVE", "private")));

    assertEquals(State.LIVE, verdict.state());
    assertTrue(verdict.declaresVariants());
  }

  /** The main goes ACTIVE 10–75 s before qits-deployments writes the variant's row at all. */
  @Test
  void aDeclaredVariantWithNoRowYetIsPending() {
    ReleaseDeploymentVerdict verdict =
        ReleaseDeploymentVerdict.of(List.of(main("m1", "ACTIVE", "private")));

    assertEquals(State.PENDING, verdict.state());
    assertEquals(List.of("qits-edge-private"), verdict.pendingVariants());
    assertNull(verdict.variants().get(0).newest());
  }

  @Test
  void aVariantStillStartingIsPending() {
    assertEquals(
        State.PENDING,
        ReleaseDeploymentVerdict.of(
                List.of(privateEdge("v1", "STARTING"), main("m1", "ACTIVE", "private")))
            .state());
  }

  @Test
  void eachFailureWordOfAVariantFails() {
    for (String word : List.of("FAILED", "ROLLED_BACK", "IMAGE_MISSING", "DECLARATION_REFUSED")) {
      assertEquals(
          State.FAILED,
          ReleaseDeploymentVerdict.of(
                  List.of(privateEdge("v1", word), main("m1", "ACTIVE", "private")))
              .state(),
          word);
    }
    for (String word : List.of("SUPERSEDED", "GONE", "SCALED_TO_ZERO", "QUEUED")) {
      assertEquals(
          State.PENDING,
          ReleaseDeploymentVerdict.of(
                  List.of(privateEdge("v1", word), main("m1", "ACTIVE", "private")))
              .state(),
          word);
    }
  }

  /**
   * A DEPLOY rerun writes a fresh main row while the variant's newest row is still the failure the
   * rerun is fixing: that row predates the attempt and reads PENDING, never FAILED again.
   */
  @Test
  void aVariantRowOlderThanTheNewestMainIsNotThisAttempts() {
    ReleaseDeploymentVerdict rerun =
        ReleaseDeploymentVerdict.of(
            List.of(
                main("m2", "ACTIVE", "private"),
                privateEdge("v1", "FAILED"),
                main("m1", "ACTIVE", "private")));
    assertEquals(State.PENDING, rerun.state());
    assertTrue(rerun.failedVariant().isEmpty());

    ReleaseDeploymentVerdict settled =
        ReleaseDeploymentVerdict.of(
            List.of(
                privateEdge("v2", "ACTIVE"),
                main("m2", "ACTIVE", "private"),
                privateEdge("v1", "FAILED"),
                main("m1", "ACTIVE", "private")));
    assertEquals(State.LIVE, settled.state());
  }

  /** A newer variant row never decides over the main: a live variant beside a failed main fails. */
  @Test
  void theMainRowStillDecidesOnItsOwn() {
    assertEquals(
        State.FAILED,
        ReleaseDeploymentVerdict.of(
                List.of(privateEdge("v1", "ACTIVE"), main("m1", "ROLLED_BACK", "private")))
            .state());
    assertEquals(
        State.PENDING,
        ReleaseDeploymentVerdict.of(
                List.of(privateEdge("v1", "ACTIVE"), main("m1", "STARTING", "private")))
            .state());
  }

  /** A release declaring no variants — or an older qits-deployments — is the main row alone. */
  @Test
  void noDeclaredVariantsIsTheMainAlone() {
    assertEquals(State.LIVE, ReleaseDeploymentVerdict.of(List.of(main("m1", "ACTIVE"))).state());
    assertEquals(
        State.FAILED, ReleaseDeploymentVerdict.of(List.of(main("m1", "FAILED"))).state());
    ReleaseDeploymentVerdict old =
        ReleaseDeploymentVerdict.of(
            List.of(new DeploymentRequestView("m1", "ACTIVE", AT)));
    assertEquals(State.LIVE, old.state());
    assertFalse(old.declaresVariants());
    assertEquals(State.LIVE, old.variantsState());
  }

  @Test
  void noRowsIsPendingWithNothingDeclared() {
    ReleaseDeploymentVerdict none = ReleaseDeploymentVerdict.of(List.of());
    assertEquals(State.PENDING, none.state());
    assertNull(none.main());
    assertEquals(State.LIVE, none.variantsState(), "nothing declared, nothing to wait for");
  }

  @Test
  void aVariantWithNoRowIsNamedAfterTheMainApplication() {
    assertEquals(
        "qits-edge-private",
        ReleaseDeploymentVerdict.of(List.of(main("m1", "ACTIVE", "private")))
            .variants()
            .get(0)
            .applicationName());
  }

  @Test
  void theFailureWordNamesTheVariantsApplication() {
    assertEquals(
        "qits-edge-private FAILED",
        ReleaseDeploymentVerdict.variantFailureWord("qits-edge-private", "FAILED"));
    assertEquals(
        "qits-edge-private FAILED",
        eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge.deploymentFailure(
            ReleaseDeploymentVerdict.variantFailureWord("qits-edge-private", null), null));
  }

  @Test
  void theMainApplicationOfAVariantDropsItsSuffix() {
    assertEquals("qits-edge", ReleaseFinalization.mainApplicationOf("qits-edge-private", "private"));
    assertEquals("qits-edge", ReleaseFinalization.mainApplicationOf("qits-edge", null));
    assertEquals(
        "other-name", ReleaseFinalization.mainApplicationOf("other-name", "private"));
  }
}
