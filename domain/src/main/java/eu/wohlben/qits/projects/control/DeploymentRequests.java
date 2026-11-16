package eu.wohlben.qits.projects.control;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What qits-deployments has been asked to do about one released version — <b>the third phase of a
 * release pipeline, read</b>.
 *
 * <p><b>A phase is a unit of work with a state and a rerun, and the deployment is the third one.</b>
 * QA is a qits-ci run at the fold, publish is a qits-ci run at the tag, and deploy is a
 * qits-deployments <em>deployment request</em> for {@code (repository, version)}. Nothing else is a
 * phase: a step inside one of those runs is not one, and neither is any other part a run is split
 * into, because neither has a rerun of its own. The <b>gate</b> that follows this phase is a
 * different thing again and is not this port's business — {@code DEPLOYMENT} is closed by a {@code
 * DeploymentActive} arriving on the bus, or by {@code ReleaseFinalization.reconcileDeployments}
 * reading this port's newest request as {@code ACTIVE} when no event came (qits-2217:
 * qits-deployments flips a failed deployment it later finds running to ACTIVE and announces
 * nothing). {@code ReleaseFinalization} is where both are decided; this port only answers the
 * question, and nothing here decides a gate. A gate delays; this port reports.
 *
 * <p><b>This service holds no fact about a deployment and that is why the port exists.</b> Its one
 * stored fact is {@code ReleasedTagPendingMerge.deploymentActiveAt} — the gate's answer, written
 * when the event arrived or the reconcile found it live — and reporting that as the phase as well
 * would be one fact wearing two hats, with no request id, no intermediate state and nothing to
 * re-run. So the phase is read live
 * from the service that owns it, and <b>no project awareness is added over there</b>: the question
 * is asked in qits-deployments' own vocabulary, {@code (repoId, version)}, which is the pair its
 * listing is already keyed on.
 *
 * <p>A port in the house shape: the implementation is {@code service/…/deploymenthost} (one HTTP
 * read of {@code GET /deployments/api/deployment-requests?repoId=…&version=…}), resolved
 * through {@code Instance} with absent supported, and <b>it must not throw</b>. It decorates a read,
 * so it carries {@code HttpActiveBuilds}' failure contract and not the dispatch's.
 *
 * <p><b>{@code Optional.empty()} means "could not be asked" and NEVER "no deployment".</b> The port
 * unimplemented, the address unset, qits-deployments unreachable, a 401/403 at its door, any
 * non-200, an unreadable body — all one answer, and the assembler draws it as a phase in state
 * {@code UNKNOWN}. <b>A present, empty list is a different answer</b>: qits-deployments really was
 * asked and really has no request for that version yet, which is a deployment about to be asked for
 * rather than one that cannot be seen. Collapsing the two would put a released, deployable
 * repository on screen as though nothing were owed — the one reading that would make an outage of
 * the far side look like a finished release. It is the {@code DownstreamComponents} rule one seam
 * over, with the stakes of a screen rather than of a merge.
 */
public interface DeploymentRequests {

  /**
   * One deployment request, in the three fields a phase needs plus the four its hold is read from.
   *
   * @param id the deployment request's own id — what the phase reports as its {@code runId}, because
   *     a caller does exactly one thing with that field and it is address the phase's own page
   * @param status qits-deployments' {@code deploymentStatus}, <b>verbatim and nullable</b>. Null is a
   *     real answer over there — a request whose deployment has not been created at all — and it is
   *     carried as null rather than as a word, so the fold that maps it has the same three-way
   *     choice this service has everywhere else: a word it knows, a word it does not, and no word.
   * @param createdAt when the request was made. It becomes the phase's {@code startedAt}; there is
   *     deliberately no {@code finishedAt} for a deployment phase, because qits-deployments carries
   *     no instant meaning "this deployment settled" — {@code gateSettledAt} is when its own quality
   *     gate answered, which is a different fact and must not be dressed as one.
   * @param qualityGate the far side's {@code qualityGate} ({@code MET}, {@code UNMET}), verbatim
   *     and nullable; read for {@link #held} alone (qits-2268)
   * @param gateSettledAt when that gate settled, or null while it has not
   * @param gateDetail the far side's sentence on its gate, or null
   * @param deployHold the far side's {@code deployHold}, or null where it does not answer one — an
   *     older qits-deployments, which {@link #held} reads off the detail instead
   * @param declinedBy who declined the held request at qits-deployments (qits-2271), or null where
   *     nobody did or the far side does not answer the field
   * @param declinedAt when they did, or null with {@code declinedBy}; see {@link #declined}
   */
  record DeploymentRequestView(
      String id,
      String status,
      Instant createdAt,
      String qualityGate,
      Instant gateSettledAt,
      String gateDetail,
      Boolean deployHold,
      String declinedBy,
      Instant declinedAt) {

    /** The far side's word for a quality gate that has not passed. */
    public static final String UNMET = "UNMET";

    /** How qits-deployments' detail begins on a request it holds for a person (qits-2268). */
    public static final String HELD_DETAIL_PREFIX = "held for manual approval";

    /** The view in the three fields every read before qits-2268 carried. */
    public DeploymentRequestView(String id, String status, Instant createdAt) {
      this(id, status, createdAt, null, null, null, null, null, null);
    }

    /** The view in the seven fields every read before qits-2271 carried: nothing declined. */
    public DeploymentRequestView(
        String id,
        String status,
        Instant createdAt,
        String qualityGate,
        Instant gateSettledAt,
        String gateDetail,
        Boolean deployHold) {
      this(id, status, createdAt, qualityGate, gateSettledAt, gateDetail, deployHold, null, null);
    }

    /**
     * Whether somebody <b>declined</b> this held request at qits-deployments (qits-2271): it settled
     * UNMET and will never deploy, so the release request it belongs to must not ship. {@code
     * declinedAt} alone decides it, the far side's own stamp.
     */
    public boolean declined() {
      return declinedAt != null;
    }

    /**
     * The note the decline was given, read off the far side's {@code gateDetail} ({@code "declined
     * by <who>: <note>"}), or null where there was none or the detail does not read that way. The
     * listing carries no field of its own for it.
     */
    public String declineNote() {
      if (!declined() || gateDetail == null) {
        return null;
      }
      String prefix = "declined by " + (declinedBy == null ? "" : declinedBy) + ": ";
      if (!gateDetail.startsWith(prefix)) {
        return null;
      }
      String note = gateDetail.substring(prefix.length()).trim();
      return note.isEmpty() ? null : note;
    }

    /**
     * Whether qits-deployments is <b>holding this request for a person</b> (qits-2268): its gate
     * UNMET and unsettled, and either its own {@code deployHold} flag or — from a qits-deployments
     * that does not answer the flag — a detail saying so. Nothing is deployed while it holds; {@code
     * qits deployments requests approve --request <id>} releases it.
     */
    public boolean held() {
      if (!UNMET.equals(qualityGate) || gateSettledAt != null) {
        return false;
      }
      if (deployHold != null) {
        return deployHold;
      }
      return gateDetail != null && gateDetail.startsWith(HELD_DETAIL_PREFIX);
    }
  }

  /**
   * Every deployment request for this released version, <b>newest first</b>.
   *
   * <p>The order is the far side's ({@code seq desc}) and the caller depends on it: the phase is the
   * newest request, and every older one is a request it superseded — a redeploy, or an earlier ask
   * that a later one replaced. This service does not re-sort, because the sequence a row was written
   * in is a fact only the writer holds.
   *
   * @param repoId the repository the version was released from — qits-deployments' own {@code repoId},
   *     which is this service's repository row id
   * @param version the released version, the tag's bare name and never a ref
   * @return the requests, or empty where the question could not be asked at all — see this
   *     interface's javadoc for why that is never the same answer as an empty list
   */
  Optional<List<DeploymentRequestView>> forRelease(String repoId, String version);
}
