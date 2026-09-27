package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.control.EntityDispatchService;
import eu.wohlben.qits.entities.control.TicketCommentService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.error.DomainException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
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
    return dispatch(entities.get(id), mode, changedBy); // 404
  }

  /**
   * The same press over a row the caller already holds — for the old doors, which resolved it
   * through their own archetype's service (and, for the epic door, moved it) first. Passing the row
   * rather than re-reading it by id matters there: a request that read the row before a write made
   * in another transaction would be handed its stale first read back by its own session.
   */
  public Outcome dispatch(WorkEntity entity, DispatchMode mode, String changedBy) {
    refuseCampaign(entity);
    String id = entity.id;
    PhasePrompts.Started started = phaseOrRefuse(entity);
    if (dispatch.isUnsatisfied()) {
      throw new DomainException(
          503,
          "No workspaces context is configured, so no agent can be dispatched onto "
              + noun(entity)
              + " "
              + id
              + ".");
    }
    EntityWorkspaces.Target target = workspaces.require(entity);
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
    refuseCampaign(entity);
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

  // ---- the pieces --------------------------------------------------------------------------

  /**
   * <b>A campaign is never dispatched</b> (qits-411): it has no branch, no workspace and no phase
   * prompts — it orders work that is dispatched, and it starts through its executor. A 409 naming
   * the campaign, for the press and for the read alike, so neither answers a phase a campaign does
   * not have. (A later task of the campaigns epic replaces this with a branch in the controller.)
   */
  private static void refuseCampaign(WorkEntity entity) {
    if (entity.archetype == Archetype.CAMPAIGN) {
      throw new DomainException(
          409,
          "Campaign "
              + entity.id
              + " is not dispatched onto a workspace — a campaign starts through its executor.");
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
      throw new DomainException(
          409,
          "A "
              + entity.archetype
              + " has no lifecycle of its own, so there is no phase to dispatch an agent onto —"
              + " dispatch its epic instead.");
    }
    if (entity.blocked) {
      throw new DomainException(
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
                new DomainException(
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
