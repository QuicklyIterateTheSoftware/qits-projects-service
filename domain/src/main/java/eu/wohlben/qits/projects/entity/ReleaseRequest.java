package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One ask to release, settled by quality gates before anything ships — the asynchronous replacement
 * for calling the release door and hoping the build was green.
 *
 * <p><b>A request is an OCTOPUS MERGE of N sources, not a branch head.</b> Its participants are the
 * named branches on {@link ReleaseRequestSource} ({@code main} is implied by every create) plus the
 * repository's released tags not yet merged to {@code main} ({@link ReleasedTagPendingMerge}) —
 * folded into a backing branch of the request's own, {@code release/<id>}, by qits-githost's merge
 * primitive. {@link #mergedSha} is the tip of that fold: the one sha that is the whole of what would
 * be released, and therefore the one thing the gates evaluate and the execution is pinned to.
 *
 * <p><b>The re-merge is the re-arm.</b> Anything that changes what the fold would produce — a push
 * to a participating branch, a source added, a sibling release adding an implicit tag, a pending tag
 * reaching {@code main} — re-folds the sources and lands a new {@link #mergedSha}, which invalidates
 * the gates and puts the request back to PENDING. That is the same merge-request shape the single-
 * branch model had, generalised: pushing a fix onto a rejected request is still the ordinary way to
 * answer it. A fold that produces nothing new ({@code unchanged} at the git host) is not a change
 * and re-arms nothing.
 *
 * <p>The state machine: {@code PENDING → READY → RELEASED → FINALIZED}; {@code PENDING → REJECTED}
 * when a verdict is red; {@code READY → FAILED → READY} around a mechanical execution failure
 * — retried by the sweep only while {@link #retryable} says asking again can change the answer;
 * {@code → CONFLICTED} when the sources cannot be folded at all, cleared by the next fold that
 * succeeds; a new merged sha re-arms {@code REJECTED}, {@code FAILED} and {@code CONFLICTED} back to
 * {@code PENDING}; {@code WITHDRAWN} is reserved for an explicit withdrawal, and {@code OBSOLETE}
 * for a released-but-unfinalized request a later release of the same repository has overtaken.
 * Stored as a string with no check constraint, the platform's usual reasoning — which is why
 * CONFLICTED cost no DDL and neither did these two.
 *
 * <p><b>One move is NOT a re-arm: {@code REJECTED → PENDING} at the same sha</b> (ticket qits-309).
 * A {@code qits ci retry} re-fires the run that rejected this request against the very fold it
 * rejected, so the answer to the rejection arrives without anything having been pushed — and a
 * request whose only path back was a new {@link #mergedSha} had no way to take it. The
 * re-consideration therefore moves the state and nothing else: same fold, same window, same
 * approval, and {@link #rejectingRunId} is what says whether this rejection is the kind a run can
 * answer at all. The re-merge is still the re-arm and is still the only thing that moves a fold.
 *
 * <p><b>RELEASED IS NOT THE END, and that is the change ticket b27384a3 made.</b> The tag being cut
 * used to finish a request: a publish run that failed afterwards had nothing holding it open, and a
 * QA run still queued kept grinding on a branch that no longer existed. A request is finished when
 * the release is <b>finalized</b> — the tag merged into {@code main} — which happens only once every
 * post-release gate its repository configures has passed: the tag's own release pipeline green
 * (where the released tree declares one) and {@code DeploymentActive} for the released version
 * (where it declares a deployment). Until then the request is <em>open</em>: it is in {@link
 * eu.wohlben.qits.projects.persistence.ReleaseRequestRepository#OPEN}, it is on the default listing,
 * and a red publish verdict is a failed gate on it rather than a state it leaves.
 *
 * <p><b>This state machine IS the release pipeline's, and there is no second one.</b> A release is
 * one pipeline of three phases with gates between them — the QA run at {@code
 * release/<id>@mergedSha}, the publish run at {@code <version>@commitSha}, and the deployment of
 * that version — and the states above are how far along it this request has got. So {@code RELEASED}
 * is <b>mid-pipeline</b>: the QA phase passed and the tag was cut, and two phases are still to
 * happen. {@code FINALIZED} is the end of all three. <b>No state was added for the pipeline and none
 * may be</b>: a phase's own position is a fact about a qits-ci run (mirrored in {@code
 * release_pipeline_run} for the surface to draw) and a gate's answer is a fact about a gate, so a
 * status word combining them would be a third answer free to disagree with both — and a gate DELAYS
 * rather than fails, which means there is no "pipeline failed" for a state to name in the first
 * place. A red phase is a failed gate on a request that is still open.
 *
 * <p><b>A RELEASED request is nonetheless past changing.</b> Its fold has been tagged, so nothing
 * re-folds it, no source may be added to it and nobody may approve it — see {@link
 * eu.wohlben.qits.projects.persistence.ReleaseRequestRepository#UNRELEASED}, which is the narrower
 * set every one of those paths reads. "Open" here means "not finished", never "still editable".
 *
 * <p><b>{@code PENDING → READY} is now TWO gates, not one</b>, and only the first of them is made of
 * verdicts. The build gate asks whether CI vouched for {@link #mergedSha}; the <b>approval gate</b>
 * asks, where {@code ApprovalPolicy} says a person has to be asked at all, whether one has said yes
 * about that same sha ({@link ReleaseRequestApproval}). Both have to pass for the state to move, and
 * they are ordered rather than combined: the build gate rejects first, so a fold CI has already
 * failed is never put in front of a person. Neither answer is a column here — the build gate reads
 * {@code commit_build_status} and the approval gate reads {@code release_request_approval}, both
 * correlated to {@code mergedSha}, so the re-arm invalidates both for free and there is nothing to
 * clear. A request held by the approval gate is PENDING like any other, with {@link #detail} saying
 * which of the two it is waiting on.
 *
 * <p>A {@link CausedRow}: created on the request thread, so the stamp records what asked. Updates
 * (the re-merge, gate resolution, execution) are machine-driven and the stamp is insert-only — the
 * verdicts that resolved a request are their own caused rows in {@code commit_build_status}.
 */
@Entity
@Table(name = "release_request")
@EntityListeners(CausationStamp.class)
public class ReleaseRequest extends PanacheEntityBase implements CausedRow {

  /** How a request stands. Grows without a migration; see the class javadoc for the moves. */
  public enum State {
    PENDING,
    READY,
    /**
     * The tag is cut, and the request is <b>still open</b>: what it released has still to publish,
     * to deploy and to reach {@code main}. See the class javadoc.
     */
    RELEASED,
    REJECTED,
    FAILED,
    /** The sources cannot be folded; {@link #conflictDetail} says which paths and whose head. */
    CONFLICTED,
    WITHDRAWN,
    /**
     * The release reached {@code main}. <b>The only end of the ordinary path</b>, and the one state
     * in which every gate — before the tag and after it — has been answered.
     */
    FINALIZED,
    /**
     * A later release of the same repository overtook this one before it was finalized, so nothing
     * will ever finish it: the new release folds this one's tag in and supersedes it whole. {@link
     * #supersededBy} names the request that did it and {@link #detail} says so in a sentence.
     */
    OBSOLETE
  }

  /**
   * How the <b>approval</b> gate stands for this request, right now — the second gate's answer,
   * beside {@link State}'s.
   *
   * <p><b>There is no column behind this and there must never be one.</b> It is derived per read
   * from two facts that live elsewhere: whether {@code ApprovalPolicy} says this repository needs a
   * person at all, and the newest {@link ReleaseRequestApproval} at the request's <em>current</em>
   * {@link #mergedSha}. Storing it would be a second answer that a policy change could not reach —
   * the day the policy widens, every already-open request has to start needing approval, and a
   * stored word would go on saying {@code NOT_REQUIRED} until something remembered to rewrite it.
   *
   * <p>{@code NOT_REQUIRED} is the answer for the repositories that release on their gates alone,
   * and it is deliberately not the same as {@code APPROVED}: nobody was asked. {@code WAITING} is a
   * request that needs a person and has not had one at this fold — including one whose earlier
   * decisions were all made against a sha it has since moved past, which is a fold nobody has looked
   * at however much history it carries.
   */
  public enum ApprovalState {
    NOT_REQUIRED,
    WAITING,
    APPROVED,
    DECLINED
  }

  /** The prefix of every request's backing branch. Storage, in the sense that git refs are. */
  public static final String BACKING_BRANCH_PREFIX = "release/";

  /**
   * The branch qits-githost folds this request's sources into — {@code release/<id>}, derived and
   * never stored, because the id already is the name and a column would be a second answer.
   */
  public String backingBranch() {
    return backingBranchOf(id);
  }

  /** The same derivation where only the id is in hand. */
  public static String backingBranchOf(String requestId) {
    return BACKING_BRANCH_PREFIX + requestId;
  }

  @Id public String id;

  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /** The public address pair, for the execution door. Null on a repository with no name. */
  @Column(name = "project_id")
  public String projectId;

  @Column(name = "repo_name")
  public String repoName;

  /**
   * The tip of the fold — what the gates evaluate and what an execution is pinned to. <b>Null until
   * the first merge lands</b>, and null is not "nothing to gate yet" guessed at: the gate reads it
   * as "not ready" and waits, rather than passing a request whose content nobody has computed.
   */
  @Column(name = "merged_sha")
  public String mergedSha;

  /**
   * Why a CONFLICTED request is conflicted: qits-githost's own 409 body, stored as the JSON document
   * it arrived as, so the API and the UI can put the conflicting paths and the head that introduced
   * each in front of a person. Null in every other state — cleared by the fold that succeeds.
   */
  @Column(name = "conflict_detail")
  public String conflictDetail;

  /**
   * When {@link #mergedSha} was armed — the merge that produced it. The settle window's basis: a
   * re-armed request waits its own window, and a no-ci push (which will never produce a verdict)
   * still passes vacuously after it.
   */
  @Column(name = "armed_at", nullable = false)
  public Instant armedAt;

  @Column(nullable = false)
  public String summary;

  /** Who asked — the forwarded identity, carried onto the execution call as the acting user. */
  @Column public String requester;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public State state;

  /** Why a request is REJECTED, FAILED or WITHDRAWN — a sentence for the person who asked. */
  @Column public String detail;

  /**
   * The run whose red verdict rejected this request, and <b>null on every rejection that is not a
   * build's</b> — which today means null on a rejection a person made by declining the approval.
   *
   * <p><b>It is the discriminator, and it is a column because REJECTED has two causes (ticket
   * qits-309).</b> A {@code qits ci retry} re-fires a run at the same fold, so a green retry is the
   * answer to a red verdict and has to be able to take the rejection back — the fold never moved, so
   * there is no push to re-arm it with. But the other cause of REJECTED is somebody saying no, and a
   * CI event must never undo a human decision. Admitting REJECTED to the verdict path wholesale
   * would do exactly that, so the path is admitted by <em>run</em> instead: the rejection is
   * answerable only by a verdict that superseded the very run named here, which a decline has none
   * of and never will. {@link #detail} is not that discriminator and could not be — it is a
   * sentence, and matching a run id back out of prose is a parser standing in for a key.
   *
   * <p><b>Cleared wherever the request leaves REJECTED</b>: by the re-arm that folds a new sha, by
   * the evaluation that takes the request to READY, and by the reconsideration a superseding verdict
   * triggers. Stale here is not merely untidy — it is a rejection the next retry of a long-dead run
   * could answer — and the belt under that is the sweep, which re-evaluates a REJECTED request whose
   * named run no longer stands in the ledger.
   *
   * <p>A key with no foreign key, for {@link CommitBuildStatus#runId}'s reason: the run lives in
   * qits-ci's database, and the ledger row it names here is deleted by the very supersession this
   * column exists to notice.
   */
  @Column(name = "rejecting_run_id")
  public String rejectingRunId;

  /**
   * On a FAILED request: whether the sweep retries the execution. The executor classifies — a
   * failure of the moment (unreachable, 5xx, the door's retry-me 409s) is retried; a refusal about
   * the ask itself (ALREADY_INTEGRATED, a vanished branch) answers the same forever and waits for a
   * re-arm instead. Meaningless in every other state.
   */
  @Column(nullable = false)
  public boolean retryable;

  /** The calver the release door answered with, once RELEASED. */
  @Column public String version;

  /**
   * The request that made this one {@link State#OBSOLETE} — a later release of the same repository
   * that overtook this one before it was finalized. Null in every other state, and it is a plain id
   * with no foreign key for the reason {@link #gateTicketId} states one field down in reverse: the
   * row it names is in this very table, but a delete of it must not cascade into the record of what
   * superseded what.
   */
  @Column(name = "superseded_by")
  public String supersededBy;

  /**
   * The ticket filed because this request's gate went red with <b>nobody watching</b> — see {@code
   * UnattendedGateTickets}. Null on every request that has never been rejected unattended, and it is
   * the dedupe key: a further red verdict is a comment on this ticket rather than a second one.
   *
   * <p>Not cleared by a re-arm and not cleared by the release. A re-arm is a fold nobody has judged
   * yet and clearing the link there would file a fresh ticket the moment the same build fails again;
   * a release is a "this released as {version}" comment on the thread, which is a thing to say on
   * the ticket rather than a reason to forget it. What ends the link is the ticket reaching DONE —
   * asked at the far side, at the moment the next failure needs to know, and DONE rather than any
   * earlier status because only that word says somebody has finished with the thread.
   *
   * <p>It names a row in the <b>epics</b> database. There is no foreign key because there cannot be
   * one, and a ticket somebody deleted reads as "there is no open ticket" and files a fresh one.
   */
  @Column(name = "gate_ticket_id")
  public String gateTicketId;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;

  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }
}
