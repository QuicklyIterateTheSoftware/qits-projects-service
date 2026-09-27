package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.campaign.CampaignStartRecord;
import eu.wohlben.qits.entities.campaign.CampaignStartRecordRepository;
import eu.wohlben.qits.entities.control.EntityDispatchService;
import eu.wohlben.qits.entities.control.ReadPatience;
import eu.wohlben.qits.entities.control.TicketCommentService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.error.DomainException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * <b>The one dispatch path</b> (qits-394): start the phase an entity's status implies, in the
 * workspace on its branch, and record whether the run continues past that phase. Every door that
 * puts an agent on an epic or a ticket comes through {@link #dispatch} — the unified {@code POST
 * /entities/{id}/dispatch} and, until the SPA stops calling them, the two old per-archetype doors,
 * which are thin delegates onto this.
 *
 * <h2>What it is, step by step, and why in this order</h2>
 *
 * <ol>
 *   <li><b>The row, of any archetype</b> (404). A kind with no lifecycle — a feature, a task — is a
 *       <b>409</b>: nothing stands an agent on one, and there is no status to read a phase from.
 *   <li><b>A block</b> (409, tickets only), before the status, for the reason the ticket door always
 *       gave: the status there is one a phase runs under, so "no phase left" would be false, and the
 *       answer that sends a reader to the thread comes first.
 *   <li><b>The phase</b>, from {@link PhasePrompts#startedBy} — VERIFIED, DONE and DROPPED start
 *       none and are a <b>409</b> naming the status. Decided before anything is asked of anybody.
 *   <li><b>The port</b> (503 when absent) and <b>the address</b> ({@link EntityWorkspaces#require},
 *       409 for a project with no wrapper). Knowable without attempting anything.
 *   <li><b>The bit</b>, written onto the entity ({@code EntityDispatchService.setDispatchContinues}),
 *       <em>before</em> the dispatch: an agent that claimed its phase faster than this request
 *       returned must already find the answer the press gave. A dispatch that then fails leaves the
 *       bit saying what the person last asked for, which is harmless — it is read only when a
 *       transition happens with a workspace standing.
 *   <li><b>The dispatch</b>, with the entity as the workspace's {@link WorkspaceAgentDispatch.Subject}
 *       and the archetype's own turn. A failure surfaces as the port's 502/503 and nothing is written
 *       on the entity after it.
 *   <li><b>The record</b>: a ticket's thread gets one comment naming the phase and the mode, and the
 *       {@code TICKETS} hint; an epic has no thread — its description is the plan, not a log — so it
 *       gets the {@code EPICS} hint alone.
 * </ol>
 *
 * <h2>Steps 1–4 are {@link #precheck}, and nothing after them is a refusal (qits-417)</h2>
 *
 * <p>Everything up to and including the address is decided without a write or a call out, and is
 * available on its own as {@link #precheck}, each refusal thrown as a {@link DispatchRefused}. The
 * press runs it first; from the bit onward every exception means the outcome is unknown — the bit
 * may be written, the far side may have stood a workspace up — which is the line the campaign
 * executor keeps or releases its claim on. The by-id overloads read the row fresh, in a transaction
 * of their own, so a caller that read it earlier is never handed its own stale read back.
 *
 * <h2>The bit is the whole difference between the two actions</h2>
 *
 * <p>{@link DispatchMode#FLOW} and {@link DispatchMode#PHASE} take this identical path and differ
 * only in what they write onto the row; {@link PhaseAdvance} reads it when the agent claims its
 * phase. So a second "Run the next phase" on an entity continues from its new status and records
 * PHASE again — which is the intended way to step an entity through by hand — and pressing
 * <em>Dispatch</em> on an entity a one-phase run left behind turns it back into a flow.
 *
 * <h2>Why a bean and not the controller's body</h2>
 *
 * <p>Three doors reach it and must not be three implementations; the old ones add nothing but their
 * own response shape (and, for the epic door, the freeze the older "Start implementation" button
 * always made first) — and both were removed in qits-399, leaving this the one path. It lives in {@code projects.api} for the reason every dispatch did: it
 * needs {@code domain} — the project, the wrapper, the port — and the entities jar depends on {@code
 * domain} nowhere.
 */
@ApplicationScoped
public class EntityDispatch {

  private static final Logger LOG = Logger.getLogger(EntityDispatch.class);

  @Inject EntityDispatchService entities;

  /** A ticket's thread is written through the comment service, the one writer of comments. */
  @Inject TicketCommentService tickets;

  @Inject EntityWorkspaces workspaces;

  /** A campaign's start, for the read {@link #state} answers a campaign with. */
  @Inject CampaignStartRecordRepository starts;

  @Inject ReadPatience reads;

  @Inject ProjectChangePublisher publisher;

  /** Optional, like every port here. Absent is a 503 naming what is missing. */
  @Inject Instance<WorkspaceAgentDispatch> dispatch;

  /**
   * What one press did.
   *
   * @param entity the entity as the press left it, the bit written
   * @param phase the phase its status started
   * @param mode what the press recorded
   * @param repositoryId the wrapper's catalog id the workspace stands on
   * @param branch the branch the workspace stands on
   * @param made what qits-workspaces made or adopted
   */
  public record Outcome(
      WorkEntity entity,
      String phase,
      DispatchMode mode,
      String repositoryId,
      String branch,
      WorkspaceAgentDispatch.Dispatch made) {

    /** The unified door's answer. */
    EntityDispatchDto toDto() {
      return new EntityDispatchDto(
          entity.id,
          entity.archetype.name(),
          phase,
          mode,
          made.workspaceRowId(),
          repositoryId,
          branch,
          made.fresh(),
          made.agentLaunch());
    }
  }

  /** The entity this id names, of any archetype, or a 404. */
  public WorkEntity get(String id) {
    return entities.get(id);
  }

  /**
   * Start the phase {@code id}'s status implies and record {@code mode} on it. See the class
   * javadoc for every refusal and the order they are decided in.
   *
   * @param changedBy the caller, for the audit row and the ticket comment; may be null
   */
  public Outcome dispatch(String id, DispatchMode mode, String changedBy) {
    return dispatch(entities.fresh(id), mode, changedBy); // 404
  }

  /**
   * The same press by id, refused with a {@link DispatchRefused} unless the phase the row's status
   * starts is {@code requiredPhase} — the campaign executor's door (qits-417), which starts a member
   * at REFINED, its implement phase, and nothing else. The phase is decided on the row as this call
   * reads it, fresh, so a member that moved on since the caller last looked (another press, an agent's
   * own claim) is refused here instead of being started at whatever phase it has reached.
   */
  public Outcome dispatch(
      String id, DispatchMode mode, String changedBy, String requiredPhase) {
    WorkEntity entity = entities.fresh(id); // 404
    requirePhase(entity, requiredPhase);
    return dispatch(entity, mode, changedBy);
  }

  /**
   * <b>Every refusal {@link #dispatch} would make, with no side effect</b> (qits-417): a campaign, a
   * kind with no lifecycle, a block, a status that starts no phase, no workspaces context, no
   * wrapper. Nothing is written and nothing is called out to; reads only (the project and its
   * wrapper, and an epic's tree for its refs). Each refusal is a {@link DispatchRefused} carrying the
   * status and the sentence the press would have answered.
   *
   * @return the phase a press would start now
   */
  public String precheck(WorkEntity entity) {
    return checked(entity).started().phase();
  }

  /** {@link #precheck}, and a {@link DispatchRefused} unless that phase is {@code requiredPhase}. */
  public String precheck(WorkEntity entity, String requiredPhase) {
    requirePhase(entity, requiredPhase);
    return precheck(entity);
  }

  /**
   * The same press over a row the caller already holds — for the old doors, which resolved it
   * through their own archetype's service (and, for the epic door, moved it) first. Passing the row
   * rather than re-reading it by id matters there: a request that read the row before a write made
   * in another transaction would be handed its stale first read back by its own session.
   */
  public Outcome dispatch(WorkEntity entity, DispatchMode mode, String changedBy) {
    // Every refusal first, with no side effect (a DispatchRefused); everything after this line is
    // the part that writes and calls out, so anything it throws means "outcome unknown".
    Checked checked = checked(entity);
    PhasePrompts.Started started = checked.started();
    EntityWorkspaces.Target target = checked.target();
    String branch = target.branch();

    WorkEntity recorded = entities.setDispatchContinues(entity.id, mode.continues(), changedBy);

    WorkspaceAgentDispatch.Dispatch made =
        dispatch
            .get()
            .dispatchAgent(
                target.repositoryId(),
                branch,
                target.scope().gitRefs(),
                true,
                EntityWorkspaces.subjectOf(recorded),
                started.instruction());

    if (recorded.archetype == Archetype.TICKET) {
      tickets.addComment(recorded.id, comment(branch, made, started.phase(), mode), changedBy);
      publisher.fire(recorded.projectId, ProjectChangeHint.Topic.TICKETS);
    } else {
      publisher.fire(recorded.projectId, ProjectChangeHint.Topic.EPICS);
    }
    LOG.infof(
        "Dispatched an agent onto %s %s (%s) for the %s phase (%s) in workspace %s on %s",
        noun(recorded),
        recorded.id,
        recorded.slug,
        started.phase(),
        mode,
        made.workspaceRowId(),
        branch);
    return new Outcome(recorded, started.phase(), mode, target.repositoryId(), branch, made);
  }

  /** What a press would do now — the read behind {@code GET /entities/{id}/dispatch}. */
  public EntityDispatchStateDto state(String id) {
    WorkEntity entity = entities.get(id);
    if (entity.archetype == Archetype.CAMPAIGN) {
      return campaignState(entity);
    }
    String nextPhase = PhasePrompts.nextPhase(entity).orElse(null);
    boolean lifecycle = entity.status != null && nextPhaseAware(entity);
    return new EntityDispatchStateDto(
        entity.id,
        entity.archetype.name(),
        entity.status,
        nextPhase,
        entity.blocked,
        nextPhase != null && !entity.blocked,
        lifecycle ? DispatchMode.of(entity.dispatchContinues) : null);
  }

  /**
   * <b>A campaign's read</b> (qits-417): a press is accepted whenever it is REFINED — while it runs
   * too, since a press re-checks every waiting member — and what it would do is start it, or, once a
   * start is active, re-check it. A campaign has no block and no phase of its own; the mode is FLOW
   * once it has ever been started (a campaign presses dispatch only) and null before.
   */
  private EntityDispatchStateDto campaignState(WorkEntity campaign) {
    Optional<CampaignStartRecord> start =
        reads.hold(
            "a campaign's start",
            () -> QuarkusTransaction.requiringNew().call(() -> starts.startOf(campaign.id)));
    boolean active = start.map(row -> row.active).orElse(false);
    return new EntityDispatchStateDto(
        campaign.id,
        campaign.archetype.name(),
        campaign.status,
        active ? "recheck" : "start",
        false,
        EntityStatus.REFINED.name().equals(campaign.status),
        start.isPresent() ? DispatchMode.FLOW : null);
  }

  // ---- the pieces --------------------------------------------------------------------------

  /** What {@link #checked} decided: the phase, and where it runs. */
  private record Checked(PhasePrompts.Started started, EntityWorkspaces.Target target) {}

  /**
   * The refusals, in the order the class javadoc gives them, each a {@link DispatchRefused}. The
   * wrapper's 409 comes out of {@link EntityWorkspaces#require} as a plain {@link DomainException}
   * and is re-thrown as a refusal with its words unchanged; anything else it throws (a database that
   * is gone) is not a refusal and passes through as it is.
   */
  private Checked checked(WorkEntity entity) {
    refuseCampaign(entity);
    PhasePrompts.Started started = phaseOrRefuse(entity);
    if (dispatch.isUnsatisfied()) {
      throw new DispatchRefused(
          503,
          "No workspaces context is configured, so no agent can be dispatched onto "
              + noun(entity)
              + " "
              + entity.id
              + ".");
    }
    EntityWorkspaces.Target target;
    try {
      target = workspaces.require(entity);
    } catch (DispatchRefused refused) {
      throw refused;
    } catch (DomainException refused) {
      throw new DispatchRefused(refused.statusCode(), refused.getMessage());
    }
    return new Checked(started, target);
  }

  /**
   * A {@link DispatchRefused} unless {@code entity}'s status starts {@code requiredPhase} — decided
   * after the refusals every press makes, so a blocked or finished row still answers its own
   * sentence first.
   */
  private static void requirePhase(WorkEntity entity, String requiredPhase) {
    refuseCampaign(entity);
    String phase = phaseOrRefuse(entity).phase();
    if (requiredPhase != null && !requiredPhase.equals(phase)) {
      throw new DispatchRefused(
          409,
          capitalised(noun(entity))
              + " "
              + entity.id
              + " is "
              + entity.status
              + ", where its "
              + phase
              + " phase runs; only its "
              + requiredPhase
              + " phase is started here.");
    }
  }

  /**
   * <b>A campaign is never dispatched onto a workspace</b> (qits-411, kept by qits-417 as a belt): it
   * has no branch, no workspace and no phase prompts — it orders work that is dispatched. Its press
   * is its start, which {@code EntityDispatchController} branches to before this class is reached;
   * this refusal is what keeps an in-process caller from ever cutting a workspace for one.
   */
  private static void refuseCampaign(WorkEntity entity) {
    if (entity.archetype == Archetype.CAMPAIGN) {
      throw new DispatchRefused(
          409,
          "Campaign "
              + entity.id
              + " is not dispatched onto a workspace — a campaign is started by pressing dispatch"
              + " on it, and its executor dispatches its members.");
    }
  }

  private static boolean nextPhaseAware(WorkEntity entity) {
    return entity.archetype == Archetype.TICKET || entity.archetype == Archetype.EPIC;
  }

  /**
   * The phase this entity's status starts, or the 409 that says there is none. The ticket door's
   * two sentences, kept word for word for a ticket — a person reads them — and said of an epic in
   * the same words with its own noun.
   */
  private static PhasePrompts.Started phaseOrRefuse(WorkEntity entity) {
    if (!nextPhaseAware(entity)) {
      throw new DispatchRefused(
          409,
          "A "
              + entity.archetype
              + " has no lifecycle of its own, so there is no phase to dispatch an agent onto —"
              + " dispatch its epic instead.");
    }
    if (entity.blocked) {
      throw new DispatchRefused(
          409,
          "Ticket "
              + entity.id
              + " is blocked, so its "
              + entity.status
              + " phase is not started — something is in the way and the ticket's thread says"
              + " what. Clear the block once that is resolved, then dispatch.");
    }
    return PhasePrompts.startedBy(entity)
        .orElseThrow(
            () ->
                new DispatchRefused(
                    409,
                    capitalised(noun(entity))
                        + " "
                        + entity.id
                        + " is "
                        + entity.status
                        + ", so there is no phase left to start — what remains is a person's to"
                        + " decide, and an agent is not dispatched onto work that is over or that"
                        + " was decided against."));
  }

  /**
   * What a ticket's thread is told. It names the phase, and for a one-phase run says that it stops
   * there, so a reader can tell a flow from a single step. A re-dispatch that found an agent already
   * working says so and names no phase: that agent was started for whatever the status said then.
   */
  private static String comment(
      String branch, WorkspaceAgentDispatch.Dispatch made, String phase, DispatchMode mode) {
    if ("SKIPPED_RUNNING".equals(made.agentLaunch())) {
      return "An agent is already working on this ticket in workspace `"
          + branch
          + "`; left it to carry on."
          + (mode == DispatchMode.PHASE
              ? " It stops after its current phase: the next one starts when somebody presses"
                  + " again."
              : "");
    }
    return "Dispatched a coding agent to workspace `"
        + branch
        + "` for the "
        + phase
        + " phase."
        + (mode == DispatchMode.PHASE
            ? " This run stops after that phase: the next one starts when somebody presses again."
            : "");
  }

  private static String noun(WorkEntity entity) {
    return entity.archetype.name().toLowerCase(java.util.Locale.ROOT);
  }

  private static String capitalised(String word) {
    return Character.toUpperCase(word.charAt(0)) + word.substring(1);
  }
}
