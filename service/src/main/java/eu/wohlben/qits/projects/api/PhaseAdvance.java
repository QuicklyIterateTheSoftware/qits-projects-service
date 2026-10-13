package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.control.Archetypes;
import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.control.EntityDispatchService;
import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.control.EntityStateMachine.Phase;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.WorkBranches;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.ConflictException;
import eu.wohlben.qits.projects.control.BranchListing;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.ReleaseGitHost;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.control.ReleasedBranchWorkspaces;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestSourceDto;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.persistence.ReleasedTagPendingMergeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * The next phase starts itself: an entity that just moved has the turn its <b>new</b> status begins
 * delivered into the workspace already standing on its branch — <b>when the press that started the
 * run asked for the whole flow</b> — and the entity's thread is told what happened. It was {@code
 * TicketPhaseAdvance} until qits-394; everything below that says "ticket" holds for an epic too,
 * with the three differences the next section names.
 *
 * <h2>What qits-394 changed: epics, and the continue-or-stop bit</h2>
 *
 * <p><b>The bit.</b> {@link WorkEntity#dispatchContinues} is written by every dispatch press
 * ({@link EntityDispatch}): {@code true} for <em>Dispatch</em> (the whole flow), {@code false} for
 * <em>Run the next phase</em>. This class reads it at exactly one place — before the turn is
 * delivered — and a stopped run delivers nothing, says nothing, and waits for the next press. It
 * reads it off the row it was handed, i.e. as the transition left it, because the press and the
 * transition are different requests hours apart. <b>The release at VERIFIED does not read it</b>:
 * the branch is finished whoever pressed what, so a one-phase run that reaches VERIFIED asks for its
 * release exactly as a flow does. So does the IMPLEMENTED note about a release still standing open,
 * which is context about a release and not a phase started.
 *
 * <p><b>Epics.</b> Both lifecycle archetypes come through here: {@code POST /work/{qualifiedId}/status}
 * ({@code WorkEntityDoors.move}) for either; an agent reaches it through the {@code qits} MCP
 * server's {@code work_status}. The phase and its words come from {@link PhasePrompts}, which is archetype-aware; the
 * address from {@link EntityWorkspaces}, which knows both branch shapes. What differs for an epic:
 * it is looked up at the workspaces port by epic id, and its release request is titled "Epic
 * &lt;slug&gt;: …". An epic is blocked as a ticket is (qits-592, {@link EntityBlocks}), and a
 * blocked one is delivered no turn either. <b>It has a thread</b> since qits-551, so every
 * sentence below lands on the epic's thread exactly as on a ticket's, with the {@code EPICS} hint
 * where a ticket's fires {@code TICKETS} — until then an epic had none and each sentence was a log
 * line nobody reading the board would ever see.
 *
 * <h2>The transition is the whole trigger</h2>
 *
 * <p>A ticket's three phases run in one workspace, separated by a context reset rather than by a
 * container ({@link WorkspaceAgentTurns}). So the moment an agent claims a phase — its own {@code
 * work_status} call — is the moment this service knows something that workspace does not, and
 * this class is what says it. Nobody presses anything between implement and verify: implement ends,
 * the ticket is IMPLEMENTED, and the verify turn arrives in the session the agent is already in.
 * <b>Refine is the exception since qits-887</b>: it ends at REFINED, which starts nothing and waits
 * for a person to schedule the work (READY_FOR_DEV), and the scheduling move delivers no turn either
 * — starting the work is the next press. <b>Unless that person already said so</b> (qits-1075): a
 * person's <em>Dispatch</em> press at REPORTED leaves a pre-approval on the row ({@link
 * WorkEntity#preApprovedBy}), and the refine landing REFINED is then scheduled as that person and
 * handed on to implement in the same session — one press, REPORTED to VERIFIED. Pressing "Assign
 * agent" stays the way to <em>resume</em> a ticket nobody is working on; it stops being the way to
 * continue one that is.
 *
 * <p>It hangs off the transition and off nothing else. Not off assignment, not off a comment, not
 * off a release — those say something about a ticket without saying that a phase ended, and a phase
 * started from any of them would be started from a claim nobody made.
 *
 * <h2>One rule, and it is {@link PhasePrompts} unchanged</h2>
 *
 * <p><b>The prompt for a status is the work that starts from it</b>, which is exactly what {@link
 * PhasePrompts#phaseOf(WorkEntity)} and {@link PhasePrompts#start} already compute for the dispatch
 * door. This class adds no second table and no second switch: it reads that one, and everything
 * else follows from it.
 *
 * <p><b>Direction is not consulted, with one exception.</b> The ticket's new status is otherwise the
 * entire input, so a move back from IMPLEMENTED to IMPLEMENTING — a correction of a claim, not a
 * failure path — gets the <em>implement</em> turn, which is precisely right, because rework is
 * what runs next; a move back from VERIFIED to
 * VERIFYING gets the <em>verify</em> turn, re-verification; and a person closing a
 * VERIFIED ticket to DONE gets nothing, which is also right, because DONE starts no phase at all
 * (and, being final, never moves again).
 *
 * <p><b>The exception is the platform's own "phase started" move</b> (qits-749) — READY_FOR_DEV →
 * IMPLEMENTING or IMPLEMENTED → VERIFYING ({@link EntityStateMachine#isStartedMove}) — and it pushes
 * nothing. That move means the phase was started: the platform made it at the dispatch press (which
 * is not a route and never reaches here), or the agent already working moved itself by hand —
 * either way the prompt is out or the agent is at work, and a second one would restart it. Into
 * IMPLEMENTING or VERIFYING from anywhere else (BACK, rework or re-verification) the turn is
 * delivered as for any phase. {@code statusBefore} is passed in for this, and for the two qits-887
 * arms beside it: a person's scheduling (REFINED → READY_FOR_DEV) pushes nothing, and a refine that
 * lands REFINED under FLOW says on the thread that the run now waits for a person — or, with a
 * pre-approval standing, is scheduled on it and carries on (qits-1075).
 *
 * <h2>Delivering a phase's turn starts the phase (qits-749)</h2>
 *
 * <p><b>One rule:</b> whenever the implement prompt is delivered for an epic or a ticket that is
 * READY_FOR_DEV, the entity moves to IMPLEMENTING; whenever the verify prompt is delivered for one that is
 * IMPLEMENTED, it moves to VERIFYING. {@link EntityDispatch} does it at the press; this class does it
 * for the FLOW hand-off, when the previous phase lands its status with {@code dispatchContinues} set
 * and the next turn is spoken (DELIVERED or LAUNCHED) — and only then: a turn that found no
 * workspace, or could not be delivered, started nothing, and the entity stays where it landed. The move goes straight through {@link WorkEntityService#transitionFrom}, never back
 * through a route, so this class is not re-entered and no second prompt is pushed; a row that moved
 * on meanwhile is left where it is.
 *
 * <p>{@link EntityStatus#VERIFIED}, {@link EntityStatus#DONE} and {@link EntityStatus#DROPPED}
 * therefore start no phase and deliver no turn, and that is where the one remaining human decision
 * lives: closing a ticket is a person's move, and so is abandoning one. DONE and DROPPED end the
 * flow at its first line and write nothing at all — a ticket whose work was decided against has
 * nothing to ask for and nobody to tell, and a comment saying so would be a comment about having
 * done nothing. VERIFIED does the one thing that is not a phase — it asks for the release of the
 * branch the work was done on — and the section below is the whole of it.
 *
 * <h2>VERIFIED asks for a release, and nothing else does</h2>
 *
 * <p>The verify phase found the change good. What the entity's branches then need is to be
 * released. So a move into VERIFIED opens (or joins) a release request for each of its branches that
 * is still unreleased — one per repository, since a request converges per repository — and the
 * thread is told which requests the entity now waits on (qits-1152: the agent names its own
 * submodule branches, {@code <prefix>/<qualified id>-<its choice>}).
 *
 * <p><b>The agents are looked up first, and the lookup gates the decision.</b> An entity no agent
 * ever worked on asks for nothing — one plain sentence on the thread. Any agent, <b>in any
 * state</b>, means work was done (qits-1218): {@link WorkspaceAgentDispatch#agentsFor} answers
 * OBSOLETE and REMOVED agents too, and an OBSOLETE agent is <em>not</em> one whose branches are
 * all gone — it became OBSOLETE when any one of them was released, and the rest may still wait.
 *
 * <p><b>The git host says which branches there are; the agents' push logs add to it.</b> Every
 * repository of the project is listed on the git host and each branch in the work item's scope is a
 * candidate, which also catches a branch pushed with a plain {@code git push} that no push log
 * recorded; every agent's pushed branches in scope are added. {@link ReleaseRequests#request}
 * validates a branch name syntactically and nothing else, so an ask naming a branch the git host no
 * longer has lands a row whose fold the git host refuses — so each candidate is put to the git host
 * first (qits-1220), and one it says is gone, or whose head is already released, is passed over and
 * named ({@link ReleasableBranch}).
 *
 * <p><b>Nothing left to release hands the workspace to cleanup.</b> Only a release marks an agent
 * OBSOLETE, and only an OBSOLETE agent is cleaned up, so where no request is asked for and none was
 * refused the wrapper branch is reported done through {@link ReleasedBranchWorkspaces} itself.
 *
 * <p><b>The call is made in process rather than through {@code ReleaseRequestController}, and
 * bypassing that door is the intent.</b> {@link ReleaseRequests} is role-blind; every check on the
 * release door — {@code @RolesAllowed}, {@code requireAgentProject}, {@code requireAgentBranch} —
 * lives in the controller and judges <em>the caller</em>. Here the caller is not who is asking: the
 * platform is the requester and the ticket lifecycle is the authority, exactly as it is for the turn
 * delivered one arm up. Going through the door would mean widening some agent's {@code git_refs}
 * scope so that pressing a transition also granted it the right to release the wrapper, which is a
 * far larger grant than this feature needs and one that would outlive the press.
 *
 * <h2>A move back into VERIFYING or IMPLEMENTED names the request and withdraws nothing</h2>
 *
 * <p>Verification can fail after the release was asked for, and then the ticket moves back. Nothing
 * is withdrawn, because there is no honest inverse to perform: no door removes a single source from
 * a request, the wrapper's request is estate-wide so {@code withdraw} would settle every other
 * branch's release along with this one, and deleting the branch would destroy the work. What the
 * platform owes a person instead is the request id on the thread, so that withdrawing or declining
 * it is a decision they can actually make.
 *
 * <p><b>That note is conditioned on the request existing and never on the direction of the move</b>,
 * which is this class's rule applied where it would be easiest to break: arriving at IMPLEMENTED
 * from READY_FOR_DEV nothing has been asked for, the query finds nothing and no comment is written;
 * arriving back from VERIFIED — at VERIFYING, its BACK move since qits-749 — it does, and the
 * sentence lands. The fact is read rather than inferred
 * from where the ticket came from, so there is still no second table saying which way is which.
 *
 * <h2>Why this is not on {@code WorkEntityService}</h2>
 *
 * <p>Because the entities module has no idea what a workspace is, and must keep not having one. {@code
 * entities/} depends on neither {@code domain} nor anything framework-shaped; it owns its database, its
 * errors and its lineage, and it is the module most likely to be lifted out of this repository next.
 * Putting this on {@code WorkEntityService} would make the lifecycle itself depend on {@code
 * control/WorkspaceAgentTurns}, on the project, on the wrapper repository and thereby on the whole
 * catalog — the entities jar would carry a workspace concept into any service that ever reused it, and
 * the lift-out would stop being a database move.
 *
 * <p>There is a second reason, and it survives even if the modules were one. {@code
 * WorkEntityService.transition} is the <b>recording</b> of a fact, held under the module's write
 * patience; delivery is an outward call to another service that may hang, refuse or be absent. A
 * transaction that had to wait on qits-workspaces before it could commit would let an unreachable
 * sibling fail a move that has already happened in every sense that matters, and a transaction that
 * called out and then rolled back would have spoken about a status no row ever held. Separating them
 * is what makes "the transition is recorded first and can never be undone by a delivery" a
 * structural property instead of a promise. Both call sites are non-transactional and call this
 * <em>after</em> the service returns, exactly as they already fire their change hints.
 *
 * <h2>It lives in {@code projects.api} for {@link EntityDispatch}'s reason</h2>
 *
 * <p>It needs {@code domain} — the project, the wrapper and the port — and the entities jar depends on
 * {@code domain} nowhere. The <em>service</em> layer may cross, which is the crossing {@code
 * WorkDispatchController} already makes, and the package name is where that crossing is declared.
 * It is {@code public} for one narrow reason: the transition surface is outside this package
 * ({@code eu.wohlben.qits.entities.api.WorkEntityDoors}), and the alternative — a copy per surface
 * — is the drift this class exists to prevent. {@link PhasePrompts} stays package-private and is read
 * from here, which is the whole reason this class is in that package rather than beside either
 * caller.
 *
 * <h2>What lands on the thread, and what deliberately does not</h2>
 *
 * <p>Every failure is a WARN and a sentence on the thread, because <b>that comment is the only place
 * a reader learns whether an agent is now working</b>. The sentence names the phase that was started
 * — from {@link PhasePrompts#start}, so the words and the naming come from one switch —
 * and where nothing was delivered it says so with the reason and <b>claims nothing about an
 * agent</b>: a thread saying work resumed when it did not is worse than a thread saying nothing.
 *
 * <p><b>A ticket with no agent gets no comment at all.</b> {@link
 * WorkspaceAgentTurns.Outcome#NO_AGENT} is not a failure — the delivery door never creates an
 * agent, so it is the ordinary answer for a ticket nobody has dispatched an agent onto — and a
 * person walking a ticket through the statuses by hand would otherwise have their thread filled with
 * "there was nobody to tell", once per move. The same silence covers a project with no wrapper
 * repository ({@link EntityWorkspaces#find}) and an assembly with no {@link WorkspaceAgentTurns}
 * implementation at all: in all three there is no workspace to speak to, nothing was asked of
 * anybody, and nothing is said.
 *
 * <p>The {@code TICKETS} hint is fired <b>only where a comment was written</b>, which is the rule the
 * rest of this code follows — a hint announces that something changed, and on the silent paths
 * nothing did. The transition's own hint has already gone out from the caller by then, so an open
 * browser has seen the status move regardless.
 */
@ApplicationScoped
public class PhaseAdvance {

  private static final Logger LOG = Logger.getLogger(PhaseAdvance.class);

  @Inject EntityCommentService comments;

  /** The move into IMPLEMENTING a delivered implement turn makes — see the class javadoc. */
  @Inject WorkEntityService lifecycle;

  @Inject EntityWorkspaces workspaces;

  /** Where a spent pre-approval is cleared (qits-1075). */
  @Inject EntityDispatchService entityDispatch;

  @Inject ProjectChangePublisher publisher;

  /**
   * Optional, like every port here, and <b>absent is a supported configuration</b>: nothing is
   * asked, nothing is said, and every transition behaves exactly as it did before this class
   * existed. See the class javadoc for why that silence is the same silence as a ticket with no
   * workspace, rather than a comment about missing configuration a reader could do nothing with.
   */
  @Inject Instance<WorkspaceAgentTurns> turns;

  /**
   * The read half of the dispatch port, and optional for {@link #turns}' reason: with no
   * implementation present there is no workspace to find, so a move into VERIFIED asks for nothing
   * and says nothing — the same silence an absent turn port gets. Named apart from {@link
   * #workspaces} beside it because the two answer different questions: that one derives the address
   * a workspace would stand at, this one says whether one actually does.
   */
  @Inject Instance<WorkspaceAgentDispatch> dispatchedWorkspaces;

  /**
   * Not an {@code Instance}, unlike every port here, because it is not a port: {@link
   * ReleaseRequests} is an {@code @ApplicationScoped} bean of this service's own {@code domain}
   * module, in this JVM, and an assembly without it is an assembly that does not start.
   */
  @Inject ReleaseRequests releaseRequests;

  /** Where a reported branch's repository is checked against the work item's project. */
  @Inject RepositoryService repositories;

  /**
   * Where a pushed branch is checked for still existing and for being unreleased before it is asked
   * for (qits-1220, {@link ReleasableBranch}). A port, so an {@code Instance}: <b>absent is "cannot
   * tell"</b>, and every branch is asked for exactly as it was before the check existed.
   */
  @Inject Instance<ReleaseGitHost> gitHosts;

  /** The releases not yet merged into their default branch — see {@link ReleasableBranch}. */
  @Inject ReleasedTagPendingMergeRepository pendingTags;

  /** Where the repositories of a work item's project are enumerated for the listing below. */
  @Inject ProjectService projects;

  /**
   * The branches each repository of the project has on the git host, the first source of what a
   * move into VERIFIED asks for (qits-1218). A port, so an {@code Instance}: <b>absent means the
   * agents' push logs are all there is</b>, exactly as before the listing existed.
   */
  @Inject Instance<BranchListing> branchListings;

  /**
   * Told the wrapper branch is done when a move into VERIFIED finds nothing left to release, so the
   * entity's agent is marked OBSOLETE and its workspace cleaned up (qits-1218) — the release that
   * would otherwise do it never happens. Optional, as {@code ReleaseRequests} injects it: absent,
   * the workspace lingers as it did before.
   */
  @Inject Instance<ReleasedBranchWorkspaces> releasedBranchWorkspaces;

  /**
   * The {@code version} that hand-off carries in place of a release version: what the far side's
   * history entry records as having ended the agent's work.
   */
  static final String VERIFIED_NOTHING_TO_RELEASE = "verified";

  /**
   * How long a move into VERIFIED waits for the git host's listings, all repositories together. This
   * runs synchronously inside the transition request, and a project the size of qits has ~60
   * repositories at ~270 ms per {@code ls-remote}: in series that was ~16 s on every VERIFIED move.
   */
  static final Duration LISTING_DEADLINE = Duration.ofSeconds(10);

  /** {@link #LISTING_DEADLINE}, package-private and mutable for the suite alone. */
  Duration listingDeadline = LISTING_DEADLINE;

  /**
   * How many listings run at once. Bounded rather than one per repository so a VERIFIED move does
   * not open sixty concurrent {@code ls-remote}s against the git host; eight brings ~60 repositories
   * to about two seconds.
   */
  static final int LISTING_THREADS = 8;

  /**
   * The listings' pool: plain daemon platform threads, {@code refinementhost/RefinementDrift}'s
   * shape — nothing a native image needs telling about. They carry no request context, which is why
   * the shipped {@link BranchListing} activates its own.
   */
  private final ExecutorService listingPool =
      Executors.newFixedThreadPool(
          LISTING_THREADS,
          runnable -> {
            Thread thread = new Thread(runnable, "verified-branch-listing");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdownListings() {
    listingPool.shutdownNow();
  }

  /**
   * Who asks for the release when the transition carried no name. The platform is the requester on
   * every one of these asks — see the class javadoc — and this is what that looks like on a request
   * a person reads.
   */
  private static final String PLATFORM_REQUESTER = "ticket-lifecycle";

  /**
   * Start the phase the ticket's new status begins, in the workspace standing on its branch, and say
   * on the thread what happened.
   *
   * <p><b>Never throws</b>, for the reason the port does not: it is called after a transition that is
   * already recorded, and nothing here may turn a move that happened into an error for the caller.
   * Both call sites wrap it anyway; that belt is theirs and this one is ours.
   *
   * <p><b>The signature takes {@code changedBy}</b>, resolved by the surface that took the
   * transition. The stamp on this comment is then the same string the transition's audit row
   * carries, and it keeps this bean out of the request context entirely.
   *
   * @param entity the moved row <b>as it is after the move</b> — a campaign, a feature and a task
   *     return at once (qits-763); for a ticket or an epic the new status is the input
   *     to which phase starts, and its {@code dispatchContinues} is whether it starts at all
   * @param statusBefore the status it moved from, read only to tell the platform's "phase started"
   *     move (READY_FOR_DEV → IMPLEMENTING, IMPLEMENTED → VERIFYING — it pushes nothing) and a
   *     person's scheduling (REFINED → READY_FOR_DEV — it pushes nothing either, qits-887) from any
   *     other arrival, and a refine that just landed REFINED; may be null
   * @param changedBy the caller, resolved by the surface that took the transition; may be null
   */
  public void afterTransition(WorkEntity entity, String statusBefore, String changedBy) {
    if (entity.archetype == Archetype.CAMPAIGN) {
      // A campaign stands in no workspace: its moves deliver no turn and release no branch. What a
      // campaign's move does do — pause or resume its executor — belongs to its own door (qits-411).
      return;
    }
    if (Archetypes.isPlanPiece(entity.archetype)) {
      // A feature or a task holds a status of its own since qits-763 but runs no phase: its moves
      // record where that piece stands, and the turn, the workspace and the release are its epic's.
      // Without this return, PhasePrompts.render and EntityWorkspaces would be asked for a phase a
      // piece does not have and throw on every move, which the doors only log.
      return;
    }
    // The merged row stores the status word; VERIFIED is compared against it by name, which is what
    // the column holds. See PhasePrompts.phaseOf for the same reading made one call down.
    if (EntityStatus.VERIFIED.name().equals(entity.status)) {
      // The one move that starts no phase and is still not nothing: the work is good, so the branch
      // it was done on is asked to be released. See the class javadoc.
      releaseAgentBranches(entity, changedBy);
      return;
    }
    if (entity.blocked) {
      // A blocked entity's phase is not started — a ticket's or an epic's alike (qits-592) — and the
      // ORDER against the clearing rule is the whole of what this arm means.
      // WorkEntityService.transition clears the flag unconditionally, so the entity handed here by
      // any transition surface is never blocked and the phase it just entered DOES start — which is
      // right: a block is about the phase that was running, and the one beginning now has not been
      // tried. What this catches is the other caller and the other state: an entity blocked in the
      // phase it is already standing in, for which delivering a turn would tell an agent to start
      // work somebody has already written down the obstacle to.
      LOG.debugf(
          "%s %s is blocked, so no turn is delivered for the phase its status (%s) starts",
          entity.archetype, entity.id, entity.status);
      return;
    }
    if (EntityStateMachine.isStartedMove(statusOf(statusBefore), statusOf(entity.status))) {
      // The phase was started — at a dispatch press, or by the agent at work moving itself. Its
      // prompt is out either way; a second one would restart the work (qits-749).
      LOG.debugf(
          "%s %s moved %s → %s; that phase is already under way, so no turn is delivered",
          entity.archetype, entity.id, statusBefore, entity.status);
      return;
    }
    if (isScheduling(statusOf(statusBefore), statusOf(entity.status))) {
      // REFINED → READY_FOR_DEV is a person's approval (qits-887), not a phase hand-off. Without
      // this arm an entity whose last press was FLOW, with its refine workspace still standing,
      // would be handed its implement turn the moment somebody schedules it. Starting the work is
      // the press — a person's, or a campaign executor's — and never the scheduling itself.
      LOG.debugf(
          "%s %s was scheduled (%s → %s); scheduling starts nothing, so no turn is delivered",
          entity.archetype, entity.id, statusBefore, entity.status);
      return;
    }
    Optional<Phase> phase = PhasePrompts.phaseOf(entity);
    if (phase.isEmpty()) {
      // REFINED (it waits for a person to schedule it, qits-887), DONE and DROPPED, now that
      // VERIFIED is answered above. A flow that has just refined is scheduled on the pre-approval
      // its person's press left (qits-1075) and carries straight on into implement; without one it
      // stops here, and says so on the thread when an agent stands on the branch, so the run does
      // not end in silence. Otherwise there is nothing to do, and nothing to say about having done
      // nothing.
      if (EntityStatus.REFINED.name().equals(entity.status)
          && EntityStatus.REPORTED.name().equals(statusBefore)
          && entity.dispatchContinues) {
        if (entity.preApprovedBy != null) {
          scheduleOnThePreApproval(entity, changedBy);
        } else {
          noteTheFlowWaitsForAPerson(entity, changedBy);
        }
      }
      return;
    }
    Optional<EntityWorkspaces.Target> target = addressOf(entity, phase.get());
    if (target.isEmpty()) {
      return;
    }
    if (entity.dispatchContinues) {
      handOff(entity, phase.get(), target.get(), changedBy);
    } else {
      // The continue-or-stop bit, read at its one place: the press that started this run asked for
      // one phase, so the next one waits for somebody to press again. Nothing is said on a thread —
      // whether a workspace even stands on the branch is not known without asking, and a person
      // stepping an entity by hand must not collect a sentence per move.
      LOG.infof(
          "%s %s moved to %s; its run was dispatched for one phase, so the %s phase is not started"
              + " until somebody presses again",
          entity.archetype, entity.id, entity.status, phase.get().word());
    }
    if (EntityStatus.IMPLEMENTED.name().equals(entity.status)
        || EntityStatus.VERIFYING.name().equals(entity.status)) {
      // Back from VERIFIED, a request may stand open — at VERIFYING since qits-749 (its BACK move),
      // still at IMPLEMENTED for the skip. Read, never inferred from the direction of the move.
      noteTheReleaseThatStandsOpen(entity, changedBy);
    }
  }

  /**
   * Where {@code entity}'s {@code phase} would be handed to, or empty — with nothing said — when
   * there is no turn port or no wrapper to stand a branch on (the latter a WARN). Shared by every
   * hand-off this class makes, so the two arms that deliver a turn cannot disagree about where.
   */
  private Optional<EntityWorkspaces.Target> addressOf(WorkEntity entity, Phase phase) {
    if (turns.isUnsatisfied()) {
      return Optional.empty();
    }
    Optional<EntityWorkspaces.Target> target = workspaces.find(entity);
    if (target.isEmpty()) {
      LOG.warnf(
          "Ticket %s moved to %s but its project (%s) has no wrapper repository, so there is no"
              + " branch to start the %s phase on",
          entity.id, entity.status, entity.projectId, phase.word());
    }
    return target;
  }

  /**
   * <b>The FLOW hand-off</b>: render the turn {@code phase} starts, deliver it into the workspace on
   * the branch, and — when it was spoken — move the entity into that phase's "-ING" status
   * (qits-749). The one place a phase's turn is handed on, whether a transition landed the status
   * that starts it or the platform's own scheduling did (qits-1075).
   */
  private void handOff(
      WorkEntity entity, Phase phase, EntityWorkspaces.Target target, String changedBy) {
    // The turn is rendered here, once a workspace could stand on the branch, with the same
    // qualified id the dispatch named that workspace with (qits-301).
    PhasePrompts.Started started =
        PhasePrompts.start(entity, phase, workspaces.qualifiedIdOf(entity));
    boolean spoken = deliver(entity, started, target, changedBy);
    if (spoken) {
      startPhase(entity, changedBy);
    }
  }

  /**
   * <b>The scheduling a person pre-approved</b> (qits-1075): a FLOW refine that lands REFINED on an
   * entity whose person pressed Dispatch at REPORTED is moved REFINED → READY_FOR_DEV <em>as that
   * person</em> — the audit row, the moved entity and its {@code EntityTransitioned} all name them
   * — then the pre-approval is cleared (spent: it is good for one scheduling), and the implement
   * phase is handed to the session that just refined it, exactly as the FLOW arm does at
   * READY_FOR_DEV ({@link #handOff}, which moves it on to IMPLEMENTING once the turn is spoken).
   *
   * <p>The move goes through {@link WorkEntityService#transitionFrom}, never a route, so this class
   * is not re-entered and the {@link #isScheduling} arm — which keeps a person's scheduling by hand
   * from pushing anything — is not consulted. Every gate still runs: the person behind the mover is
   * the one the press verified ({@code PERSON_APPROVAL}), and {@code ACCEPTANCE_CRITERIA} judges the
   * row. For an epic the move carries its REFINED features and tasks with it, as any scheduling does.
   *
   * <p><b>A gate's refusal moves nothing and keeps the pre-approval</b>, and the thread says which
   * gate and what a person can do about it — the run has stopped and a reader must not have to
   * guess why. Never throws, for {@link #afterTransition}'s reason.
   *
   * <p><b>The race with a campaign</b>: a member scheduled here stands at READY_FOR_DEV for the
   * moment between this move and the hand-off's move into IMPLEMENTING, and the campaign executor
   * claims READY_FOR_DEV members — the {@code EntityTransitioned} this move announces even asks it
   * to look. That window is exactly the one a person's READY_FOR_DEV press already opens (it too
   * schedules nothing it does not then start), and the executor treats an unclaimed IMPLEMENTING
   * member as started by hand and leaves it alone, so no new guard is added here.
   */
  private void scheduleOnThePreApproval(WorkEntity entity, String changedBy) {
    String person = entity.preApprovedBy;
    Optional<WorkEntityService.Transition> scheduled;
    try {
      scheduled =
          lifecycle.transitionFrom(
              entity.archetype,
              entity.id,
              EntityStatus.REFINED,
              EntityStatus.READY_FOR_DEV,
              Mover.person(person));
    } catch (ConflictException refused) {
      LOG.infof(
          "%s %s was pre-approved by %s but could not be scheduled: %s",
          entity.archetype, entity.id, person, refused.getMessage());
      say(entity, scheduleRefused(entity, person, refused.getMessage()), changedBy);
      return;
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "%s %s was pre-approved by %s but its scheduling failed unexpectedly",
          entity.archetype,
          entity.id,
          person);
      return;
    }
    if (scheduled.isEmpty()) {
      // Somebody moved it off REFINED a moment ago; whatever they did is the decision now.
      LOG.infof(
          "%s %s left REFINED before its pre-approved scheduling could be made; nothing moved",
          entity.archetype, entity.id);
      return;
    }
    WorkEntity row = scheduled.get().entity();
    try {
      row = entityDispatch.setPreApprovedBy(row.id, null, changedBy);
    } catch (RuntimeException e) {
      LOG.warnf(
          e, "%s %s was scheduled but its pre-approval could not be cleared", row.archetype, row.id);
    }
    LOG.infof(
        "%s %s was scheduled as %s on the pre-approval of their Dispatch press",
        row.archetype, row.id, person);
    Optional<EntityWorkspaces.Target> target = addressOf(row, Phase.IMPLEMENT);
    if (target.isPresent()) {
      handOff(row, Phase.IMPLEMENT, target.get(), changedBy);
    }
  }

  /**
   * What the thread is told when a pre-approved scheduling is refused (qits-1075): which gate and
   * why, that the pre-approval stands, and the two ways on once it is fixed.
   */
  static String scheduleRefused(WorkEntity entity, String person, String refusal) {
    return person
        + " pre-approved scheduling this "
        + noun(entity)
        + ", but the platform could not schedule it: "
        + refusal
        + ". It stays REFINED. Once that is fixed, a person can schedule it (READY_FOR_DEV), or"
        + " press Dispatch.";
  }

  /** Whether {@code from → to} is a person scheduling the entity: REFINED → READY_FOR_DEV. */
  private static boolean isScheduling(EntityStatus from, EntityStatus to) {
    return from == EntityStatus.REFINED && to == EntityStatus.READY_FOR_DEV;
  }

  /**
   * The sentence a FLOW run ends on when its refine phase lands REFINED (qits-887): REFINED starts
   * no phase, so the hand-off a flow used to make here is a person's scheduling now. Said only when
   * the entity has an ACTIVE agent — the one that just refined it — for the reason the no-agent case
   * says nothing anywhere else: a person walking an entity by hand must not
   * collect a sentence per move. Never throws.
   */
  private void noteTheFlowWaitsForAPerson(WorkEntity entity, String changedBy) {
    if (dispatchedWorkspaces.isUnsatisfied()) {
      return;
    }
    if (theLiveOne(agentsOf(entity)) == null) {
      return;
    }
    LOG.infof(
        "%s %s is REFINED; its flow waits for a person to schedule it", entity.archetype, entity.id);
    say(entity, WAITING_FOR_SCHEDULE, changedBy);
  }

  /** What a FLOW run says on the thread when it stops at REFINED (qits-887). */
  static final String WAITING_FOR_SCHEDULE =
      "Refined; waiting for a person to schedule it (READY_FOR_DEV).";

  /**
   * The ask that a move into VERIFIED makes, and the sentence that follows it (qits-1152, reworked
   * by qits-1218).
   *
   * <p><b>Whether anything is decided at all is gated on the entity having had an agent</b> — in
   * any state. An entity no agent ever worked on is the ordinary state of one walked through by
   * hand, and it asks for nothing and says so in one sentence. Any agent, ACTIVE, OBSOLETE or
   * REMOVED, means work was done on the entity's branches and some of it may still be unreleased:
   * an agent becomes OBSOLETE as soon as <em>any one</em> of its branches is released, so an
   * earlier agent's other branches may well still be on the git host (qits-168: the wrapper branch
   * of an OBSOLETE agent was never asked for, because only the ACTIVE agent's push log was read).
   *
   * <p><b>The candidates are what the git host has, unioned with what the agents pushed.</b> For
   * every repository of the entity's project, each branch the git host lists ({@link
   * BranchListing}) that lies in the scope this side derives for the work item ({@link
   * WorkBranches#isAgentBranch} over its own wrapper branch) is a candidate — which catches a branch
   * pushed with a plain {@code git push} that no push log ever recorded. Every agent's push log is
   * added on top, through the same scope check, and an agent-reported branch outside it is left off
   * and named on the thread. A repository whose listing cannot be read is logged and skipped; its
   * branches may still arrive through a push log. Candidates are deduplicated on (repository,
   * branch).
   *
   * <p>Each candidate is asked for in its own repository; {@link ReleaseRequests#request} converges
   * per repository, so this opens or joins one request per repository. The wrapper goes last, after
   * the submodules it points at.
   *
   * <p><b>Listed or pushed is not proof the branch is unreleased, so each candidate goes through
   * {@link ReleasableBranch} before it is asked for</b> (qits-1220). A push log keeps naming a branch
   * an earlier release shipped and deleted: qits-projects-service-rr-261 was opened for {@code
   * ticket/qits-1199}, which shipped on 2026-10-11 and was deleted, and its fold died on {@code 404
   * no-such-source}. A branch the git host answers {@code no-such-branch} for is <b>gone</b>, and one
   * whose head is contained in the repository's default branch or in a release still pending its
   * merge there is <b>already released</b> — neither is asked for, and the thread names both. Every
   * answer short of the host saying so — no git host, an unreachable one, a refusal — asks exactly as
   * before: a release is never dropped on a flaky moment.
   *
   * <p><b>When nothing was asked for and nothing was refused, the workspace is handed to
   * cleanup</b>: every candidate was gone or released, or there was none. Only a release marks an
   * agent OBSOLETE ({@link ReleasedBranchWorkspaces}), and the far side's cleanup only removes
   * OBSOLETE agents — so a VERIFIED item with nothing to release would otherwise keep its agent and
   * its workspace for ever. The same port is told the wrapper branch is done, with {@code verified}
   * standing in for a version. Where a request was asked for, that release resolves the workspace
   * when it lands; where an ask was refused, work may still be unreleased and nothing is resolved.
   */
  private void releaseAgentBranches(WorkEntity entity, String changedBy) {
    if (dispatchedWorkspaces.isUnsatisfied()) {
      // No implementation of the port: there is no agent anywhere, so there is nothing to release
      // and nothing to say. The same silence an absent turn port gets.
      LOG.debugf(
          "%s %s is VERIFIED, and no agent lookup is configured, so no release was asked for",
          entity.archetype, entity.id);
      return;
    }
    List<WorkspaceAgentDispatch.Agent> agents = agentsOf(entity);
    if (agents.isEmpty()) {
      // No agent at all is the ordinary state of an entity walked through by hand.
      LOG.debugf("%s %s has no agent, so it asks for no release", entity.archetype, entity.id);
      say(
          entity,
          "No agent is working on this " + noun(entity) + ", so no release was asked for.",
          changedBy);
      return;
    }
    Optional<EntityWorkspaces.Target> address = workspaces.find(entity);
    if (address.isEmpty()) {
      LOG.warnf(
          "%s %s is VERIFIED, but its branch cannot be named, so no release was asked for",
          entity.archetype, entity.id);
      say(
          entity,
          "The " + noun(entity) + "'s branch cannot be named, so no release was asked for.",
          changedBy);
      return;
    }
    String wrapperBranch = address.get().branch();
    WorkspaceAgentDispatch.Agent newest = theNewest(agents);
    String wrapperRepoId =
        newest.repositoryId() == null || newest.repositoryId().isBlank()
            ? address.get().repositoryId()
            : newest.repositoryId();

    // The git host first, then every agent's word. Only branches in the scope this side derives for
    // the work item, in a repository of the work item's project, may become release sources: the
    // release door's own checks are bypassed here on purpose (see the class javadoc), so this is
    // where they are made instead.
    java.util.Map<String, WorkspaceAgentDispatch.Branch> found = new java.util.LinkedHashMap<>();
    for (WorkspaceAgentDispatch.Branch branch : listedOnTheGitHost(entity, wrapperBranch)) {
      found.putIfAbsent(keyOf(branch), branch);
    }
    java.util.Set<String> outOfScope = new java.util.LinkedHashSet<>();
    for (WorkspaceAgentDispatch.Agent agent : agents) {
      for (WorkspaceAgentDispatch.Branch branch : agent.branches()) {
        if (inScope(entity, wrapperBranch, branch)) {
          found.putIfAbsent(keyOf(branch), branch);
        } else {
          LOG.warnf(
              "%s %s: agent %s reported %s in %s, outside its scope; not released",
              entity.archetype,
              entity.id,
              agent.agentId(),
              branch.branch(),
              branch.repositoryId());
          outOfScope.add("`" + branch.branch() + "`");
        }
      }
    }
    List<WorkspaceAgentDispatch.Branch> branches =
        found.values().stream()
            .sorted(
                java.util.Comparator.comparing(
                    branch -> branch.repositoryId().equals(wrapperRepoId)))
            .toList();
    String refusedScope =
        outOfScope.isEmpty()
            ? ""
            : " Not released, because they lie outside the agent's scope ("
                + wrapperBranch
                + " and "
                + wrapperBranch
                + WorkBranches.AGENT_SEPARATOR
                + "*): "
                + String.join(", ", outOfScope)
                + ".";
    if (branches.isEmpty()) {
      LOG.infof(
          "%s %s is VERIFIED, but no branch in its scope was found on the git host or among its"
              + " agents' pushes, so no release was asked for",
          entity.archetype, entity.id);
      say(
          entity,
          "No branch of this "
              + noun(entity)
              + " was found on the git host or among its agents' pushes, so no release was asked"
              + " for."
              + refusedScope
              + handedToCleanup(entity, wrapperRepoId, wrapperBranch),
          changedBy);
      return;
    }

    String requester = changedBy == null || changedBy.isBlank() ? PLATFORM_REQUESTER : changedBy;
    String summary =
        (entity.archetype == Archetype.TICKET ? "Ticket " : "Epic ")
            + entity.slug
            + ": "
            + entity.title;
    List<String> asked = new java.util.ArrayList<>();
    List<String> refused = new java.util.ArrayList<>();
    List<String> released = new java.util.ArrayList<>();
    List<String> gone = new java.util.ArrayList<>();
    ReleaseGitHost gitHost = gitHosts.isResolvable() ? gitHosts.get() : null;
    for (WorkspaceAgentDispatch.Branch branch : branches) {
      ReleasableBranch.Verdict verdict =
          ReleasableBranch.of(
              gitHost,
              branch.repositoryId(),
              branch.branch(),
              defaultBranchOf(branch.repositoryId()),
              releasedShasOf(branch.repositoryId()));
      if (verdict != ReleasableBranch.Verdict.ASK) {
        LOG.infof(
            "%s %s is VERIFIED, but %s in %s is %s, so its release was not asked for",
            entity.archetype,
            entity.id,
            branch.branch(),
            branch.repositoryId(),
            verdict == ReleasableBranch.Verdict.GONE
                ? "gone from the git host"
                : "already released");
        (verdict == ReleasableBranch.Verdict.GONE ? gone : released)
            .add("`" + branch.branch() + "`");
        continue;
      }
      try {
        ReleaseRequestDto request =
            releaseRequests.request(
                branch.repositoryId(),
                branch.branch(),
                summary,
                requester,
                // No priority: MEDIUM is what a caller who states nothing gets, and this caller has
                // nothing to state — the entity carries no urgency a release could read.
                null);
        LOG.infof(
            "%s %s is VERIFIED: release request %s now carries %s",
            entity.archetype, entity.id, request.id(), branch.branch());
        asked.add("`" + branch.branch() + "` (release request " + request.qualifiedId() + ")");
      } catch (RuntimeException e) {
        // The refusal arm's rule: name what did not happen, carry the far side's own reason, and
        // claim nothing that did.
        LOG.warnf(
            e,
            "Could not ask for the release of %s in %s for %s %s",
            branch.branch(),
            branch.repositoryId(),
            entity.archetype,
            entity.id);
        String detail = e.getMessage() == null ? "" : e.getMessage().trim();
        refused.add("`" + branch.branch() + "`" + (detail.isBlank() ? "" : ": " + detail));
      }
    }
    String skipped =
        (released.isEmpty()
                ? ""
                : " Not asked for, because they are already released: "
                    + String.join(", ", released)
                    + ".")
            + (gone.isEmpty()
                ? ""
                : " Not asked for, because they no longer exist on the git host: "
                    + String.join(", ", gone)
                    + ".");
    if (asked.isEmpty() && refused.isEmpty()) {
      // Every candidate was skipped: nothing is left to release, the work item moves on without a
      // request, and its workspace is handed to cleanup — no release will ever do that for it.
      say(
          entity,
          "Nothing was left to release, so no release was asked for."
              + skipped
              + refusedScope
              + handedToCleanup(entity, wrapperRepoId, wrapperBranch),
          changedBy);
      return;
    }
    StringBuilder sentence = new StringBuilder();
    if (!asked.isEmpty()) {
      sentence
          .append("Asked for the release of ")
          .append(String.join(", ", asked))
          .append(": the ")
          .append(noun(entity))
          .append(" now waits on ")
          .append(asked.size() == 1 ? "that request." : "those requests.");
    }
    if (!refused.isEmpty()) {
      if (sentence.length() > 0) {
        sentence.append(' ');
      }
      sentence
          .append("Could not ask for the release of ")
          .append(String.join("; ", refused))
          .append(".");
      if (asked.isEmpty()) {
        // Work may still be unreleased, so the workspace is not resolved: it stays for a person.
        sentence
            .append(" The ")
            .append(noun(entity))
            .append(" is VERIFIED, and its workspace is left standing.");
      }
    }
    say(entity, sentence.toString() + skipped + refusedScope, changedBy);
  }

  /**
   * Every branch the git host lists in a repository of the entity's project that lies in the work
   * item's scope ({@link WorkBranches#isAgentBranch}), in the project's repository order. The
   * listings run concurrently on {@link #listingPool}, at most {@link #LISTING_THREADS} at once, under
   * one {@link #listingDeadline} for all of them. A repository whose listing throws, or does not
   * answer within the deadline, is logged and skipped, and the others are still read; with no listing configured, or the project's
   * repositories unreadable, the answer is none and the agents' push logs are all there is.
   */
  private List<WorkspaceAgentDispatch.Branch> listedOnTheGitHost(
      WorkEntity entity, String wrapperBranch) {
    if (!branchListings.isResolvable()) {
      return List.of();
    }
    List<Repository> ofTheProject;
    try {
      ofTheProject = projects.getRepositories(entity.projectId);
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not read the repositories of project %s, so %s %s's branches are read from its"
              + " agents alone",
          entity.projectId,
          entity.archetype,
          entity.id);
      return List.of();
    }
    BranchListing listing = branchListings.get();
    List<Callable<List<String>>> asks =
        ofTheProject.stream()
            .map(repository -> (Callable<List<String>>) () -> listing.branches(repository.id))
            .toList();
    List<Future<List<String>>> answers;
    try {
      // invokeAll answers in the order asked, and cancels (interrupts) whatever is not done by the
      // deadline — so the outcome keeps the project's repository order whatever finished first.
      answers = listingPool.invokeAll(asks, listingDeadline.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warnf(
          "Interrupted listing the branches of project %s, so %s %s's branches are read from its"
              + " agents alone",
          entity.projectId,
          entity.archetype,
          entity.id);
      return List.of();
    }
    List<WorkspaceAgentDispatch.Branch> listed = new java.util.ArrayList<>();
    for (int i = 0; i < answers.size(); i++) {
      String repoId = ofTheProject.get(i).id;
      try {
        for (String branch : answers.get(i).get()) {
          if (WorkBranches.isAgentBranch(wrapperBranch, branch)) {
            listed.add(new WorkspaceAgentDispatch.Branch(repoId, branch));
          }
        }
      } catch (CancellationException e) {
        LOG.warnf(
            "The git host did not list the branches of %s within %s; it is skipped for %s %s",
            repoId,
            listingDeadline,
            entity.archetype,
            entity.id);
      } catch (ExecutionException e) {
        LOG.warnf(
            e.getCause(),
            "Could not list the branches of %s on the git host; it is skipped for %s %s",
            repoId,
            entity.archetype,
            entity.id);
      } catch (InterruptedException e) {
        // Unreachable — invokeAll returned, so every future is done — but never swallowed.
        Thread.currentThread().interrupt();
        return listed;
      }
    }
    return listed;
  }

  /** The key a candidate is deduplicated on: its repository and its branch. */
  private static String keyOf(WorkspaceAgentDispatch.Branch branch) {
    return branch.repositoryId() + "\u0000" + branch.branch();
  }

  /**
   * Tell the workspaces port the wrapper branch is done, so the entity's agent is marked OBSOLETE and
   * its workspace cleaned up, and answer the sentence that says so — or nothing, when there is no
   * port to tell or it threw. Never throws: the port says it must not, and a throw is a port bug
   * that may not touch a transition already recorded.
   */
  private String handedToCleanup(WorkEntity entity, String wrapperRepoId, String wrapperBranch) {
    if (!releasedBranchWorkspaces.isResolvable()) {
      return "";
    }
    try {
      releasedBranchWorkspaces
          .get()
          .branchReleased(wrapperRepoId, wrapperBranch, VERIFIED_NOTHING_TO_RELEASE, null);
      LOG.infof(
          "%s %s is VERIFIED with nothing to release: its workspace on %s was handed to cleanup",
          entity.archetype, entity.id, wrapperBranch);
      return " Its workspace was handed to cleanup.";
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not hand the workspace of %s %s on %s to cleanup: the port threw",
          entity.archetype,
          entity.id,
          wrapperBranch);
      return "";
    }
  }

  /**
   * The repository's default branch ({@link Repository#mainBranch}), {@code main} where it names
   * none or cannot be read — the fallback every other reader of that column makes.
   */
  private String defaultBranchOf(String repoId) {
    try {
      String main = repositories.get(repoId).mainBranch;
      return main == null || main.isBlank() ? "main" : main;
    } catch (RuntimeException e) {
      return "main";
    }
  }

  /**
   * The commits of the repository's releases not yet merged into its default branch, or none when
   * they cannot be read — fewer places to find a released head in, which only ever means asking.
   */
  private List<String> releasedShasOf(String repoId) {
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () ->
                  pendingTags.listPending(repoId).stream().map(row -> row.releasedSha).toList());
    } catch (RuntimeException e) {
      LOG.warnf(
          e, "Could not read the pending releases of %s; its default branch alone is asked", repoId);
      return List.of();
    }
  }

  /**
   * Whether a branch the agent reported may be released for {@code entity}: it lies in the scope
   * derived here from the work item ({@link WorkBranches#isAgentBranch}), and its repository is one
   * of the work item's project. A repository that cannot be read is not in scope.
   */
  private boolean inScope(
      WorkEntity entity, String wrapperBranch, WorkspaceAgentDispatch.Branch branch) {
    if (!WorkBranches.isAgentBranch(wrapperBranch, branch.branch())) {
      return false;
    }
    try {
      Repository repository = repositories.get(branch.repositoryId());
      return repository.project != null && entity.projectId.equals(repository.project.id);
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * Every agent of {@code entity}, or none when the port is absent or threw. The port says it must
   * not throw; a throw is a port bug and must not touch a transition already recorded.
   */
  private List<WorkspaceAgentDispatch.Agent> agentsOf(WorkEntity entity) {
    if (dispatchedWorkspaces.isUnsatisfied()) {
      return List.of();
    }
    try {
      // Matched back to this entity: an agent of another work item is never this one's.
      return dispatchedWorkspaces.get().agentsFor(List.of(entity.id)).stream()
          .filter(agent -> entity.id.equals(agent.workId()))
          .toList();
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not look up the agents of %s %s: the port threw", noun(entity), entity.id);
      return List.of();
    }
  }

  /**
   * The entity's one <b>ACTIVE</b> agent, or nothing — the agent a move back to IMPLEMENTED or
   * VERIFYING names the open release of.
   *
   * <p>{@link WorkspaceAgentDispatch#agentsFor} answers every agent that ever worked on the entity,
   * so that it keeps a link to where its work happened. An OBSOLETE or REMOVED agent is <b>not</b>
   * proof that its branches are gone — an agent becomes OBSOLETE as soon as any one of its branches
   * is released — which is why the release a move into VERIFIED asks for reads every agent and the
   * git host ({@link #releaseAgentBranches}). This note is only context on a move, and the live
   * agent is the one whose work is in question.
   */
  private static WorkspaceAgentDispatch.Agent theLiveOne(
      List<WorkspaceAgentDispatch.Agent> found) {
    return found.stream().filter(WorkspaceAgentDispatch.Agent::active).findFirst().orElse(null);
  }

  /**
   * The newest agent of the entity: its ACTIVE one, or else the newest of the rest — {@link
   * WorkspaceAgentDispatch#agentsFor} answers newest first. {@code found} is never empty.
   */
  private static WorkspaceAgentDispatch.Agent theNewest(List<WorkspaceAgentDispatch.Agent> found) {
    WorkspaceAgentDispatch.Agent live = theLiveOne(found);
    return live != null ? live : found.get(0);
  }

  /**
   * The second sentence a move into IMPLEMENTED sometimes gets: the release asked for earlier is
   * still standing, and it is a person's to withdraw or decline.
   *
   * <p>Read rather than inferred — see the class javadoc — so a ticket arriving from READY_FOR_DEV finds
   * nothing and is told nothing. A failed read writes nothing either: this note is context on a move
   * that has already happened and has already said what it started, and a sentence about the release
   * is worth exactly nothing if it might be wrong.
   *
   * <p>The state is left unnamed on purpose, because <b>absent is the word meaning open</b> here
   * ({@code ReleaseRequests.statesFor}): the open set is what it answers, plus the last few
   * FINALIZED requests as a tail. A finalized request is a release that landed and is nobody's to
   * withdraw, so the tail is dropped again on the way past.
   */
  private void noteTheReleaseThatStandsOpen(WorkEntity ticket, String changedBy) {
    WorkspaceAgentDispatch.Agent agent = theLiveOne(agentsOf(ticket));
    if (agent == null) {
      return;
    }
    Optional<EntityWorkspaces.Target> address = workspaces.find(ticket);
    if (address.isEmpty()) {
      return;
    }
    for (WorkspaceAgentDispatch.Branch branch : agent.branches()) {
      if (!inScope(ticket, address.get().branch(), branch)) {
        continue;
      }
      ReleaseRequestDto open;
      try {
        open =
            releaseRequests.listByRepo(branch.repositoryId(), null).stream()
                .filter(request -> !FINALIZED.equals(request.state()))
                .filter(
                    request ->
                        request.sources().stream()
                            .map(ReleaseRequestSourceDto::name)
                            .anyMatch(branch.branch()::equals))
                .findFirst()
                .orElse(null);
      } catch (RuntimeException e) {
        LOG.warnf(
            e,
            "Could not read the release requests of %s, so ticket %s was told nothing about the"
                + " release its branch is on",
            branch.repositoryId(),
            ticket.id);
        return;
      }
      if (open == null) {
        continue;
      }
      LOG.infof(
          "Ticket %s moved back to %s while release request %s still carries %s",
          ticket.id, ticket.status, open.id(), branch.branch());
      say(
          ticket,
          "Release request "
              + open.qualifiedId()
              + " still names `"
              + branch.branch()
              + "` as a source: the release was not withdrawn, and withdrawing or declining it is a"
              + " person's decision.",
          changedBy);
    }
  }

  /**
   * How a sentence on the thread names the entity it is on: {@code ticket} or {@code epic}. Since
   * qits-551 an epic's thread hears these sentences too, and "the ticket is REFINED" on an epic
   * would be a sentence about something else.
   */
  private static String noun(WorkEntity entity) {
    return entity.archetype.name().toLowerCase(java.util.Locale.ROOT);
  }

  /** The one state word this class reads, and it reads it to drop a row rather than to find one. */
  private static final String FINALIZED = "FINALIZED";

  /**
   * The FLOW hand-off's move into the "-ING" status of the phase whose turn was just spoken —
   * IMPLEMENTING from READY_FOR_DEV, VERIFYING from IMPLEMENTED; nothing for any other status — see the
   * class javadoc. Never throws: the turn is out and the thread has said so, and a refused move must
   * not reach the caller of a transition that already happened.
   */
  private void startPhase(WorkEntity entity, String changedBy) {
    EntityStatus from = statusOf(entity.status);
    if (from == null) {
      return;
    }
    EntityStateMachine.startedStatusOf(from)
        .ifPresent(
            to -> {
              try {
                lifecycle.transitionFrom(entity.archetype, entity.id, from, to, changedBy);
              } catch (RuntimeException e) {
                LOG.warnf(
                    e,
                    "%s %s was handed its %s turn but could not be moved to %s",
                    entity.archetype,
                    entity.id,
                    from,
                    to);
              }
            });
  }

  /** The stored word as a status, or null for none or for a word the lifecycle does not spell. */
  private static EntityStatus statusOf(String word) {
    if (word == null) {
      return null;
    }
    for (EntityStatus status : EntityStatus.values()) {
      if (status.name().equals(word)) {
        return status;
      }
    }
    return null;
  }

  /**
   * The turn, and the one sentence that follows it. Split out so the method above reads as the rule
   * it implements — status picks a phase, a phase needs an address — and this one as what is done
   * with the answer.
   *
   * @return whether the turn was spoken — delivered to an agent, or one was launched to take it
   */
  private boolean deliver(
      WorkEntity ticket,
      PhasePrompts.Started started,
      EntityWorkspaces.Target target,
      String changedBy) {
    String branch = target.branch();
    WorkspaceAgentTurns.Turn turn;
    try {
      turn = turns.get().deliver(ticket.id, started.instruction());
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not touch a transition that
      // has already been recorded. The thread still gets the honest sentence, because a reader
      // asking "is anything working on this?" is owed an answer either way.
      LOG.warnf(
          e,
          "Could not start the %s phase of ticket %s on %s: the delivery port threw",
          started.phase(),
          ticket.id,
          branch);
      turn =
          new WorkspaceAgentTurns.Turn(
              WorkspaceAgentTurns.Outcome.COULD_NOT, "the delivery failed unexpectedly");
    }

    if (turn.outcome() == WorkspaceAgentTurns.Outcome.NO_AGENT) {
      // The ordinary answer for a ticket nobody dispatched an agent onto. Said in the log for
      // somebody debugging a hand-off, and nowhere else — see the class javadoc.
      LOG.debugf(
          "Ticket %s has no agent, so it starts its %s phase on %s when somebody dispatches one",
          ticket.id, started.phase(), branch);
      return false;
    }

    if (turn.spoken()) {
      LOG.infof(
          "Started the %s phase of ticket %s (%s) in the workspace on %s (%s)",
          started.phase(), ticket.id, ticket.slug, branch, turn.outcome());
    } else {
      LOG.warnf(
          "Could not start the %s phase of ticket %s (%s) on %s: %s",
          started.phase(), ticket.id, ticket.slug, branch, turn.detail());
    }
    say(ticket, comment(ticket, started.phase(), branch, turn), changedBy);
    return turn.spoken();
  }

  /**
   * What the thread is told, one sentence per outcome.
   *
   * <p>The two spoken arms are kept apart rather than folded into "the turn was delivered", because
   * the difference is the one a reader of the thread is actually asking about: an agent that was
   * <em>already there</em> carried straight on, while one that had to be <b>launched</b> started from
   * a cold session — and if a phase produced nothing, which of the two happened is the first thing
   * worth knowing.
   *
   * <p>The refusal arm names the phase that did <em>not</em> start, carries the far side's own
   * reason, and says nothing whatsoever about an agent. It ends by naming the status the ticket now
   * holds, because that is what makes the sentence actionable: the move stands, and the phase is
   * started by pressing the ticket's own button.
   */
  private static String comment(
      WorkEntity ticket, String phase, String branch, WorkspaceAgentTurns.Turn turn) {
    return switch (turn.outcome()) {
      case DELIVERED ->
          "Started the "
              + phase
              + " phase: the agent working in the workspace on `"
              + branch
              + "` was told.";
      case LAUNCHED ->
          "Started the "
              + phase
              + " phase: no agent was running in the workspace on `"
              + branch
              + "`, so one was launched to take it.";
      case RESUMED ->
          "Started the "
              + phase
              + " phase: the agent on `"
              + branch
              + "` had yielded its workspace, so it was resumed to take it.";
      default ->
          "Could not start the "
              + phase
              + " phase"
              + (turn.detail().isBlank() ? "" : ": " + turn.detail())
              + ". The "
              + noun(ticket)
              + " is "
              + ticket.status
              + " and nothing is running on it.";
    };
  }

  /**
   * The comment on the entity's own thread, and the redraw that goes with it — {@code TICKETS} for
   * a ticket, {@code EPICS} for an epic ({@link ProjectChangeHint.Topic#of}). Wrapped for the same
   * reason the port's own call is: this runs after a recorded transition, and a store that refused
   * a comment must not reach the caller as a failure of a move that already happened. The hint is
   * fired here and only here, which is what makes "a hint only where something was written" true by
   * construction.
   */
  private void say(WorkEntity entity, String body, String changedBy) {
    try {
      comments.addComment(entity.id, body, changedBy);
      publisher.fire(entity.projectId, ProjectChangeHint.Topic.of(entity.archetype));
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not say on %s %s's thread what became of its next phase",
          entity.archetype,
          entity.id);
    }
  }
}
