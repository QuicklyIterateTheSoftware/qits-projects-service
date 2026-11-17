package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The released version went live and was not rolled back. Configured by {@code
 * .config/qits/deployments.yml} at {@code main}.
 *
 * <p><b>The tag reaching {@code main} is not this gate.</b> That is the finalize step after the
 * gates, answered by {@code mergedToMainAt}. The deprecated {@code DEPLOYMENT} entry of {@code
 * gates[]} still means "reached main" and is kept by {@link LegacyGates}, not by a class.
 *
 * <p>"Went live" is this service's own stamp, {@code deploymentActiveAt}. "Rolled back" is
 * qits-deployments' word {@code ROLLED_BACK} on the newest deployment request for the version,
 * which only a single-request read asks for. A list read reports a live release PASSED and says
 * it did not re-check.
 *
 * <p><b>A deployment that failed and never went live is FAILED on every read</b> (qits-1690). It is
 * recorded on the released tag's row ({@code deploymentFailedAt}) by a {@code DeploymentFailed} or
 * by the deployment reconcile, so a list read needs no call to say it; the detail names the remedy,
 * {@code qits release-request rerun --request <qualifiedId> --phase DEPLOY}. A single read whose newest deployment is neither rolled back nor
 * failed — a redeploy under way — is believed over the record.
 *
 * <p><b>A deploy held for a person is PENDING</b> (qits-2268): a single read whose newest deployment
 * request qits-deployments holds for approval — the release request's {@code deployHold} — reads
 * PENDING with the command that releases it, {@code qits deployments requests approve --request
 * <id>}, until it goes live.
 *
 * <p><b>A declined deploy is FAILED</b> (qits-2271): a single read whose newest deployment request
 * was declined at qits-deployments ({@code declinedAt}), or any read of a tag row this service
 * stamped declined, reads FAILED, {@code Deploy of <version> declined by <who>}, ahead of every
 * other arm. Nothing will ever deploy it, and the request ends WITHDRAWN.
 *
 * <p><b>A release with variants answers one check per declared variant</b> (qits-2296): name the
 * variant's application ({@code qits-edge-private}), state PASSED, FAILED or PENDING, detail its
 * status. They ride a single read whose newest request declares variants and no other read, so a
 * release without variants answers exactly the checks it did before. A failed variant fails the
 * gate on that read even before finalization has recorded it, and a recorded variant failure is not
 * mistaken for a redeploy under way because the main deployment reads ACTIVE.
 */
@ApplicationScoped
public class DeploymentRollbackGate implements ReleaseGate {

  public static final String KIND = "deployment-not-rolled-back";

  /** qits-deployments' word for a deployment that was rolled back. */
  static final String ROLLED_BACK = "ROLLED_BACK";

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "Deployment not rolled back";
  }

  @Override
  public ReleaseGatePosition position() {
    return ReleaseGatePosition.DEPLOY_FINALIZED;
  }

  @Override
  public int order() {
    return 500;
  }

  @Override
  public GateApplicability applicability(GateSubject subject) {
    if (!subject.gateSet().known()) {
      return GateApplicability.UNREADABLE;
    }
    return subject.gateSet().requires(ReleaseGates.Kind.DEPLOYMENT)
        ? GateApplicability.APPLIES
        : GateApplicability.DOES_NOT_APPLY;
  }

  @Override
  public GateEvaluation evaluate(GateSubject subject) {
    ReleasedTagPendingMerge released = subject.released();
    if (released == null || released.tagName == null || released.tagName.isBlank()) {
      return GateEvaluation.of(ReleaseGates.State.PENDING, "Nothing is released yet");
    }
    String version = released.tagName;
    GateSubject.Deployment deployment = subject.deployment();
    boolean wentLive = released.deploymentActiveAt != null;
    if (!wentLive && (deployment.declined() || released.declinedAt != null)) {
      // Declined at qits-deployments (qits-2271): settled UNMET, nothing will deploy it, and the
      // request ends WITHDRAWN. FAILED, never the PENDING a settled UNMET request read as before.
      return new GateEvaluation(
          ReleaseGates.State.FAILED,
          declinedDetail(version, deployment.declinedBy()),
          checksOf(deployment, ReleaseGates.State.FAILED),
          null,
          null);
    }
    if (!wentLive && deployment.held()) {
      // Held on purpose (qits-2268): the request's deploy waits for a person at qits-deployments.
      // PENDING, never FAILED, and the detail is the command that releases it.
      return new GateEvaluation(
          ReleaseGates.State.PENDING,
          heldDetail(version, deployment.requestId()),
          checksOf(deployment, ReleaseGates.State.PENDING),
          null,
          null);
    }
    if (!wentLive && released.deploymentFailedAt != null && !redeployUnderWay(deployment)) {
      return new GateEvaluation(
          ReleaseGates.State.FAILED,
          failedDetail(subject, released),
          checksOf(deployment, ReleaseGates.State.FAILED),
          null,
          null);
    }
    if (deployment.asked() && !deployment.answered()) {
      return GateEvaluation.of(
          ReleaseGates.State.UNKNOWN, "qits-deployments could not be asked about " + version);
    }
    ReleaseGates.State state;
    String detail;
    if (ROLLED_BACK.equals(deployment.status())) {
      state = ReleaseGates.State.FAILED;
      detail = version + " was rolled back";
    } else if (!wentLive && deployment.variantFailed()) {
      // qits-2296: a declared variant failed and finalization has not recorded it yet.
      state = ReleaseGates.State.FAILED;
      detail =
          version
              + " failed to deploy: "
              + deployment.variants().stream()
                  .filter(check -> "FAILED".equals(check.state()))
                  .map(check -> check.name() + " " + check.detail())
                  .collect(java.util.stream.Collectors.joining(", "));
    } else if (wentLive) {
      state = ReleaseGates.State.PASSED;
      detail =
          deployment.asked()
              ? version + " went live and is not rolled back"
              : version + " went live; a list read does not check for a rollback";
    } else if (deployment.variantPending()) {
      state = ReleaseGates.State.PENDING;
      detail =
          "Waiting for "
              + version
              + " to go live in every variant it declares: "
              + deployment.variants().stream()
                  .filter(check -> "PENDING".equals(check.state()))
                  .map(GateCheck::name)
                  .collect(java.util.stream.Collectors.joining(", "));
    } else {
      state = ReleaseGates.State.PENDING;
      detail = "Waiting for " + version + " to go live";
    }
    return new GateEvaluation(state, detail, checksOf(deployment, state), null, null);
  }

  private static List<GateCheck> checksOf(
      GateSubject.Deployment deployment, ReleaseGates.State state) {
    List<GateCheck> checks = new java.util.ArrayList<>();
    if (deployment.requestId() != null) {
      checks.add(
          new GateCheck(
              "Deployment request " + deployment.requestId(),
              state.name(),
              deployment.status() == null ? "No deployment yet" : deployment.status(),
              null));
    }
    // qits-2296: one row per declared variant, after the release's own.
    checks.addAll(deployment.variants());
    return checks;
  }

  /**
   * Whether a single read found a newer deployment that is neither rolled back nor failed — a
   * redeploy queued or starting, which the recorded failure must not be drawn over.
   */
  private static boolean redeployUnderWay(GateSubject.Deployment deployment) {
    return deployment.answered()
        && deployment.status() != null
        && !ROLLED_BACK.equals(deployment.status())
        && !FAILED_STATUSES.contains(deployment.status())
        // qits-2296: a main that is ACTIVE beside a failed variant is the recorded failure, not a
        // redeploy.
        && !deployment.variantFailed();
  }

  /**
   * {@code "Deploy of <version> held for approval: qits deployments requests approve --request
   * <id>"} — the deployment request qits-deployments holds for a person (qits-2268).
   */
  static String heldDetail(String version, String deploymentRequestId) {
    return "Deploy of "
        + version
        + " held for approval: qits deployments requests approve --request "
        + deploymentRequestId;
  }

  /**
   * {@code "Deploy of <version> declined by <who>"} (qits-2271), or {@code "Deploy of <version>
   * declined"} where only this service's own stamp says so — a list read, which asks
   * qits-deployments nothing, or a far side that names nobody.
   */
  static String declinedDetail(String version, String declinedBy) {
    return "Deploy of "
        + version
        + " declined"
        + (declinedBy == null || declinedBy.isBlank() ? "" : " by " + declinedBy);
  }

  /** qits-deployments' words for a deployment that ended without going live, besides a rollback. */
  static final java.util.Set<String> FAILED_STATUSES =
      java.util.Set.of("FAILED", "IMAGE_MISSING", "SPEC_UNREADABLE", "DECLARATION_REFUSED");

  /**
   * {@code "<version> was rolled back: <first line>"} or {@code "<version> failed to deploy
   * (<status>): <first line>"}, then the remedy.
   */
  static String failedDetail(GateSubject subject, ReleasedTagPendingMerge released) {
    String recorded = released.deploymentFailedDetail == null ? "" : released.deploymentFailedDetail;
    int colon = recorded.indexOf(": ");
    String status = colon < 0 ? recorded.trim() : recorded.substring(0, colon).trim();
    String said = colon < 0 ? "" : recorded.substring(colon + 2).trim();
    StringBuilder detail = new StringBuilder(released.tagName);
    if (ROLLED_BACK.equals(status)) {
      detail.append(" was rolled back");
    } else if (status.isEmpty() || "FAILED".equals(status)) {
      detail.append(" failed to deploy");
    } else {
      detail.append(" failed to deploy (").append(status).append(')');
    }
    if (!said.isEmpty()) {
      detail.append(": ").append(said);
    }
    String request =
        subject.request() == null
            ? null
            : subject.request().qualifiedId != null
                ? subject.request().qualifiedId
                : subject.request().id;
    if (request != null) {
      detail
          .append(" — redeploy with `qits release-request rerun --request ")
          .append(request)
          .append(" --phase DEPLOY`");
    } else {
      detail.append(" — redeploy by re-running its DEPLOY phase");
    }
    return detail.toString();
  }
}
