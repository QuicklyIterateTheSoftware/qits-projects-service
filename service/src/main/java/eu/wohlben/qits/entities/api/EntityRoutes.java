package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.Nested;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.dto.EpicDto;
import eu.wohlben.qits.entities.dto.FeatureDto;
import eu.wohlben.qits.entities.dto.TaskDto;
import eu.wohlben.qits.entities.dto.TicketDto;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.mapper.WorkEntityMapper;
import eu.wohlben.qits.projects.api.DispatchedWorkspaces;
import eu.wohlben.qits.projects.api.PhaseAdvance;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.refinementhost.EntityResolutions;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import org.jboss.logging.Logger;

/**
 * <b>The one implementation behind every per-archetype entity route</b> (qits-399).
 *
 * <p>{@code EpicController}, {@code TicketController}, {@code FeatureController}, {@code
 * TaskController}, {@code ProjectEpicsController} and {@code ProjectTicketsController} used to each
 * carry their own copy of the same eight steps — resolve the project, bind an agent to it, write
 * through the archetype's service, announce the change, map the row, qualify its id, decorate it
 * with its workspaces. They are thin JAX-RS resources now, kept per archetype only because the wire
 * is: the paths, the request records and the response envelopes ({@code {"epic": …}}, {@code
 * {"ticket": …}}) are what the SPA, the generated client and {@code docs/openapi.yml} name, and they
 * did not move. Every route body is one call into this class, with the archetype handed over as a
 * {@link View}.
 *
 * <p><b>A {@link View} is the archetype as data on this side of the wire</b>: which DTO a row
 * becomes, how that DTO is qualified and decorated, and which SSE topic its writes redraw. The four
 * are built once, in {@link #init}; a route asks for one by method ({@link #epics()} …) rather than
 * reading a field, because this bean is reached through a client proxy and a proxy does not proxy
 * field access.
 *
 * <h2>What stayed per route, deliberately</h2>
 *
 * <p><b>Whether an agent is bound</b> is the route's own declaration, passed in as {@code bound}: an
 * admin-only route ({@code @RolesAllowed("qits:admin")}) never reaches a bound agent, and asking it to
 * resolve the project first anyway would move its refusal order — an epic's transition answers a
 * missing target with a 400 before it looks the epic up. Where a route binds, the project is resolved
 * from the row before the write, so an id naming nothing is the 404 it always was ({@link
 * EntitiesAgentAccess}).
 *
 * <p>The identity is a parameter, never injected here: a route's caller is the route's, and {@code
 * EntityAgentBoundsTest} drives the resources with hand-made identities over this shared bean.
 */
@ApplicationScoped
public class EntityRoutes {

  private static final Logger LOG = Logger.getLogger(EntityRoutes.class);

  @Inject WorkEntityService entities;

  @Inject WorkEntityMapper mapper;

  /**
   * The qualified id {@code <project-slug>-<number>} every answer carries. One batched slug lookup
   * per listing; see {@link QualifiedEntityIds}.
   */
  @Inject QualifiedEntityIds qualifiedIds;

  /**
   * Which live workspaces are on an epic or a ticket — derived per read, and only on a read: a write
   * answers the row it changed, and an edit is not the question "who is working on this".
   */
  @Inject DispatchedWorkspaces dispatchedWorkspaces;

  @Inject ProjectService projectService;

  @Inject RepositoryService repositoryService;

  @Inject ProjectChangePublisher publisher;

  /** The only way a door moves a lifecycle: discards a refinement a resolving move would strand. */
  @Inject EntityResolutions resolutions;

  /**
   * The next phase after a move — the turn the new status starts, delivered when the run standing on
   * the entity's branch was dispatched as a flow, and the release asked for at VERIFIED either way.
   */
  @Inject PhaseAdvance phaseAdvance;

  /** Who is moving, as qits-891's person check says (qits-887) — see {@link EntityMovers}. */
  @Inject EntityMovers movers;

  /**
   * <b>One archetype as the wire sees it.</b>
   *
   * @param archetype the kind of row
   * @param nested whether its DTO names a parent ({@code epicId}, {@code featureId}), which is what
   *     makes a read resolve the membership edge
   * @param topic the SSE topic a write of it redraws — {@code tickets} for a ticket, {@code epics}
   *     for the plan's three kinds
   * @param toDto the row, and its parent for a nested kind, as the DTO
   * @param withQualifiedId the DTO told its qualified id, already rendered
   * @param qualify one DTO qualified by a slug lookup
   * @param qualifyAll a list qualified by one slug lookup
   * @param decorate one DTO told its workspaces — the identity for a kind no workspace names
   * @param decorateAll a list told its workspaces by one lookup
   */
  public record View<D>(
      Archetype archetype,
      boolean nested,
      ProjectChangeHint.Topic topic,
      BiFunction<WorkEntity, String, D> toDto,
      BiFunction<D, String, D> withQualifiedId,
      UnaryOperator<D> qualify,
      UnaryOperator<List<D>> qualifyAll,
      UnaryOperator<D> decorate,
      UnaryOperator<List<D>> decorateAll) {

    D render(WorkEntity row, String parentId) {
      return toDto.apply(row, parentId);
    }

    D render(Nested nested) {
      return toDto.apply(nested.entity(), nested.parentId());
    }
  }

  private View<EpicDto> epics;
  private View<TicketDto> tickets;
  private View<FeatureDto> features;
  private View<TaskDto> tasks;

  @PostConstruct
  void init() {
    epics =
        new View<>(
            Archetype.EPIC,
            false,
            ProjectChangeHint.Topic.EPICS,
            (row, parent) -> mapper.toEpicDto(row),
            EpicDto::withQualifiedId,
            qualifiedIds::qualify,
            qualifiedIds::qualifyEpics,
            dispatchedWorkspaces::decorate,
            dispatchedWorkspaces::decorateEpics);
    tickets =
        new View<>(
            Archetype.TICKET,
            false,
            ProjectChangeHint.Topic.TICKETS,
            (row, parent) -> mapper.toTicketDto(row),
            TicketDto::withQualifiedId,
            qualifiedIds::qualify,
            qualifiedIds::qualifyTickets,
            dispatchedWorkspaces::decorate,
            dispatchedWorkspaces::decorateTickets);
    features =
        new View<>(
            Archetype.FEATURE,
            true,
            ProjectChangeHint.Topic.EPICS,
            mapper::toFeatureDto,
            FeatureDto::withQualifiedId,
            qualifiedIds::qualify,
            qualifiedIds::qualifyFeatures,
            UnaryOperator.identity(),
            UnaryOperator.identity());
    tasks =
        new View<>(
            Archetype.TASK,
            true,
            ProjectChangeHint.Topic.EPICS,
            mapper::toTaskDto,
            TaskDto::withQualifiedId,
            qualifiedIds::qualify,
            qualifiedIds::qualifyTasks,
            UnaryOperator.identity(),
            UnaryOperator.identity());
  }

  public View<EpicDto> epics() {
    return epics;
  }

  public View<TicketDto> tickets() {
    return tickets;
  }

  public View<FeatureDto> features() {
    return features;
  }

  public View<TaskDto> tasks() {
    return tasks;
  }

  // --- reads ----------------------------------------------------------------------------------------

  /** The detail read, and the one place a single row carries its workspaces. */
  public <D> D get(View<D> view, String id) {
    Nested row =
        view.nested()
            ? entities.nested(view.archetype(), id)
            : new Nested(entities.get(view.archetype(), id), null);
    return view.qualify().apply(view.decorate().apply(view.render(row)));
  }

  /**
   * A project's roots of one kind, oldest first, optionally narrowed to a status (a word naming none
   * is a 400). The project lookup is the 404 and also the slug every qualified id is rendered from,
   * so no second lookup is made; the workspaces are asked once about the whole list.
   */
  public <D> List<D> listRoots(View<D> view, String projectId, String status) {
    String slug = projectService.get(projectId).slug;
    List<D> rendered =
        entities.listByProject(view.archetype(), projectId, status).stream()
            .map(
                row ->
                    view.withQualifiedId()
                        .apply(view.render(row, null), QualifiedEntityIds.render(slug, row.number)))
            .toList();
    return view.decorateAll().apply(rendered);
  }

  /**
   * The rows of one kind under a parent, in membership order — the parent read first for its 404,
   * then every row qualified by one slug lookup, never once per row.
   */
  public <D> List<D> listChildren(View<D> view, Archetype parentKind, String parentId) {
    entities.get(parentKind, parentId);
    return view.qualifyAll()
        .apply(
            entities.listChildren(view.archetype(), parentId).stream().map(view::render).toList());
  }

  // --- writes ---------------------------------------------------------------------------------------

  /**
   * A new root in a project. Filing takes {@code qits:agent} wherever it is granted, bound to the
   * agent's own project — the path names the project the binding is against — and the project
   * lookup that follows is the 404 and the slug the answer is qualified with.
   */
  public <D> D createRoot(
      View<D> view, String projectId, EntityWrite write, SecurityIdentity identity) {
    EntitiesAgentAccess.requireProject(identity, projectId);
    String slug = projectService.get(projectId).slug;
    WorkEntity row =
        entities
            .create(view.archetype(), projectId, write, EntitiesPrincipal.changedBy(identity))
            .entity();
    publisher.fire(projectId, view.topic());
    return view.withQualifiedId()
        .apply(view.render(row, null), QualifiedEntityIds.render(slug, row.number));
  }

  /**
   * A new node under a parent, bound to the parent's project. A write naming a repository must name
   * one in that project — a 404 for none, a 400 for one elsewhere — because a task must not bind a
   * repository from an unrelated project; that check crosses into {@code domain}, which is why it is
   * here and not in the entities module.
   */
  public <D> D createChild(
      View<D> view,
      Archetype parentKind,
      String parentId,
      EntityWrite write,
      SecurityIdentity identity) {
    String projectId = entities.get(parentKind, parentId).projectId; // the parent's 404
    EntitiesAgentAccess.requireProject(identity, projectId);
    if (write.repositoryId() != null) {
      Repository repo = repositoryService.get(write.repositoryId()); // 404 if absent
      if (repo.project == null || !projectId.equals(repo.project.id)) {
        throw new BadRequestException(
            "Repository " + write.repositoryId() + " is not in this epic's project");
      }
    }
    Nested created =
        entities.create(view.archetype(), parentId, write, EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, view.topic());
    return view.qualify().apply(view.render(created));
  }

  /**
   * An edit, bound to the row's project, resolved from the row before the write — so an id naming
   * nothing is a 404. It answers the row it changed and leaves the workspaces empty.
   */
  public <D> D update(View<D> view, String id, EntityWrite write, SecurityIdentity identity) {
    String projectId = projectOf(view, id);
    EntitiesAgentAccess.requireProject(identity, projectId);
    Nested updated =
        entities.update(view.archetype(), id, write, EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, view.topic());
    return view.qualify().apply(view.render(updated));
  }

  /**
   * A delete. The project is resolved <em>before</em> it — afterwards there is no row to walk up
   * from — and a {@code bound} route binds against it.
   */
  public void delete(View<?> view, String id, boolean bound, SecurityIdentity identity) {
    String projectId = projectOf(view, id);
    if (bound) {
      EntitiesAgentAccess.requireProject(identity, projectId);
    }
    entities.delete(view.archetype(), id, EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, view.topic());
  }

  /** A lifecycle move's answer: the row in its new status, and a supersede's successor or null. */
  public record Moved<D>(D entity, D successor) {}

  /**
   * A lifecycle move, through {@link EntityResolutions} — a resolving move tears the row's
   * refinement room down before the status lands — and then {@link PhaseAdvance}, after the move is
   * recorded and outside its transaction: a transition that rolled back speaks to nobody, and a
   * throw in the advance must not touch a move that has already been answered for.
   */
  public <D> Moved<D> transition(
      View<D> view, String id, String target, boolean bound, SecurityIdentity identity) {
    WorkEntityService.Transition moved = move(view.archetype(), id, target, bound, identity);
    return new Moved<>(
        view.qualify().apply(view.render(moved.entity(), null)),
        moved.successor() == null
            ? null
            : view.qualify().apply(view.render(moved.successor(), null)));
  }

  /**
   * <b>The lifecycle move itself, for any archetype that has one</b> — what {@link #transition}
   * renders for its per-archetype door, and what {@code POST /entities/{id}/status} answers in the
   * merged shape (qits-548). One body for both, so the generic door is the same move and not a
   * second one: {@link EntityResolutions} first, the archetype's hint ({@link
   * ProjectChangeHint.Topic#of}, which is what every {@link View#topic} is), then {@link
   * PhaseAdvance} outside the move's transaction. {@code PhaseAdvance} returns at once for a
   * campaign, whose own door never calls it, so a campaign moved here is moved exactly as {@code
   * CampaignController.transition} moves it.
   */
  public WorkEntityService.Transition move(
      Archetype archetype, String id, String target, boolean bound, SecurityIdentity identity) {
    return move(archetype, id, target, bound, identity, moverOf(identity));
  }

  /**
   * The caller as a {@link Mover}: a person only when qits-891's check verified one, under the name
   * the proof carries — what the PERSON_APPROVAL gate judges and what the move is audited under.
   */
  public Mover moverOf(SecurityIdentity identity) {
    return movers.of(identity);
  }

  /** {@link #move(Archetype, String, String, boolean, SecurityIdentity)} by a mover already built. */
  public WorkEntityService.Transition move(
      Archetype archetype,
      String id,
      String target,
      boolean bound,
      SecurityIdentity identity,
      Mover mover) {
    if (bound) {
      EntitiesAgentAccess.requireProject(identity, entities.get(archetype, id).projectId);
    }
    String changedBy = mover.name();
    WorkEntityService.Transition moved = resolutions.transition(archetype, id, target, mover);
    // A supersede spawns a second row in the same project, so one hint covers both.
    publisher.fire(moved.entity().projectId, ProjectChangeHint.Topic.of(archetype));
    try {
      phaseAdvance.afterTransition(moved.entity(), moved.statusBefore(), changedBy);
    } catch (RuntimeException e) {
      // It says it must not throw; a throw is a bug in it and must not touch a recorded move.
      LOG.warnf(
          e,
          "Could not start the phase %s %s just moved into",
          archetype.name().toLowerCase(Locale.ROOT),
          moved.entity().id);
    }
    return moved;
  }

  /** The project a row of the view's kind belongs to — every row carries it — or that kind's 404. */
  public String projectOf(View<?> view, String id) {
    return entities.get(view.archetype(), id).projectId;
  }
}
