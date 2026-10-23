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
 * @param verdicts every CI verdict the ledger holds for the current fold's commit — any branch, any
 *     request, any time; empty where none, or no fold yet. Which of them are this request's own is
 *     {@link CiVerdicts}' question, never the caller's
 * @param released the request's released tag, or null where it has not released
 * @param deployment what qits-deployments said about the released version on this read
 * @param upstream gate 3's facts (epic qits-897): the open requests upstream of this one and what
 *     each is doing; {@link Upstreams#NOT_READ} where the caller did not read them
 */
public record GateSubject(
    ReleaseRequest request,
    ReleaseGates.GateSet gateSet,
    Approval approval,
    Automations automations,
    List<CommitBuildStatusDto> verdicts,
    ReleasedTagPendingMerge released,
    Deployment deployment,
    Upstreams upstream) {

  public GateSubject {
    verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
    deployment = deployment == null ? Deployment.NOT_ASKED : deployment;
    upstream = upstream == null ? Upstreams.NOT_READ : upstream;
  }

  /** A subject with no upstream facts read — every gate but gate 3 reads nothing more. */
  public GateSubject(
      ReleaseRequest request,
      ReleaseGates.GateSet gateSet,
      Approval approval,
      Automations automations,
      List<CommitBuildStatusDto> verdicts,
      ReleasedTagPendingMerge released,
      Deployment deployment) {
    this(request, gateSet, approval, automations, verdicts, released, deployment, null);
  }

  /**
   * Gate 3's facts about one request: whether its repository is the estate wrapper (which gate 3
   * never holds) and the open requests upstream of it.
   *
   * @param read whether the caller read them at all; false leaves gate 3 out of the answer
   * @param estateWrapper whether the request's repository is the estate wrapper (PROJECT archetype)
   * @param requests the upstream requests — see {@link Upstream}
   */
  public record Upstreams(boolean read, boolean estateWrapper, List<Upstream> requests) {

    public static final Upstreams NOT_READ = new Upstreams(false, false, List.of());

    public Upstreams {
      requests = requests == null ? List.of() : List.copyOf(requests);
    }

    public static Upstreams of(boolean estateWrapper, List<Upstream> requests) {
      return new Upstreams(true, estateWrapper, requests);
    }
  }

  /**
   * One open request in another repository of the project that is, or may be, upstream of this one.
   *
   * @param requestId the upstream request's uuid
   * @param qualifiedId its qualified id ({@code qits-ci-frontend-rr-12}), or its uuid where it has none
   * @param repoId its repository
   * @param doing what it is doing, one short sentence
   * @param qaRunId its QA run, where the activity is one; else null
   * @param active whether it has an active build right now: PENDING with a QA run QUEUED or RUNNING
   *     at its fold, READY, or RELEASED with its publish PENDING
   * @param known whether its downstream closure was read and names this request's repository; false
   *     is "could not read what is downstream of it", which is listed only where it is active
   * @param override a person's override of this pair (qits-914), or null — always null until then
   */
  public record Upstream(
      String requestId,
      String qualifiedId,
      String repoId,
      String doing,
      String qaRunId,
      boolean active,
      boolean known,
      UpstreamOverride override) {}

  /**
   * A person's decision to let this request start QA although one upstream request is active
   * (qits-914). Keyed by the pair, not by a fold.
   */
  public record UpstreamOverride(String decidedBy, Instant decidedAt, String note) {}

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
