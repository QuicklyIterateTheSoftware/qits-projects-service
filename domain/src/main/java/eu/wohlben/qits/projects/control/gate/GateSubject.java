package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import eu.wohlben.qits.projects.dto.ReleaseAutomationDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import java.time.Instant;
import java.util.List;

/**
 * Everything a gate may read about one request, already read. The caller batches it per page.
 *
 * @param request the request row
 * @param gateSet the gates the repository's {@code main} configures, or that it could not be read
 * @param approval the approval gate's facts at the current fold
 * @param automations the automations gate's facts at the current fold
 * @param verdicts the CI verdicts for the current fold; empty where none, or no fold yet
 * @param released the request's released tag, or null where it has not released
 * @param deployment what qits-deployments said about the released version on this read
 */
public record GateSubject(
    ReleaseRequest request,
    ReleaseGates.GateSet gateSet,
    Approval approval,
    Automations automations,
    List<CommitBuildStatusDto> verdicts,
    ReleasedTagPendingMerge released,
    Deployment deployment) {

  public GateSubject {
    verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
    deployment = deployment == null ? Deployment.NOT_ASKED : deployment;
  }

  /**
   * qits-deployments' newest deployment request for the released version, as this read learned it.
   * A list read never asks: its listing is keyed per version and cannot be batched.
   *
   * @param asked whether this read asked at all
   * @param answered whether the far side answered; false with {@code asked} is "could not ask"
   * @param requestId the newest deployment request's id, or null where there is none yet
   * @param status that request's deployment status as qits-deployments words it, or null
   */
  public record Deployment(boolean asked, boolean answered, String requestId, String status) {

    public static final Deployment NOT_ASKED = new Deployment(false, false, null, null);

    public static final Deployment COULD_NOT_ASK = new Deployment(true, false, null, null);

    public static final Deployment NOTHING_YET = new Deployment(true, true, null, null);

    public static Deployment newest(String requestId, String status) {
      return new Deployment(true, true, requestId, status);
    }
  }

  /**
   * @param required whether a person must approve this fold
   * @param state {@code NOT_REQUIRED}, {@code WAITING}, {@code APPROVED} or {@code DECLINED}
   * @param actor who made the current decision, or null
   * @param decidedAt when, or null
   * @param note what they said, or null
   * @param reason why a person is asked at all, or null
   */
  public record Approval(
      boolean required,
      ReleaseRequest.ApprovalState state,
      String actor,
      Instant decidedAt,
      String note,
      String reason) {}

  /**
   * @param applies whether the automations gate holds this repository
   * @param state the gate state already read off the ledger and the waiver; null where it does not
   *     apply
   * @param rows one row per automation, or null where nothing is on record
   */
  public record Automations(
      boolean applies, ReleaseGates.State state, List<ReleaseAutomationDto> rows) {}

  /** The current fold shortened the way the request's own {@code detail} shortens it. */
  public String shortFold() {
    String sha = request == null ? null : request.mergedSha;
    if (sha == null) {
      return "(nothing)";
    }
    return sha.length() <= 10 ? sha : sha.substring(0, 10);
  }
}
