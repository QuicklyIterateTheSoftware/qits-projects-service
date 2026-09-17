package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.ReleasePhaseDto;
import eu.wohlben.qits.projects.dto.ReleasePipelineDto;
import eu.wohlben.qits.projects.dto.ReleasePipelineGateDto;
import eu.wohlben.qits.projects.entity.ReleasePipelineRun;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Draws a release request as ONE pipeline: three phases with gates between them.
 *
 * <p><b>Everything here is a rendering and nothing here is a decision.</b> The phases come off
 * {@code release_pipeline_run}, which is a mirror of qits-ci's own run rows; the gates come off the
 * answers {@code ReleaseRequests.gateReport} already had. This class re-decides no gate, invents no
 * state and adds no fact — what it adds is <em>placement</em>: which phase a run belongs to, and
 * which pair of phases a gate stands between. Deleting the whole block would cost a surface and no
 * behaviour, which is the property to preserve.
 *
 * <pre>
 *     P1 . QA        a qits-ci run at release/&lt;id&gt;@mergedSha
 *       |  gates     CI, and APPROVAL where the policy asks        between = QA_PUBLISH
 *     P2 . Publish   a qits-ci run at &lt;version&gt;@commitSha
 *       |  gate      PUBLISH -- that run green                     between = PUBLISH_DEPLOY
 *     P3 . Deploy    a qits-deployments deployment request
 *       |  gate      DEPLOYMENT -- DeploymentActive for it         between = DEPLOY_FINALIZED
 *     -&gt; FINALIZED
 * </pre>
 *
 * <p><b>The request's own state machine is untouched by all of it.</b> {@code RELEASED} is
 * mid-pipeline — the tag is cut and the publish and deploy phases have still to happen — and {@code
 * FINALIZED} is the end of all three. There is no pipeline state word and there must not be one: a
 * reader asking "what is this waiting on" is answered by the first unfinished phase plus the gate in
 * front of it, both already in the block, and a summarising word would be a third answer free to
 * disagree with them.
 *
 * <h2>Absent is not empty</h2>
 *
 * <p><b>The block is null for a request no phase run has been recorded for</b>, which is every
 * request open across the cutover: qits-ci began carrying the phase word on 2026-09-16 and nothing
 * in this database records which historical run was which half of which release, so there is nothing
 * to draw and nothing to invent. A null block is what keeps the existing flat {@code gates} list
 * rendering exactly as it did for those requests — an empty block would claim the pipeline is known
 * and has no phases, which is the one thing it must not be mistaken for. This is {@code
 * ReleaseGates.GateSet}'s "unknown is not an empty set" rule, applied to the whole block.
 *
 * <h2>The deploy phase</h2>
 *
 * <p><b>{@link #deployPhase} is phase 3 and it is the one phase read over the network.</b> The two
 * run phases are rows in this database, mirrored off the bus; the deployment is a fact only
 * qits-deployments holds, so it is asked for at the read through {@link DeploymentRequests} — see
 * that port for why this service's own {@code deploymentActiveAt} stamp is the <em>gate's</em>
 * answer and not the phase's. The null-block rule ({@link #hasSomethingToShow}) reads it, so a
 * RELEASED request whose deployment can be answered draws a block even with no phase run mirrored.
 * The gate placement needed nothing: {@code DEPLOYMENT} was already placed at {@code
 * DEPLOY_FINALIZED}, and it is still closed by a {@code DeploymentActive} reaching {@code
 * ReleaseFinalization} — a phase and the gate behind it are two facts, and this class reports both
 * without merging them.
 *
 * <p><b>It is asked on the single-request read and never on a list read</b>, which is what {@link
 * DeployReach} is for and why it is a parameter rather than a policy in here. qits-deployments'
 * listing is keyed on {@code (repoId, version)} and refuses a repository-wide question outright, so
 * a page of N released requests is N HTTP calls however they are arranged — there is nothing to
 * batch. {@code ReleaseRequests.decorate} therefore passes {@link DeployReach#LIST_READ} and the
 * project-wide worklist makes no call at all; the single request a person has opened passes {@link
 * DeployReach#ASK}. A list read draws the two run phases and the gates exactly as before, which is
 * an honest partial answer rather than a wrong one: the phase is <em>absent</em> from those rows,
 * never stated as unknown or as nothing.
 *
 * <p><b>A phase nobody configured is not drawn.</b> A library, an SPA and a docs repository deploy
 * nothing, so their pipeline is two phases and a reader must not be shown an eternally pending
 * third. {@code ReleaseGates} already separates "not configured" from "pending", and that separation
 * is reused rather than re-read: the deploy phase is drawn only where the decided gate list carries
 * a {@code DEPLOYMENT} gate at all. An <em>unknown</em> gate set reports every kind, deployment
 * included, so such a request is asked about and answered honestly instead of being told it deploys
 * nothing on the strength of a configuration nobody could read.
 */
@ApplicationScoped
public class ReleasePipelineAssembler {

  private static final Logger LOG = Logger.getLogger(ReleasePipelineAssembler.class);

  /**
   * Whether this read may ask qits-deployments for the third phase.
   *
   * <p><b>Stated by the caller rather than decided here, because the cost is the caller's.</b> The
   * far side's listing is keyed on {@code (repoId, version)} — it refuses a question that names only
   * a repository — so the deploy phase is one HTTP call per released request and nothing about it
   * can be batched. On the single request a person has opened that is one call; on the project-wide
   * worklist it would be one per row, on the busiest read this service has. See the class javadoc.
   */
  public enum DeployReach {
    /** Ask. The single-request read: one call, for the one request somebody is looking at. */
    ASK,
    /**
     * Do not ask. A list read draws the run phases and the gates and simply <b>omits</b> the deploy
     * phase — absent, which is what this whole class means by "not known yet", and deliberately not
     * {@code UNKNOWN}, which would claim the far side was asked and could not answer.
     */
    LIST_READ
  }

  /**
   * The third phase's source. Optional in the house sense — absent is a supported configuration and
   * answers "could not be asked", which the fold below draws as {@code UNKNOWN} and never as "no
   * deployment".
   */
  @Inject Instance<DeploymentRequests> deploymentRequests;

  /** The QA phase as a reader names it — qits-ci's {@code RELEASE_REQUEST}. */
  public static final String PHASE_QA = "QA";

  /** The publish phase as a reader names it — qits-ci's {@code RELEASE}. */
  public static final String PHASE_PUBLISH = "PUBLISH";

  /** The deploy phase. No qits-ci run is one; see {@link #deployPhase}. */
  public static final String PHASE_DEPLOY = "DEPLOY";

  /** Between the QA run and the publish run: everything asked of the fold, before anything is tagged. */
  public static final String BETWEEN_QA_PUBLISH = "QA_PUBLISH";

  /** Between the publish run and the deployment: the tag's own release run having gone green. */
  public static final String BETWEEN_PUBLISH_DEPLOY = "PUBLISH_DEPLOY";

  /** Between the deployment and the end of the pipeline. */
  public static final String BETWEEN_DEPLOY_FINALIZED = "DEPLOY_FINALIZED";

  /**
   * Which slot each gate stands in. A {@link java.util.Map} rather than a {@code switch} because
   * that is what the placement <em>is</em> — four kinds, three slots, two kinds sharing one — and a
   * fifth gate is a line here and nothing else.
   */
  private static final Map<ReleaseGates.Kind, String> PLACEMENT =
      Map.of(
          ReleaseGates.Kind.CI, BETWEEN_QA_PUBLISH,
          ReleaseGates.Kind.APPROVAL, BETWEEN_QA_PUBLISH,
          ReleaseGates.Kind.PUBLISH, BETWEEN_PUBLISH_DEPLOY,
          ReleaseGates.Kind.DEPLOYMENT, BETWEEN_DEPLOY_FINALIZED);

  /**
   * The pipeline block for one request, or null where there is nothing to draw.
   *
   * @param runs every phase run of this request, newest transition first — {@code
   *     ReleasePipelineRuns} answers exactly that order, and the fold below depends on it
   * @param gates the gates this request reports, already decided, in {@code ReleaseGates.report}'s
   *     order; placed here and never re-evaluated
   * @param details a sentence per gate where the existing path already has one, sourced rather than
   *     invented — see {@link ReleasePipelineGateDto#detail()}
   * @param released this request's released tag, or null where it produced none — the deploy phase's
   *     input, since it carries the version qits-deployments is keyed on
   * @param reach whether this read may ask qits-deployments for the third phase; see {@link
   *     DeployReach}
   */
  public ReleasePipelineDto assemble(
      List<ReleasePipelineRun> runs,
      List<ReleaseGates.Gate> gates,
      Map<ReleaseGates.Kind, String> details,
      ReleasedTagPendingMerge released,
      DeployReach reach) {
    ReleasePhaseDto deploy = deployPhase(released, gates, reach);
    if (!hasSomethingToShow(runs, deploy)) {
      return null;
    }
    List<ReleasePhaseDto> phases = new ArrayList<>();
    Map<String, ReleasePipelineRun> newest = newestPerPhase(runs);
    ReleasePipelineRun qa = newest.get(PHASE_QA);
    if (qa != null) {
      phases.add(phaseOf(PHASE_QA, qa));
    }
    ReleasePipelineRun publish = newest.get(PHASE_PUBLISH);
    if (publish != null) {
      phases.add(phaseOf(PHASE_PUBLISH, publish));
    }
    if (deploy != null) {
      phases.add(deploy);
    }
    List<ReleasePipelineGateDto> placed = new ArrayList<>();
    for (ReleaseGates.Gate gate : gates) {
      placed.add(
          new ReleasePipelineGateDto(
              PLACEMENT.get(gate.kind()),
              gate.kind().name(),
              gate.state().name(),
              details.get(gate.kind())));
    }
    return new ReleasePipelineDto(List.copyOf(phases), List.copyOf(placed));
  }

  /**
   * Whether the pipeline is worth drawing at all — see "Absent is not empty" in the class javadoc.
   *
   * <p><b>This is the null-block rule.</b> It is "at least one phase run has been recorded, or the
   * deployment phase could be answered", and the first half is what makes a request open across the
   * cutover answer no block and keep rendering exactly as it did. The second half is what lets a
   * release whose QA and publish runs predate the mirror still draw the phase it is actually waiting
   * on.
   */
  private static boolean hasSomethingToShow(List<ReleasePipelineRun> runs, ReleasePhaseDto deploy) {
    return !runs.isEmpty() || deploy != null;
  }

  /**
   * The deploy phase: the newest deployment request qits-deployments holds for the released version.
   *
   * <p><b>Four things make it null, and none of them is a failure.</b> A read that may not ask
   * ({@link DeployReach#LIST_READ}); a request that has not released, which has no version and
   * therefore nothing to deploy; a repository whose decided gates carry no {@code DEPLOYMENT}, which
   * is a repository that declares no deployment at all and must be drawn with two phases rather than
   * three; and the port being unimplemented, which is the shipped clone-alone configuration. In every
   * one of them the phase is <b>absent from the list</b> — see {@link ReleasePipelineDto#phases()},
   * where the honest statement about a phase that does not exist is that it is not there.
   *
   * <p><b>Everything else is a phase, {@code UNKNOWN} included.</b> qits-deployments unreachable,
   * refused, or answering something unreadable is {@code Optional.empty()} from the port, and it is
   * drawn as a deployment phase in state {@code UNKNOWN} with no request id — never as a missing
   * phase, because "we could not see it" and "there is none" are the two answers this whole read
   * exists to keep apart. A present but empty listing is the other one: the far side was asked, it
   * has nothing for this version yet, and that is {@code PENDING}.
   */
  private ReleasePhaseDto deployPhase(
      ReleasedTagPendingMerge released, List<ReleaseGates.Gate> gates, DeployReach reach) {
    if (reach != DeployReach.ASK) {
      return null;
    }
    if (released == null || released.tagName == null || released.tagName.isBlank()) {
      return null;
    }
    if (gates.stream().noneMatch(gate -> gate.kind() == ReleaseGates.Kind.DEPLOYMENT)) {
      return null;
    }
    if (deploymentRequests.isUnsatisfied()) {
      return null;
    }
    Optional<List<DeploymentRequests.DeploymentRequestView>> answered;
    try {
      answered = deploymentRequests.get().forRelease(released.repoId, released.tagName);
    } catch (RuntimeException e) {
      // The port's contract is that it does not throw; a belt here rather than a screen that
      // 500s because a peer did something unexpected.
      LOG.warnf(
          "Could not read the deployment phase of %s %s: %s",
          released.repoId, released.tagName, e.toString());
      answered = Optional.empty();
    }
    if (answered.isEmpty()) {
      return new ReleasePhaseDto(PHASE_DEPLOY, "UNKNOWN", null, null, null);
    }
    List<DeploymentRequests.DeploymentRequestView> requests = answered.get();
    if (requests.isEmpty()) {
      return new ReleasePhaseDto(PHASE_DEPLOY, "PENDING", null, null, null);
    }
    DeploymentRequests.DeploymentRequestView newest = requests.get(0);
    return new ReleasePhaseDto(
        PHASE_DEPLOY, deploymentStateOf(newest.status()), newest.id(), newest.createdAt(), null);
  }

  /**
   * qits-deployments' {@code deploymentStatus} folded onto what a pipeline view needs.
   *
   * <p>It is {@link #stateOf}'s rule applied to another service's vocabulary, and the same three
   * decisions come out of it. <b>Red is one word</b>: {@code IMAGE_MISSING}, {@code
   * SPEC_UNREADABLE}, {@code DECLARATION_REFUSED} and {@code FAILED} are four ways for a deployment
   * to be owed and not arriving, and which one it was belongs on the deployment's own page rather
   * than in the shape of a pipeline. <b>Stopped is not red</b>: a deployment that was superseded,
   * rolled back, scaled to zero, torn down or is simply gone is {@code CANCELLED}, because a reader
   * offered a rerun needs to know whether anything failed. And <b>a word this service cannot place
   * is {@code UNKNOWN}, never a guess</b> — that vocabulary is qits-deployments' and may grow, so a
   * new value shows up honestly instead of being folded into whichever neighbour looked closest.
   *
   * <p><b>A null status is a real answer over there and it is {@code UNKNOWN} here.</b> It means the
   * deployment request exists and no deployment has been created for it — the quality gate has not
   * settled, or it refused — so the phase is known to have begun and its state is not. That is a
   * different fact from {@code PENDING}, which this method never answers: a phase with a request id
   * has begun, and "no request at all yet" is decided one method up.
   *
   * <p>{@code DECOMMISSIONED} is mapped although qits-deployments' enum does not carry it today. The
   * mapping costs a line and the alternative is a word arriving later and reading as {@code
   * UNKNOWN} until somebody notices.
   */
  private static String deploymentStateOf(String status) {
    if (status == null) {
      return "UNKNOWN";
    }
    return switch (status) {
      case "QUEUED", "STARTING" -> "RUNNING";
      case "ACTIVE" -> "SUCCESS";
      case "IMAGE_MISSING", "SPEC_UNREADABLE", "FAILED", "DECLARATION_REFUSED" -> "FAILED";
      case "SUPERSEDED", "ROLLED_BACK", "GONE", "SCALED_TO_ZERO", "DECOMMISSIONED" -> "CANCELLED";
      default -> "UNKNOWN";
    };
  }

  /**
   * The newest run of each phase, keyed by the <b>DTO's</b> phase word.
   *
   * <p>The rows arrive newest-transition-first, so the first row of a phase is that phase's current
   * run and every later one is a run it superseded — a retry, or a re-fold's run that a newer fold
   * replaced. A stored phase word this service cannot translate is dropped rather than drawn under a
   * guessed name: qits-ci owns that vocabulary and may grow it, and a word nobody here has taught is
   * honestly unreportable.
   */
  private static Map<String, ReleasePipelineRun> newestPerPhase(List<ReleasePipelineRun> runs) {
    Map<String, ReleasePipelineRun> newest = new LinkedHashMap<>();
    for (ReleasePipelineRun run : runs) {
      String phase = phaseWordOf(run.phase);
      if (phase != null) {
        newest.putIfAbsent(phase, run);
      }
    }
    return newest;
  }

  /** qits-ci's stored phase word as a reader's, or null where this service knows no such phase. */
  private static String phaseWordOf(String stored) {
    if (ReleasePipelineRuns.PHASE_RELEASE_REQUEST.equals(stored)) {
      return PHASE_QA;
    }
    if (ReleasePipelineRuns.PHASE_RELEASE.equals(stored)) {
      return PHASE_PUBLISH;
    }
    return null;
  }

  private static ReleasePhaseDto phaseOf(String phase, ReleasePipelineRun run) {
    return new ReleasePhaseDto(
        phase, stateOf(run.status), run.runId, run.startedAt, run.finishedAt);
  }

  /**
   * qits-ci's run status folded onto what a pipeline view needs — see {@link
   * ReleasePhaseDto#state()} for why the three reds are one word and why {@code CANCELLED} is not
   * folded in with them. A status this service cannot place is {@code UNKNOWN}, never a guess.
   */
  private static String stateOf(String status) {
    if (status == null) {
      return "UNKNOWN";
    }
    return switch (status) {
      case "QUEUED" -> "PENDING";
      case "RUNNING" -> "RUNNING";
      case "SUCCESS" -> "SUCCESS";
      case "FAILED", "CONFIG_ERROR", "TIMED_OUT" -> "FAILED";
      case "CANCELLED" -> "CANCELLED";
      default -> "UNKNOWN";
    };
  }
}
