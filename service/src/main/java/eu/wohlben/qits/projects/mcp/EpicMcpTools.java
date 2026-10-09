package eu.wohlben.qits.projects.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import eu.wohlben.qits.entities.control.EntityBlockState;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.control.Nested;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.projects.api.PhaseAdvance;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.refinementhost.EntityResolutions;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The epic-refinement half of the "repository" MCP server — the surface a per-project refinement
 * agent drafts epics through, mounted on the same declared server as {@link RepositoryMcpTools}
 * ({@code /projects/mcp}) rather than on a second one: a new server name would need its own
 * declaration and its own daemon-side contract, and the agent wants both surfaces in one session
 * anyway (it reads the repositories it is planning work in).
 *
 * <p><strong>Use case: drafting and refining a plan.</strong> The agent lists the project's epics,
 * proposes a new one or extends a draft, and fills in its feature/task tree.
 *
 * <p><strong>{@code transition_epic} is here since qits-394, and it replaces a deliberate
 * absence.</strong> Freezing a draft used to be a human act in the UI alone, because nothing
 * dispatched an agent that could honestly claim a plan was complete. The one dispatch path now runs
 * an epic through the same refine → implement → verify phases a ticket runs, each ending with the
 * agent's own claim — and a phase whose claim cannot be made is a phase whose advance never fires.
 * So the lifecycle move is on this server exactly as {@code transition_ticket} is, reversible below
 * DONE (which is final) and adjacent-only, through {@code EntityResolutions} like every door that moves an epic, and followed by
 * {@code PhaseAdvance}. What stays off the server is supersede, which is an operation on a plan
 * rather than a claim about work.
 *
 * <p><strong>{@code transition_task} is here since qits-763</strong>, beside the markers: a feature
 * and a task hold the one lifecycle of their own now, so the agent implementing an epic verifies
 * each task as it confirms it rather than waiting for the epic's verification to drag every task
 * along. No phase follows such a move — a piece of a plan runs none.
 *
 * <p>Scope comes from {@link ProjectScope} (the {@code X-QITS-Project} header), never from a tool
 * argument, and every id a tool is handed is checked back to that project — an epic, feature or
 * task in another project reads as not found, so the model cannot draft across project boundaries.
 *
 * <p>{@link WrapBusinessError} turns anything a tool throws — the scoping checks here and the
 * epics services' {@code NotFoundException}/{@code BadRequestException}/{@code ConflictException} —
 * into a tool result with {@code isError=true} carrying the message. That matters most for the
 * freeze: writing to a frozen epic comes back as a readable refusal the model can act on, not as a
 * JSON-RPC protocol error that kills the turn.
 *
 * <p>Every mutating tool fires a {@link ProjectChangeHint} on the project's SSE channel, so a
 * browser watching the epics overview redraws as the agent works.
 *
 * <p><strong>No {@code @Transactional} here</strong>, unlike {@link RepositoryMcpTools} — and that
 * is not an oversight. These tools straddle two persistence units: the scope checks read {@code
 * epics} and the repository cross-check reads {@code projects}. Both datasources are local (non-XA)
 * resources, and Narayana can enlist only one of those per transaction, so wrapping a tool in one
 * aborts the second enlistment with "Enlisted connection used without active transaction" — and the
 * wedged pooled connection then fails unrelated writes elsewhere in the process. The epics services
 * each open their own transaction, exactly as they do for the REST controllers, which are
 * transaction-free for the same reason.
 */
@ApplicationScoped
@WrapBusinessError
public class EpicMcpTools {

  /**
   * What the audit log records for a write with no forwarded identity. An MCP session is a machine
   * caller; naming it beats a null {@code changed_by} that reads as "unknown human".
   */
  private static final String AGENT = "mcp-agent";

  /**
   * The tail every work-entity id argument's description carries (qits-954): either form names the
   * row, and the description is where the model learns that the {@code qualifiedId} these tools
   * answer is one it may hand straight back.
   */
  private static final String EITHER_FORM = ": its UUID or its qualified id (<project-slug>-<n>)";

  private static final String EPIC_ID = "id of an epic in this project" + EITHER_FORM;

  private static final String FEATURE_ID = "id of a feature in this project" + EITHER_FORM;

  private static final String TASK_ID = "id of a task in this project" + EITHER_FORM;

  @Inject ProjectScope scope;

  @Inject ProjectScopeGuard scopeGuard;

  @Inject WorkEntityService entities;

  /** The epic's own thread, embedded in {@code get_epic} (qits-551). */
  @Inject eu.wohlben.qits.entities.control.EntityCommentService thread;

  @Inject ProjectChangePublisher changePublisher;

  @Inject SecurityIdentity identity;

  /** The only way a door moves an epic: discards a refinement a resolving move would strand. */
  @Inject EntityResolutions resolutions;

  /** The next phase after an agent's claim — see {@code PhaseAdvance}. */
  @Inject PhaseAdvance phaseAdvance;

  /**
   * The UUID-or-qualified-id lookup the REST doors share — for {@code transition_task} first, and
   * since qits-954 behind every scope check here, so each tool takes either form of every id.
   */
  @Inject EntityIdResolver ids;

  private static final Logger LOG = Logger.getLogger(EpicMcpTools.class);

  // --- Result shapes --------------------------------------------------------

  /**
   * An epic as it appears in a list: no tree, just enough to choose one and read its phase.
   *
   * <h2>{@code qualifiedId} only, and never the bare number — this is a decision</h2>
   *
   * <p>Every entity-shaped return on this server carries {@code qualifiedId} ({@code qits-1337})
   * and <b>none of them carries the bare {@code number}</b>. An agent that is shown a bare number
   * will hand-prefix it, and it will get the qualifier wrong — the wrong project, the epic's slug,
   * the repository's name. The surface that exists so an id can be written into a commit subject
   * should only ever hand out the form that belongs in one.
   *
   * <p>The REST DTOs carry both, and that is not an inconsistency: the SPA renders the datum (a
   * sort, a filter, a column) as well as the string, and it is not the thing that types a commit
   * message. This rule applies to every record in this class and in {@code TicketMcpTools};
   * {@code DossierMcpTools}' pages are not entities and have neither.
   */
  public record EpicSummary(
      String id,
      String qualifiedId,
      String slug,
      String title,
      String status,
      boolean blocked,
      String description,
      List<String> acceptanceCriteria,
      @JsonInclude(JsonInclude.Include.NON_NULL) String blockSource,
      @JsonInclude(JsonInclude.Include.NON_NULL) String blockReason,
      @JsonInclude(JsonInclude.Include.NON_NULL) String blockedBy) {}

  /**
   * A task inside {@link EpicDetail}. {@code qualifiedId} only — see {@link EpicSummary}. {@code
   * status} is the task's own (qits-763): where it stands, which may differ from its siblings'.
   */
  public record TaskDetail(
      String id,
      String qualifiedId,
      String slug,
      String title,
      String status,
      String description,
      String repositoryId,
      String dependsOnTaskId,
      Instant implementedAt,
      Instant implementingAt) {}

  /** A feature inside {@link EpicDetail}, with its own status (qits-763) and its tasks. */
  public record FeatureDetail(
      String id,
      String qualifiedId,
      String slug,
      String title,
      String status,
      String description,
      String dependsOnFeatureId,
      Instant implementedOn,
      Instant implementingOn,
      List<TaskDetail> tasks) {}

  /**
   * One epic with its whole feature/task tree, and its own comment thread, oldest first (qits-551)
   * — a feature's or a task's thread is read with {@code list_comments}.
   */
  public record EpicDetail(
      String id,
      String qualifiedId,
      String slug,
      String title,
      String status,
      boolean blocked,
      String description,
      List<String> acceptanceCriteria,
      String supersededByEpicId,
      List<FeatureDetail> features,
      List<CommentMcpTools.CommentDetail> comments,
      @JsonInclude(JsonInclude.Include.NON_NULL) String blockSource,
      @JsonInclude(JsonInclude.Include.NON_NULL) String blockReason,
      @JsonInclude(JsonInclude.Include.NON_NULL) String blockedBy) {}

  /** A feature on its own, as returned by the feature write tools. */
  public record FeatureSummary(
      String id,
      String qualifiedId,
      String slug,
      String title,
      String status,
      String description,
      String dependsOnFeatureId) {}

  /** A task on its own, as returned by the task write tools. */
  public record TaskSummary(
      String id,
      String qualifiedId,
      String slug,
      String title,
      String status,
      String description,
      String repositoryId,
      String dependsOnTaskId) {}

  /**
   * What {@link #markTaskImplemented} answers: the task plus the marker it just wrote.
   *
   * <p>A record of its own rather than an {@code implementedAt} field on {@link TaskSummary}. That
   * shape is what {@code add_task}, {@code update_task} and the removal report return, and none of
   * them can ever carry a marker — {@code update_task} is refused outright once the epic leaves
   * REPORTED, and REPORTED is never a phase in which a marker can be written. Widening the shared
   * record would put a field on three tools that is null by construction, and a model reading a
   * null there would reasonably conclude the task is not implemented when nothing was asked.
   */
  public record TaskImplemented(
      String id,
      String qualifiedId,
      String slug,
      String title,
      String status,
      String repositoryId,
      Instant implementedAt) {}

  /**
   * What {@link #markTaskImplementing} answers: the task plus the implementing marker it holds now
   * — the one it just wrote, or the earlier one a repeated call kept. A record of its own for
   * {@link TaskImplemented}'s reason.
   */
  public record TaskImplementing(
      String id,
      String qualifiedId,
      String slug,
      String title,
      String status,
      String repositoryId,
      Instant implementingAt) {}

  /**
   * What {@link #transitionTask} answers: the feature or the task in its new status, and the status
   * it left. {@code archetype} says which of the two it was, since the tool takes either.
   */
  public record PieceTransitioned(
      String id,
      String qualifiedId,
      String archetype,
      String slug,
      String title,
      String statusBefore,
      String status) {}

  // --- Epics ----------------------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "list_epics",
      description =
          "List the epics of the project this session is scoped to, oldest first, without their"
              + " feature/task tree. Start here: call it with status=\"REPORTED\" to find the"
              + " drafts that are open for editing, and decide between extending one of them and"
              + " proposing a new epic. Only REPORTED epics can be changed at all; the other"
              + " statuses (REFINED, READY_FOR_DEV, IMPLEMENTING, IMPLEMENTED, VERIFYING, VERIFIED, DONE,"
              + " DROPPED) are"
              + " read-only here.")
  public List<EpicSummary> listEpics(
      @ToolArg(
              required = false,
              description =
                  "exact status to filter by: REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING,"
                      + " IMPLEMENTED, VERIFYING, VERIFIED, DONE or DROPPED. Omit for every epic of"
                      + " the project.")
          String status) {
    String projectSlug = projectSlug(); // once for the listing, never once per row
    return entities.listByProject(Archetype.EPIC, scope.requireProjectId(), status).stream()
        .map(epic -> summarizeEpic(epic, projectSlug))
        .toList();
  }

  @McpServer("repository")
  @Tool(
      name = "get_epic",
      description =
          "Read one epic of this project in full: its description plus every feature and, under"
              + " each, every task, and the epic's own comment thread, oldest first. Use it before"
              + " editing a draft, so the tree you extend is the one that exists. Every feature and"
              + " task carries its own status, and the implementing and implemented markers say when"
              + " it got there; they are set as work starts and ships and are not part of a draft."
              + " If you are the agent implementing this epic, record them with"
              + " mark_task_implementing and mark_task_implemented, and move a task further with"
              + " transition_task.")
  public EpicDetail getEpic(
      @ToolArg(description = EPIC_ID) String id) {
    WorkEntity epic = requireEpicInProject(id);
    String projectSlug = projectSlug(); // once for the whole tree, never once per node
    List<FeatureDetail> features =
        entities.listChildren(Archetype.FEATURE, epic.id).stream()
            .map(
                nested -> {
                  WorkEntity feature = nested.entity();
                  return new FeatureDetail(
                      feature.id,
                      QualifiedEntityIds.render(projectSlug, feature.number),
                      feature.slug,
                      feature.title,
                      feature.status,
                      feature.description,
                      feature.dependsOnEntityId,
                      feature.implementedAt,
                      feature.implementingAt,
                      entities.listChildren(Archetype.TASK, feature.id).stream()
                          .map(Nested::entity)
                          .map(
                              task ->
                                  new TaskDetail(
                                      task.id,
                                      QualifiedEntityIds.render(projectSlug, task.number),
                                      task.slug,
                                      task.title,
                                      task.status,
                                      task.description,
                                      task.repositoryId,
                                      task.dependsOnEntityId,
                                      task.implementedAt,
                                      task.implementingAt))
                          .toList());
                })
            .toList();
    EntityBlockState block = EntityBlockState.of(epic);
    return new EpicDetail(
        epic.id,
        QualifiedEntityIds.render(projectSlug, epic.number),
        epic.slug,
        epic.title,
        epic.status,
        block.blocked(),
        epic.description,
        List.copyOf(epic.acceptanceCriteria),
        epic.supersededByEntityId,
        features,
        CommentMcpTools.threadOf(thread, epic.id),
        block.source(),
        block.reason(),
        block.blockedBy());
  }

  @McpServer("repository")
  @Tool(
      name = "propose_epic",
      description =
          "Propose a new epic for this project. It is created as a REPORTED draft — nothing is"
              + " committed to and no branches are cut — so this is the right move whenever the"
              + " work does not belong under an existing draft. Freezing it (transition_epic to"
              + " REFINED) is the claim that its plan is complete — make it only if you were"
              + " dispatched to refine this epic.")
  public EpicSummary proposeEpic(
      @ToolArg(description = "short label for lists and breadcrumbs") String title,
      @ToolArg(required = false, description = "the long-form Markdown spine") String description,
      @ToolArg(
              required = false,
              description =
                  "acceptance criteria, in order, when they are already known; usually written"
                      + " later with update_epic while refining. Each item: no line break, at most"
                      + " one '.', and fewer than 20 whitespace characters.")
          List<String> acceptanceCriteria) {
    WorkEntity epic =
        entities
            .create(
                Archetype.EPIC,
                scope.requireProjectId(),
                EntityWrite.epic(title, description).withAcceptanceCriteria(acceptanceCriteria),
                changedBy())
            .entity();
    announce();
    return summarizeEpic(epic, projectSlug());
  }

  @McpServer("repository")
  @Tool(
      name = "update_epic",
      description =
          "Change an epic's title, description or acceptance criteria. Omitted fields keep their"
              + " current value. The title and description are the scope: refused once the epic"
              + " leaves REPORTED, since its scope is frozen from REFINED on and only a person moving"
              + " the epic back to REPORTED reopens it. The acceptanceCriteria are not scope: write"
              + " them while refining (moving to REFINED needs them) and they stay editable at"
              + " REFINED; from READY_FOR_DEV on they are frozen, because a person scheduled what"
              + " they say.")
  public EpicSummary updateEpic(
      @ToolArg(description = EPIC_ID) String id,
      @ToolArg(required = false, description = "new title; omit to keep it") String title,
      @ToolArg(required = false, description = "new description; omit to keep it")
          String description,
      @ToolArg(
              required = false,
              description =
                  "the whole list of acceptance criteria, in order, replacing the current one;"
                      + " omit to keep it, an empty list clears it. What the epic is accepted"
                      + " against: each item one short Markdown statement a reviewer can check."
                      + " Each item: no line break, at most one '.', and fewer than 20 whitespace"
                      + " characters.")
          List<String> acceptanceCriteria) {
    WorkEntity named = requireEpicInProject(id);
    // Only what was supplied is written, so a criteria-only call does not restate the frozen scope.
    WorkEntity epic =
        entities
            .update(
                Archetype.EPIC,
                named.id,
                EntityWrite.epic((title == null || title.isBlank()) ? null : title, description)
                    .withAcceptanceCriteria(acceptanceCriteria),
                changedBy())
            .entity();
    announce();
    return summarizeEpic(epic, projectSlug());
  }

  /**
   * The epic's LIFECYCLE move, the twin of {@code transition_ticket}: one adjacent step along the
   * one lifecycle ({@code EntityStateMachine}), or off it into DROPPED; nothing out of DONE. Through {@link
   * EntityResolutions}, never {@code WorkEntityService.transition}, so a resolving move discards the
   * epic's refinement first; then {@link PhaseAdvance}, after the move is recorded, which delivers the next
   * phase when the run was dispatched as a flow and asks for the release at VERIFIED. Supersede is
   * not reachable from here: the tool takes a status word, and {@code SUPERSEDED} is not one.
   */
  @McpServer("repository")
  @Tool(
      name = "transition_epic",
      description =
          "Move an epic along its lifecycle. A status is a claim about what has been ACHIEVED, so"
              + " only move to one you can honestly make: REPORTED — the work is raised and its plan"
              + " is being written; REFINED — the plan is complete: description, feature/task tree"
              + " and dossier, and moving here FREEZES that scope and needs acceptanceCriteria"
              + " (update_epic); READY_FOR_DEV — a PERSON scheduled"
              + " it (scheduling is a person's decision: this tool is refused it, PERSON_APPROVAL);"
              + " IMPLEMENTING — the implementation"
              + " was started (a dispatch press, or your first mark_task_implementing, moves the"
              + " epic here for you); IMPLEMENTED — every task is"
              + " marked with mark_task_implemented and every touched repository is released AND"
              + " deployed (moving here carries every feature and task still short of IMPLEMENTED"
              + " there and stamps its marker, so never make it with work outstanding); VERIFYING — the verification was started (a dispatch press moves the"
              + " epic here for you); VERIFIED — you confirmed on the platform that what the epic promised"
              + " holds; DONE — closed, which is a person's call; DROPPED — a decision was taken not"
              + " to do this work at all. Features and tasks hold statuses of their own: moving the"
              + " epic to REFINED refines its REPORTED ones, moving it back to REPORTED returns its"
              + " REFINED ones, scheduling it (READY_FOR_DEV) schedules its REFINED ones and"
              + " unscheduling it returns those not yet started, and moving it to IMPLEMENTED carries"
              + " them as above — no other move touches them, so verifying the epic verifies no task"
              + " (use transition_task)."
              + " ALONG THE PIPELINE MOVES ARE ADJACENT ONLY, forward or back: REPORTED <-> REFINED"
              + " <-> READY_FOR_DEV -> IMPLEMENTING <-> IMPLEMENTED <-> VERIFYING <-> VERIFIED ->"
              + " DONE, one step at a time (IMPLEMENTING has no move back: the way out of started"
              + " work is DROPPED), with TWO SKIPS: READY_FOR_DEV -> IMPLEMENTED and"
              + " IMPLEMENTED -> VERIFIED directly are allowed, for work finished without ever"
              + " being moved to IMPLEMENTING or VERIFYING. DONE IS FINAL: a DONE epic never moves again, and a follow-up is a NEW"
              + " epic (propose_epic). Reopening a frozen scope is the move back from REFINED to"
              + " REPORTED. Moving back corrects a claim that turned out wrong. It is not how a phase"
              + " reports failure: a phase that cannot finish, or a verification that fails, is"
              + " block_entity. Do NOT drop an epic merely because it is hard or you could not"
              + " finish it: block_entity with what is missing instead.")
  public EpicSummary transitionEpic(
      @ToolArg(description = EPIC_ID) String id,
      @ToolArg(
              description =
                  "the status to move to: REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING,"
                      + " IMPLEMENTED, VERIFYING, VERIFIED,"
                      + " DONE or DROPPED. On the pipeline it must be a neighbour of the epic's"
                      + " current status, or IMPLEMENTED from READY_FOR_DEV or VERIFIED from"
                      + " IMPLEMENTED"
                      + " (the skips); DONE is final and moves nowhere")
          String target) {
    WorkEntity named = requireEpicInProject(id);
    if (target != null && WorkEntityService.SUPERSEDE.equals(target)) {
      throw new eu.wohlben.qits.entities.error.ConflictException(
          "SUPERSEDED is an operation on a plan, not a status an agent claims — a person"
              + " supersedes an epic from the board.");
    }
    String changedBy = changedBy();
    WorkEntityService.Transition moved =
        resolutions.transition(Archetype.EPIC, named.id, target, Mover.machine(changedBy));
    WorkEntity epic = moved.entity();
    announce();
    // The agent's claim IS the trigger for the next phase: after the move, outside its transaction.
    try {
      phaseAdvance.afterTransition(epic, moved.statusBefore(), changedBy);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not start the phase epic %s just moved into", epic.id);
    }
    return summarizeEpic(epic, projectSlug());
  }

  // --- Features -------------------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "add_feature",
      description =
          "Add a feature to a REPORTED epic of this project. A feature is one shippable slice of"
              + " the epic; give it a body that says what it is, not how far along it is. Refused"
              + " once the epic leaves REPORTED.")
  public FeatureSummary addFeature(
      @ToolArg(description = "id of a REPORTED epic in this project" + EITHER_FORM) String epicId,
      @ToolArg(description = "short label for lists and breadcrumbs") String title,
      @ToolArg(required = false, description = "the long-form Markdown body") String description,
      @ToolArg(
              required = false,
              description =
                  "id of another feature IN THE SAME EPIC that this one depends on — its UUID or"
                      + " its qualified id (<project-slug>-<n>); omit for none")
          String dependsOnFeatureId) {
    WorkEntity epic = requireEpicInProject(epicId);
    Nested feature =
        entities.create(
            Archetype.FEATURE,
            epic.id,
            EntityWrite.feature(title, description, dependencyId(dependsOnFeatureId)),
            changedBy());
    announce();
    return summarizeFeature(feature, projectSlug());
  }

  @McpServer("repository")
  @Tool(
      name = "update_feature",
      description =
          "Change a feature of a REPORTED epic. Omitted fields keep their current value. Refused"
              + " once the owning epic leaves REPORTED. Neither the feature's status nor its"
              + " implemented marker is editable here: the marker is recorded by people as work"
              + " ships, and the status moves with transition_task once the epic is refined.")
  public FeatureSummary updateFeature(
      @ToolArg(description = FEATURE_ID) String id,
      @ToolArg(required = false, description = "new title; omit to keep it") String title,
      @ToolArg(required = false, description = "new description; omit to keep it")
          String description,
      @ToolArg(
              required = false,
              description =
                  "id of another feature in the same epic to depend on — its UUID or its"
                      + " qualified id (<project-slug>-<n>); omit to keep the current one")
          String dependsOnFeatureId) {
    WorkEntity named = requireFeatureInProject(id).entity();
    Nested feature =
        entities.update(
            Archetype.FEATURE,
            named.id,
            EntityWrite.nodeEdit(
                title, description, dependencyId(dependsOnFeatureId), false, null, false),
            changedBy());
    announce();
    return summarizeFeature(feature, projectSlug());
  }

  @McpServer("repository")
  @Tool(
      name = "remove_feature",
      description =
          "Remove a feature of a REPORTED epic, along with its tasks. Refused once the owning epic"
              + " leaves REPORTED.")
  public String removeFeature(
      @ToolArg(description = FEATURE_ID) String id) {
    WorkEntity named = requireFeatureInProject(id).entity();
    entities.delete(Archetype.FEATURE, named.id, changedBy());
    announce();
    return "Removed feature " + id;
  }

  // --- Tasks ----------------------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "add_task",
      description =
          "Add a task to a feature of a REPORTED epic. A task is the work in ONE repository, so"
              + " split a feature that spans several. Use list_repositories to pick a repositoryId;"
              + " it has to belong to this project. Refused once the owning epic leaves REPORTED.")
  public TaskSummary addTask(
      @ToolArg(description = FEATURE_ID) String featureId,
      @ToolArg(description = "id of a repository in this project — see list_repositories")
          String repositoryId,
      @ToolArg(description = "short label for lists and breadcrumbs") String title,
      @ToolArg(required = false, description = "the long-form Markdown body") String description,
      @ToolArg(
              required = false,
              description =
                  "id of another task IN THE SAME FEATURE that this one depends on — its UUID or"
                      + " its qualified id (<project-slug>-<n>); omit for none")
          String dependsOnTaskId) {
    WorkEntity feature = requireFeatureInProject(featureId).entity();
    // Project membership, exactly what the REST create checks (WorkEntityDoors.create): a
    // task must not bind a repository from another project. Deliberately NOT the session's
    // repository narrowing — this id is a reference to where the planned work belongs, not a git
    // target being read, and a refinement session stands on the project's wrapper while planning
    // work for the whole estate.
    scopeGuard.requireRepoInProjectUnnarrowed(repositoryId);
    Nested task =
        entities.create(
            Archetype.TASK,
            feature.id,
            EntityWrite.task(repositoryId, title, description, dependencyId(dependsOnTaskId)),
            changedBy());
    announce();
    return summarizeTask(task, projectSlug());
  }

  @McpServer("repository")
  @Tool(
      name = "update_task",
      description =
          "Change a task of a REPORTED epic. Omitted fields keep their current value. Refused once"
              + " the owning epic leaves REPORTED. Neither the task's status nor its markers are"
              + " editable here: the agent implementing the epic records them with"
              + " mark_task_implementing and mark_task_implemented, which move the task's status"
              + " too, and moves it further with transition_task.")
  public TaskSummary updateTask(
      @ToolArg(description = TASK_ID) String id,
      @ToolArg(required = false, description = "new title; omit to keep it") String title,
      @ToolArg(required = false, description = "new description; omit to keep it")
          String description,
      @ToolArg(
              required = false,
              description =
                  "id of another task in the same feature to depend on — its UUID or its"
                      + " qualified id (<project-slug>-<n>); omit to keep the current one")
          String dependsOnTaskId) {
    WorkEntity named = requireTaskInProject(id).entity();
    Nested task =
        entities.update(
            Archetype.TASK,
            named.id,
            EntityWrite.nodeEdit(
                title, description, dependencyId(dependsOnTaskId), false, null, false),
            changedBy());
    announce();
    return summarizeTask(task, projectSlug());
  }

  /**
   * The marker an <b>implementing</b> agent sets, and deliberately a tool of its own rather than a
   * widening of {@link #updateTask}.
   *
   * <p>That tool's description says "the implemented marker is not editable here — that is recorded
   * by people as work ships", and it stays exactly as true as it was. It is a stance about the
   * <em>refining</em> agent: one that is drafting a plan must not also be able to declare parts of
   * that plan shipped, or the scope and the progress have the same author. The agent this tool is
   * for is a different one on a different branch — dispatched by {@code EntityDispatch} onto
   * an epic that is already frozen — and it reports on work it actually did. Two agents, two
   * stances, two tools; the refusal in {@code update_task} is not softened, it is pointed at its
   * neighbour.
   *
   * <p>The guard is the lifecycle's own and not a second copy of it: this lands on {@code
   * WorkEntityService.update}'s marker arm alone (an {@code EntityWrite} that touches the marker and
   * no scope), so {@code EntityLifecycle.requireBeingImplemented} is what runs and its message is
   * what a REPORTED, REFINED or finished epic answers with. The epic may be READY_FOR_DEV or
   * IMPLEMENTING (qits-749, qits-887), and {@code mark_task_implementing} before this is optional: without it the
   * task's {@code implementingAt} simply stays null.
   *
   * <p><b>This is an interim and it is written to be easy to remove.</b> Nothing on the platform
   * derives these markers today — no listener sets one when a task's work merges — so a dispatched
   * agent has no way to record progress except by being asked to. What replaces it is a
   * merge-derived marker: a consumer that reads a landed change back to the task it implements and
   * stamps {@code implementedAt} from the merge. On the day that exists, this tool goes and nothing
   * else changes — the marker's semantics are identical either way, the same column with the same
   * meaning, and only the writer moves from a prompt to an event.
   */
  @McpServer("repository")
  @Tool(
      name = "mark_task_implemented",
      description =
          "Record that a task's work has landed. Accepted only while the owning epic is being"
              + " implemented — READY_FOR_DEV or IMPLEMENTING; a task of a REPORTED epic has nothing to"
              + " mark yet, one of a REFINED epic waits for a person to schedule it, and one of a"
              + " finished epic is already settled. This is the marker a"
              + " dispatched implementing agent sets as it goes: mark each task as its work lands,"
              + " rather than all of them at the end. It moves the task's own status to IMPLEMENTED"
              + " (a task already further on, say VERIFIED, stays where it is). Calling"
              + " mark_task_implementing first is expected but not required.")
  public TaskImplemented markTaskImplemented(
      @ToolArg(description = TASK_ID) String id) {
    WorkEntity named = requireTaskInProject(id).entity();
    WorkEntity task =
        entities
            .update(
                Archetype.TASK, named.id, EntityWrite.implementedAt(Instant.now()), changedBy())
            .entity();
    announce();
    return new TaskImplemented(
        task.id,
        QualifiedEntityIds.render(projectSlug(), task.number),
        task.slug,
        task.title,
        task.status,
        task.repositoryId,
        task.implementedAt);
  }

  /**
   * <b>The implementing marker</b> (qits-749): the task's work was started. Its sibling {@link
   * #markTaskImplemented} records that the work landed; this records that it began. It stamps the
   * task's {@code implementingAt} (and its feature's, the first time) when unset and keeps it when
   * set, so a second call is harmless, and since qits-763 it moves the task — and its feature, the
   * first time — to IMPLEMENTING; on an epic still READY_FOR_DEV it moves the epic to IMPLEMENTING
   * too, so
   * an agent that starts without a dispatch press still shows on the board. All of it is {@code
   * WorkEntityService.markImplementing}'s, guarded by {@code
   * EntityLifecycle.requireBeingImplemented} like the sibling. Skippable: a task may be marked
   * implemented without ever being marked implementing.
   */
  @McpServer("repository")
  @Tool(
      name = "mark_task_implementing",
      description =
          "Record that you started a task's work. Accepted only while the owning epic is being"
              + " implemented — READY_FOR_DEV or IMPLEMENTING (a REFINED epic waits for a person to"
              + " schedule it). It stamps the task (and its feature, the first time) as"
              + " implementing and moves its status to IMPLEMENTING, and an epic still"
              + " READY_FOR_DEV moves to IMPLEMENTING."
              + " Idempotent: a task already marked keeps its first time. Call it as you start each"
              + " task, then mark_task_implemented as its work lands.")
  public TaskImplementing markTaskImplementing(
      @ToolArg(description = TASK_ID) String id) {
    WorkEntity named = requireTaskInProject(id).entity();
    WorkEntity task = entities.markImplementing(named.id, changedBy()).entity();
    announce();
    return new TaskImplementing(
        task.id,
        QualifiedEntityIds.render(projectSlug(), task.number),
        task.slug,
        task.title,
        task.status,
        task.repositoryId,
        task.implementingAt);
  }

  /**
   * <b>A feature's or a task's own lifecycle move</b> (qits-763) — {@code transition_ticket}'s shape
   * for the two kinds that hold a status but run no phase. One tool for both, because they are one
   * kind of claim: where a piece of the plan stands. It goes through {@link EntityResolutions} like
   * every door's move, so the rules are the service's and nothing here re-states them: the epic's
   * graph and both skips, a 409 while the owning epic is REPORTED (the plan is a draft — edit or
   * remove the piece instead), and a feature's move to IMPLEMENTED carrying its tasks.
   *
   * <p>{@code PhaseAdvance} is called after the move exactly as {@code transition_ticket} calls it,
   * and returns at once: no phase runs on a piece, so no turn is delivered and no branch is
   * released. Kept rather than skipped so the two tools stay one shape and the "no phase for a
   * piece" rule stays in one place.
   *
   * <p>Not for the markers: starting and landing a task are {@code mark_task_implementing} and
   * {@code mark_task_implemented}, which stamp when it happened as well as moving the status. This
   * is for everything after — above all VERIFYING and VERIFIED, which a task now reaches on its own
   * rather than when its epic does.
   */
  @McpServer("repository")
  @Tool(
      name = "transition_task",
      description =
          "Move a feature or a task of this project along its own lifecycle — the same walk an epic"
              + " and a ticket take. A status is a claim about what that piece has ACHIEVED, so only"
              + " move to one you can honestly make: REPORTED — part of a draft plan; REFINED — the"
              + " plan it belongs to is complete; READY_FOR_DEV — its epic was scheduled by a person"
              + " (it moves with its epic, never on its own — its own move to READY_FOR_DEV, or back,"
              + " is refused); IMPLEMENTING — its work was started"
              + " (mark_task_implementing moves a task here for you); IMPLEMENTED — its work is"
              + " released AND deployed (mark_task_implemented moves a task here for you); VERIFYING"
              + " — you started checking it on the platform; VERIFIED — you confirmed on the platform"
              + " that what it promised holds; DONE — closed, which is a person's call; DROPPED — a"
              + " decision was taken not to do this piece at all. A TASK IS VERIFIED ON ITS OWN:"
              + " verify each one as you confirm it, without waiting for its siblings or its epic —"
              + " moving the epic does not move its tasks past IMPLEMENTED. ALONG THE PIPELINE MOVES"
              + " ARE ADJACENT ONLY, forward or back: REPORTED <-> REFINED <-> READY_FOR_DEV ->"
              + " IMPLEMENTING <-> IMPLEMENTED <-> VERIFYING <-> VERIFIED -> DONE (IMPLEMENTING has"
              + " no move back), with TWO SKIPS: READY_FOR_DEV -> IMPLEMENTED and IMPLEMENTED ->"
              + " VERIFIED. DONE IS FINAL. Refused while the owning"
              + " epic is REPORTED: the plan is still a draft, so edit or remove the piece instead."
              + " Moving a feature to IMPLEMENTED carries its tasks that are not there yet. Moving"
              + " back corrects a claim that turned out wrong; it is not how a phase reports"
              + " failure — block the epic with block_entity for that. Do NOT drop a piece merely"
              + " because it is hard.")
  public PieceTransitioned transitionTask(
      @ToolArg(
              description =
                  "id of a feature or a task in this project" + EITHER_FORM)
          String id,
      @ToolArg(
              description =
                  "the status to move to: REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING,"
                      + " IMPLEMENTED, VERIFYING, VERIFIED, DONE or DROPPED. On the pipeline it must"
                      + " be a neighbour of the current status, or IMPLEMENTED from READY_FOR_DEV or"
                      + " VERIFIED from IMPLEMENTED"
                      + " (the skips); DROPPED is reachable from any status that is not DONE, and"
                      + " reopens only to REPORTED; DONE is final and moves nowhere")
          String target) {
    WorkEntity piece = requirePieceInProject(id);
    String changedBy = changedBy();
    WorkEntityService.Transition moved =
        resolutions.transition(piece.archetype, piece.id, target, Mover.machine(changedBy));
    WorkEntity row = moved.entity();
    announce();
    try {
      phaseAdvance.afterTransition(row, moved.statusBefore(), changedBy);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not start the phase %s %s just moved into", row.archetype, row.id);
    }
    return new PieceTransitioned(
        row.id,
        QualifiedEntityIds.render(projectSlug(), row.number),
        row.archetype.name(),
        row.slug,
        row.title,
        moved.statusBefore(),
        row.status);
  }

  @McpServer("repository")
  @Tool(
      name = "remove_task",
      description =
          "Remove a task of a REPORTED epic. Refused once the owning epic leaves REPORTED.")
  public String removeTask(@ToolArg(description = TASK_ID) String id) {
    WorkEntity named = requireTaskInProject(id).entity();
    entities.delete(Archetype.TASK, named.id, changedBy());
    announce();
    return "Removed task " + id;
  }

  // --- Scoping --------------------------------------------------------------

  /**
   * Ensures {@code epicId} names an epic of the scoped project, and answers the row — <b>whose
   * {@code id} is what every tool hands on</b>, never the argument. An epic elsewhere reads as not
   * found rather than as forbidden — the model is told nothing about what other projects hold.
   *
   * <p>The argument is either form (qits-954): the UUID, or the qualified id {@code
   * <project-slug>-<n>} every answer here carries, resolved by {@link EntityIdResolver} exactly as
   * {@code get_entity} and the {@code /work} doors resolve it. The services' writes are primary-key
   * writes, so {@code qits-7} handed on as written would name nothing there — which is why only the
   * resolved row's UUID travels past these checks.
   */
  private WorkEntity requireEpicInProject(String epicId) {
    WorkEntity epic = resolveOf(Archetype.EPIC, "Epic", epicId);
    if (!scope.requireProjectId().equals(epic.projectId)) {
      throw new NotFoundException("Epic not found in this project: " + epicId);
    }
    return epic;
  }

  /** The owning epic is the membership edge's parent now, carried beside the row as a {@link Nested}. */
  private Nested requireFeatureInProject(String featureId) {
    Nested feature =
        entities.nested(Archetype.FEATURE, resolveOf(Archetype.FEATURE, "Feature", featureId).id);
    requireEpicInProject(feature.parentId());
    return feature;
  }

  private Nested requireTaskInProject(String taskId) {
    Nested task = entities.nested(Archetype.TASK, resolveOf(Archetype.TASK, "Task", taskId).id);
    requireFeatureInProject(task.parentId());
    return task;
  }

  /**
   * The row {@code id} names, in either form, if it is of {@code archetype}. A miss reads as "not
   * found in this project", as the scope checks' own refusal does — from the caller's side the two
   * are one answer — and a row of another kind keeps the refusal {@code WorkEntityService.get}
   * always gave it, naming what was asked for rather than the UUID it resolved to.
   */
  private WorkEntity resolveOf(Archetype archetype, String noun, String id) {
    WorkEntity row;
    try {
      row = ids.resolve(id);
    } catch (NotFoundException e) {
      throw new NotFoundException(noun + " not found in this project: " + id);
    }
    if (row.archetype != archetype) {
      throw new NotFoundException(noun + " not found: " + id);
    }
    return row;
  }

  /**
   * A {@code dependsOn*} argument as the UUID the service stores, from either form. Null and blank
   * are handed on as they came — they keep meaning what they meant (none, or unchanged) — and so is
   * a value naming nothing, for the service's own refusal about it, the rule {@code
   * WorkMembersController} applies to a criterion's {@code entityId}. Whether the row is a sibling,
   * and so in this project at all, is the service's check, which runs on the resolved id.
   */
  private String dependencyId(String named) {
    if (named == null || named.isBlank()) {
      return named;
    }
    try {
      return ids.resolve(named).id;
    } catch (NotFoundException unknown) {
      return named;
    }
  }

  /**
   * A feature or a task of the scoped project, named by UUID or qualified id. Any other kind, or a
   * row elsewhere, reads as not found — the tool's subject is a piece of a plan and nothing else.
   */
  private WorkEntity requirePieceInProject(String id) {
    WorkEntity row;
    try {
      row = ids.resolve(id);
    } catch (NotFoundException e) {
      throw new NotFoundException("Feature or task not found in this project: " + id);
    }
    if (row.archetype != Archetype.FEATURE && row.archetype != Archetype.TASK) {
      throw new NotFoundException(
          "Feature or task not found in this project: "
              + id
              + " is a "
              + row.archetype
              + " — move a ticket with transition_ticket and an epic with transition_epic.");
    }
    if (!scope.requireProjectId().equals(row.projectId)) {
      throw new NotFoundException("Feature or task not found in this project: " + id);
    }
    return row;
  }

  // --- Plumbing -------------------------------------------------------------

  /** Tell the project's browsers to re-read the epic tree. */
  private void announce() {
    changePublisher.fire(scope.requireProjectId(), ProjectChangeHint.Topic.EPICS);
  }

  /** The audit's {@code changed_by}: the forwarded user, else the agent marker. */
  private String changedBy() {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return AGENT;
    }
    return identity.getPrincipal().getName();
  }

  /**
   * The project slug the qualified ids in this call are rendered with, <b>resolved once</b>. Every
   * row a tool answers is in the session's project, so one lookup covers a whole listing — see
   * {@link ProjectScopeGuard#scopedProjectSlug()}.
   */
  private String projectSlug() {
    return scopeGuard.scopedProjectSlug();
  }

  private static EpicSummary summarizeEpic(WorkEntity epic, String projectSlug) {
    EntityBlockState block = EntityBlockState.of(epic);
    return new EpicSummary(
        epic.id,
        QualifiedEntityIds.render(projectSlug, epic.number),
        epic.slug,
        epic.title,
        // The merged column holds the enum's own name(), which is what the old status.name() was.
        epic.status,
        block.blocked(),
        epic.description,
        List.copyOf(epic.acceptanceCriteria),
        block.source(),
        block.reason(),
        block.blockedBy());
  }

  /**
   * Named apart from its two neighbours rather than overloaded: a feature and a task are both a
   * {@link Nested} now, so the three would share one erasure.
   */
  private static FeatureSummary summarizeFeature(Nested nested, String projectSlug) {
    WorkEntity feature = nested.entity();
    return new FeatureSummary(
        feature.id,
        QualifiedEntityIds.render(projectSlug, feature.number),
        feature.slug,
        feature.title,
        feature.status,
        feature.description,
        feature.dependsOnEntityId);
  }

  private static TaskSummary summarizeTask(Nested nested, String projectSlug) {
    WorkEntity task = nested.entity();
    return new TaskSummary(
        task.id,
        QualifiedEntityIds.render(projectSlug, task.number),
        task.slug,
        task.title,
        task.status,
        task.description,
        task.repositoryId,
        task.dependsOnEntityId);
  }
}
