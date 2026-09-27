package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.TicketComment;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ConflictException;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.TicketCommentRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <b>Create, read, update, lifecycle and delete for every archetype of the merged {@link WorkEntity}
 * table, with the archetype as data</b> (qits-399).
 *
 * <p>This was four services — {@code EpicService}, {@code TicketService}, {@code FeatureService},
 * {@code TaskService} — writing one table, each minting the same row in its own words, each carrying
 * its own copy of the 404, the flush, the registry check and the audit call. Nothing about an
 * archetype that used to be a method body is one now. What still differs between the kinds is
 * written down as data, once, in two places:
 *
 * <ul>
 *   <li>{@link Archetypes}, the registry, answers what a kind may carry and must carry, where it
 *       sits in a tree ({@code depth}, {@code mayBeRoot}) and whether it has a lifecycle. The intake
 *       refusals ({@code "impetus is required"}) are read off its {@code requiredAtCreate}, so the
 *       registry and the 400 a caller reads cannot drift apart.
 *   <li>{@link Kind}, below, answers the rest — the words a refusal is phrased in, what a kind hangs
 *       under, which kind's phase freezes its scope, what its dependency is called on the wire and
 *       whether it has a thread. One row per archetype, in {@link #KINDS}.
 * </ul>
 *
 * <h2>The rules, each stated once</h2>
 *
 * <ul>
 *   <li><b>A row of another archetype is a 404</b>, spelled with the kind asked for: four kinds share
 *       one id space, so "no epic with this id" means "no EPIC row with this id".
 *   <li><b>The scope freeze is the owner's phase.</b> A kind whose {@link Kind#freezeOwner} is set
 *       has its scope frozen by that ancestor's status — an epic by its own, a feature by its epic's,
 *       a task by the epic two hops up ({@link EntityLifecycle#requireReported}); the implemented
 *       marker moves only at REFINED ({@link EntityLifecycle#requireRefined}). A ticket names no
 *       owner, so nothing about it freezes. Deleting a <em>root</em> is allowed in every status — it
 *       removes the scope rather than changing it — while deleting a node is a scope change.
 *   <li><b>The audit subtree key is the root</b>: an epic's and a ticket's own id, a feature's and a
 *       task's epic. {@code AuditEntry.epicId} is that key and not a foreign key.
 *   <li><b>Every write is one {@link WritePatience} attempt and none is {@code @Transactional}</b>;
 *       validations that need no row run before the wrap, so a missing title is a 400 on the first
 *       attempt rather than a question retried for fifteen seconds, and ids and slugs are minted
 *       inside it, so a retry is a fresh row rather than a duplicate. Reads go through {@link
 *       ReadPatience}. A row is handed out only after an explicit flush ({@link #settled}), because
 *       {@code @CreationTimestamp}/{@code @UpdateTimestamp} populate at flush.
 *   <li><b>Subtree walks read a whole level at a time</b> ({@link Subtree}) — {@code childrenOfAll}
 *       plus {@code listByIds} per level, never one query per node. That is the performance mistake a
 *       merged table makes easy, and the stamp, the supersede and the cascade delete are the deepest
 *       reads the module has.
 *   <li><b>Every removed row gets its own DELETE audit row</b> — descendants and a ticket's comments
 *       are removed in-service rather than by the database cascade, because the audit log is the git
 *       replacement and a row that vanished silently never happened.
 * </ul>
 *
 * <p>The legacy {@code epic}, {@code ticket} and {@code feature} tables are written by nothing and
 * pointed at by nothing — the frozen snapshot of what V10 found, which the verification door compares
 * against; {@code docs/unified-entity-model.md} carries that argument.
 */
@ApplicationScoped
public class WorkEntityService {

  @Inject WorkEntityRepository entities;

  @Inject EntityMembershipRepository memberships;

  @Inject TicketCommentRepository comments;

  /** The nesting rule's view of the two tables — see {@link #attach}. */
  @Inject StoredEntityFacts facts;

  @Inject AuditService auditService;

  @Inject ReadPatience patience;

  @Inject WritePatience writes;

  /** The per-project numeric id every created row takes; see {@link EntityNumbers}. */
  @Inject EntityNumbers numbers;

  /**
   * <b>The supersede operation's name, which is not a status.</b> {@code SUPERSEDED} was an epic
   * status until qits-392 folded it into {@link EntityStatus#DROPPED}: it said two things at once —
   * this work will not be done, and here is what replaced it — and only the first is a status. The
   * second is the {@code superseded_by_entity_id} column. The operation behind the word, the deep
   * copy of the subtree into a successor draft, survives, so a transition asking for {@code
   * SUPERSEDED} is accepted of any kind that may carry {@link EntityProperty#SUPERSEDED_BY} — an
   * epic, today: it is judged as a move to DROPPED, lands the row DROPPED, and points it at the
   * successor it spawned. Nothing ever stores the word; {@code ck_entity_status} would refuse it.
   *
   * <p>One refusal is the operation's own rather than the graph's: <b>a REPORTED row cannot be
   * superseded</b>. A draft has no frozen scope to discard — it is edited, or dropped — and a
   * successor copied from a draft would be a second draft saying the same thing.
   */
  public static final String SUPERSEDE = "SUPERSEDED";

  /**
   * The outcome of a {@link #transition}: the row in its new status, plus the successor draft when
   * the move was a {@linkplain #SUPERSEDE supersede} (null otherwise), and the status the row moved
   * <em>from</em> — captured inside the write, because afterwards the row only knows where it went.
   */
  public record Transition(WorkEntity entity, WorkEntity successor, String statusBefore) {}

  /**
   * What the write hands out of {@link WritePatience}: the outcome, plus the announcement batch read
   * in the same transaction — the edges are only readable in there, and the announcement itself is
   * made outside it.
   */
  private record Moved(Transition transition, List<TransitionedEntity> batch) {}

  /**
   * The announcement seam, optional like every port here: with no implementation a move still moves
   * and announces nothing. {@link EntityTransitionService} announces through the same port.
   */
  @Inject Instance<TransitionAnnouncer> announcer;

  /**
   * What a {@link #transition} to {@code target} would be: the row as it stands, the status it would
   * move to, and whether that status {@linkplain EntityLifecycle#resolves resolves} it.
   *
   * <p>It exists so a caller can act <em>before</em> the move on something the row is still holding
   * — the refinement container, in the assembling service — and still refuse an illegal move first.
   * Every rejection is the transition's own, thrown here rather than one step later: a 409'd move
   * that had already torn a refinement down would be the worse half of the bug this answers. The
   * move is re-checked inside {@link #transition}, which is where it is decided; this is a preview
   * and never a reservation.
   */
  public record PlannedTransition(WorkEntity entity, EntityStatus target, boolean resolving) {}

  // --- the per-archetype data ---------------------------------------------------------------------

  /**
   * <b>What differs between two archetypes that the registry does not already say</b>, as data.
   *
   * @param archetype the kind this row describes
   * @param noun how a refusal names a row of it — {@code "Epic not found: …"}, {@code "Unknown epic
   *     status: …"}
   * @param parent the kind a row of it hangs under, or null for a root
   * @param freezeOwner the kind whose status freezes this one's scope — itself for an epic, the epic
   *     above for a feature and a task, nobody for a ticket
   * @param dependencyField what the sibling dependency is called on the wire, for its refusals; null
   *     for a kind with none
   * @param thread whether rows of it carry a comment thread, removed with them
   */
  record Kind(
      Archetype archetype,
      String noun,
      Archetype parent,
      Archetype freezeOwner,
      String dependencyField,
      boolean thread) {

    boolean isRoot() {
      return parent == null;
    }

    /** {@code "epic"} — the lower-case noun a sentence continues with. */
    String word() {
      return noun.toLowerCase(Locale.ROOT);
    }

    /** The patience label: {@code "epic create"}. Read only by the logs. */
    String label(String operation) {
      return word() + " " + operation;
    }

    /** How many membership hops up the tree's root is — the registry's depth. */
    int depth() {
      return Archetypes.depth(archetype);
    }

    boolean hasLifecycle() {
      return !Archetypes.legalStatuses(archetype).isEmpty();
    }

    boolean supersedable() {
      return Archetypes.spec(archetype).permits(EntityProperty.SUPERSEDED_BY);
    }
  }

  private static final Map<Archetype, Kind> KINDS = declare();

  private static Map<Archetype, Kind> declare() {
    Map<Archetype, Kind> kinds = new EnumMap<>(Archetype.class);
    kinds.put(Archetype.EPIC, new Kind(Archetype.EPIC, "Epic", null, Archetype.EPIC, null, false));
    kinds.put(Archetype.TICKET, new Kind(Archetype.TICKET, "Ticket", null, null, null, true));
    // A root with no freeze owner — its title and description stay editable at every status, as a
    // ticket's do — no sibling dependency and no thread. It is the fifth kind (qits-411).
    kinds.put(
        Archetype.CAMPAIGN, new Kind(Archetype.CAMPAIGN, "Campaign", null, null, null, false));
    kinds.put(
        Archetype.FEATURE,
        new Kind(
            Archetype.FEATURE,
            "Feature",
            Archetype.EPIC,
            Archetype.EPIC,
            "dependsOnFeatureId",
            false));
    kinds.put(
        Archetype.TASK,
        new Kind(
            Archetype.TASK, "Task", Archetype.FEATURE, Archetype.EPIC, "dependsOnTaskId", false));
    for (Archetype archetype : Archetype.values()) {
      if (!kinds.containsKey(archetype)) {
        throw new IllegalStateException("No WorkEntityService.Kind declared for " + archetype);
      }
    }
    return Map.copyOf(kinds);
  }

  private static Kind kind(Archetype archetype) {
    return KINDS.get(archetype);
  }

  /**
   * <b>The order intake refuses in</b>, over the registry's {@code requiredAtCreate}. The registry
   * says <em>which</em> properties a birth demands; this says which one a caller missing several is
   * told about first, and how the property is spelled on the wire. {@link EntityProperty#STATUS} is
   * absent on purpose: the writer mints it.
   */
  private static final Map<EntityProperty, String> INTAKE_ORDER = intakeOrder();

  private static Map<EntityProperty, String> intakeOrder() {
    Map<EntityProperty, String> order = new LinkedHashMap<>();
    order.put(EntityProperty.TITLE, "title");
    order.put(EntityProperty.IMPETUS, "impetus");
    order.put(EntityProperty.TICKET_TYPE, "type");
    order.put(EntityProperty.REPOSITORY_ID, "repositoryId");
    return order;
  }

  // --- reads ----------------------------------------------------------------------------------------

  /** The row of {@code archetype} this id names, or a 404 — and a row of another kind is a 404 too. */
  public WorkEntity get(Archetype archetype, String id) {
    return lookup(archetype, id);
  }

  /** The row and, beside it, the parent its membership edge names (null for a root). */
  public Nested nested(Archetype archetype, String id) {
    WorkEntity row = lookup(archetype, id);
    return new Nested(row, parentOf(id));
  }

  public List<WorkEntity> listByProject(Archetype archetype, String projectId) {
    return listByProject(archetype, projectId, null);
  }

  /**
   * A project's roots of one archetype, oldest first, optionally narrowed to one status. {@code
   * status} is the status name; a value naming none is a 400 rather than an empty list, so a typo in
   * the filter is visible. It is parsed before the wrap, so that 400 lands on the first attempt.
   *
   * <p>One query either way, with the rows projected in memory — nothing is resolved per row. The
   * read is held through a postgres cutover ({@link ReadPatience}): this is a board's top level, and
   * a severed connection would draw a project with nothing in it. Neither caller — the controllers
   * and the MCP tools — opens a transaction, which is what makes the wrap legal here.
   */
  public List<WorkEntity> listByProject(Archetype archetype, String projectId, String status) {
    Kind kind = kind(archetype);
    if (status == null || status.isBlank()) {
      return patience.hold(
          kind.label("list"), () -> entities.listByProjectAndArchetype(projectId, archetype));
    }
    EntityStatus filter =
        EntityLifecycle.parse(status)
            .orElseThrow(
                () -> new BadRequestException("Unknown " + kind.word() + " status: " + status));
    return patience.hold(
        kind.label("list by status"),
        () -> entities.listByProjectArchetypeAndStatus(projectId, archetype, filter.name()));
  }

  /**
   * The rows of {@code archetype} under {@code parentId}, in membership position order. <b>Two
   * queries whatever the size of the parent</b>: the edges come back in order and the rows
   * oldest-first, so the rows are indexed and re-emitted in the edges' order.
   */
  public List<Nested> listChildren(Archetype archetype, String parentId) {
    return patience.hold(
        kind(archetype).label("list"),
        () -> {
          List<EntityMembership> edges = memberships.childrenOf(parentId);
          Map<String, WorkEntity> rows = byId(entities.listByIds(childIds(edges)));
          List<Nested> children = new ArrayList<>();
          for (EntityMembership edge : edges) {
            WorkEntity row = rows.get(edge.childId);
            if (row != null && row.archetype == archetype) {
              children.add(new Nested(row, parentId));
            }
          }
          return List.copyOf(children);
        });
  }

  // --- create ---------------------------------------------------------------------------------------

  /**
   * A new row of {@code archetype}. {@code under} is the <b>project id</b> for a root and the
   * <b>parent's id</b> for a node — the one argument that says where the row goes, whichever kind it
   * is.
   *
   * <p>Before the wrap: a root's project id, then every property the registry's {@code
   * requiredAtCreate} demands, in {@link #INTAKE_ORDER}, then the type word. Inside it: the parent
   * (a 404 in the parent's own noun), the owner's scope freeze, the dependency's scope, the row, the
   * registry at {@link Demand#AT_CREATE}, the membership edge and the nesting rule, the audit row.
   *
   * <p>A lifecycle kind starts {@link EntityStatus#REPORTED}; a kind that may carry {@link
   * EntityProperty#CREATED_BY} has {@code changedBy} stamped there — the reporter, read from the
   * request identity by the surfaces above and never from a body.
   */
  public Nested create(Archetype archetype, String under, EntityWrite write, String changedBy) {
    Kind kind = kind(archetype);
    if (kind.isRoot()) {
      Validations.requireText(under, "projectId");
    }
    requireIntake(archetype, write);
    TicketType type = parseType(write.type());
    return writes.hold(
        kind.label("create"),
        () -> {
          String projectId = under;
          String rootId = null;
          if (!kind.isRoot()) {
            WorkEntity parent = lookup(kind.parent(), under);
            WorkEntity owner = ownerAbove(kind, parent);
            if (owner != null) {
              EntityLifecycle.requireReported(owner);
            }
            if (write.dependsOn() != null) {
              requireDependencyUnder(kind, write.dependsOn(), under);
            }
            projectId = parent.projectId;
            rootId = owner != null ? owner.id : ancestor(parent.id, kind(parent.archetype).depth());
          }
          WorkEntity row = insert(kind, projectId, kind.isRoot() ? projectId : under, write, type);
          row.createdBy =
              Archetypes.spec(archetype).permits(EntityProperty.CREATED_BY) ? changedBy : null;
          requireArchetypeValid(row, Demand.AT_CREATE);
          entities.persist(row);
          if (!kind.isRoot()) {
            attach(under, row);
          }
          WorkEntity created = settled(row);
          audit(created, rootId == null ? created.id : rootId, AuditOperation.CREATE, changedBy);
          return new Nested(created, kind.isRoot() ? null : under);
        });
  }

  /**
   * <b>A new campaign in {@code projectId}</b> — the create behind {@code POST
   * /projects/{projectId}/campaigns}, and the one door a campaign is born through (the MIMO door
   * refuses to make one; see {@link EntityTransitionService}).
   *
   * <p>It is {@link #create} at {@link Archetype#CAMPAIGN} and nothing else, so it inherits every
   * rule an epic's create has: the slug minted once from the title by {@link Slugs#slugify} in the
   * project's root slug scope (shared with epics and tickets), the number from {@code
   * entity_number_sequence}, the status minted {@link EntityStatus#REPORTED} by the writer, the
   * registry judged at {@link Demand#AT_CREATE}, and one audit row. Named rather than left to a
   * caller's {@code create(CAMPAIGN, …)} so the surfaces that make one say what they make.
   */
  public WorkEntity createCampaign(
      String projectId, String title, String description, String changedBy) {
    return create(
            Archetype.CAMPAIGN, projectId, EntityWrite.campaign(title, description), changedBy)
        .entity();
  }

  /**
   * A fresh row of {@code kind}, not yet persisted, unaudited. The slug is <b>minted once, here, and
   * never re-derived on update</b>: it is a branch path segment and a stable address, and renaming a
   * row must not orphan what was cut from it. Its scope is the project for a root — shared by epics
   * and tickets, the narrowing {@code docs/unified-entity-model.md} states — and the parent for a
   * node, which is what {@code uq_entity_slug_scope_slug} makes of the four old per-table rules.
   *
   * <p>The number is allocated inside the write's transaction but committed outside it, which is
   * what makes a create that rolls back leave a gap rather than hand the number back — see {@link
   * EntityNumbers}. It is per PROJECT, never per parent: a node is in the same run of integers as its
   * root.
   */
  private WorkEntity insert(
      Kind kind, String projectId, String slugScope, EntityWrite write, TicketType type) {
    WorkEntity row = new WorkEntity();
    row.id = UUID.randomUUID().toString();
    row.archetype = kind.archetype();
    row.projectId = projectId;
    row.number = numbers.next(projectId);
    row.title = write.title();
    row.slugScope = slugScope;
    row.slug =
        Slugs.unique(
            Slugs.slugify(write.title(), row.id, kind.word() + "-"),
            entities.slugsInScope(slugScope));
    row.description = write.description();
    row.status = kind.hasLifecycle() ? EntityStatus.REPORTED.name() : null;
    row.ticketType = type;
    row.impetus = write.impetus();
    row.assignee = blankToNull(write.assignee());
    row.repositoryId = write.repositoryId();
    row.dependsOnEntityId = write.dependsOn();
    return row;
  }

  // --- update ---------------------------------------------------------------------------------------

  /**
   * An edit of a row of {@code archetype}: every value {@code write} supplies is set, every {@code
   * clear*} flag empties its property, and everything else is left as it is — see {@link
   * EntityWrite}. The status is never written here: {@link #transition} is the only thing that moves
   * it.
   *
   * <p><b>The freeze is applied per field</b>, against the owner's phase: whatever this call touches
   * must be allowed by it, so an edit supplying both scope and the marker always fails — no status
   * allows both. A kind with no owner (a ticket) is never frozen: a DONE ticket is still editable.
   */
  public Nested update(Archetype archetype, String id, EntityWrite write, String changedBy) {
    Kind kind = kind(archetype);
    return writes.hold(
        kind.label("update"),
        () -> {
          WorkEntity row = lookup(archetype, id);
          String parentId = kind.isRoot() ? null : parentOf(id);
          WorkEntity owner = owner(kind, row, parentId);
          if (owner != null) {
            if (write.touchesScope()) {
              EntityLifecycle.requireReported(owner);
            }
            if (write.touchesMarker()) {
              EntityLifecycle.requireRefined(owner);
            }
          }
          if (write.title() != null) {
            Validations.requireText(write.title(), "title");
            row.title = write.title();
          }
          if (write.clearImpetus()) {
            row.impetus = null;
          } else if (write.impetus() != null) {
            row.impetus = write.impetus();
          }
          if (write.clearDescription()) {
            row.description = null;
          } else if (write.description() != null) {
            row.description = write.description();
          }
          TicketType type = parseType(write.type());
          if (type != null) {
            row.ticketType = type;
          }
          if (write.clearAssignee()) {
            row.assignee = null;
          } else if (write.assignee() != null) {
            row.assignee = blankToNull(write.assignee());
          }
          if (write.repositoryId() != null) {
            row.repositoryId = write.repositoryId();
          }
          if (write.clearDependsOn()) {
            row.dependsOnEntityId = null;
          } else if (write.dependsOn() != null) {
            if (write.dependsOn().equals(id)) {
              throw new BadRequestException("A " + kind.word() + " cannot depend on itself");
            }
            requireDependencyUnder(kind, write.dependsOn(), parentId);
            requireNoCycle(kind, id, write.dependsOn());
            row.dependsOnEntityId = write.dependsOn();
          }
          if (write.clearImplementedAt()) {
            row.implementedAt = null;
          } else if (write.implementedAt() != null) {
            row.implementedAt = write.implementedAt();
          }
          requireArchetypeValid(row, Demand.ON_UPDATE);
          WorkEntity updated = settled(row);
          audit(updated, rootOf(kind, updated, owner), AuditOperation.UPDATE, changedBy);
          return new Nested(updated, parentId);
        });
  }

  // --- lifecycle ------------------------------------------------------------------------------------

  /** The preview of a move — see {@link PlannedTransition}. */
  public PlannedTransition planTransition(Archetype archetype, String id, String target) {
    Kind kind = kind(archetype);
    Validations.requireText(target, "target");
    WorkEntity row = lookup(archetype, id);
    EntityStatus to = targetStatus(kind, row, target);
    requireSupersedable(kind, row, target);
    return new PlannedTransition(row, to, EntityLifecycle.resolves(to));
  }

  /**
   * Moves a row to {@code target} (the status name), rejecting a move the lifecycle does not allow
   * with a 409 — which moves those are is {@link EntityLifecycle}'s to say. A target naming no status
   * is a 409 too: the caller asked for a phase that does not exist, the same kind of answer as
   * asking for one that is not reachable. An absent target is a 400, a malformed request rather than
   * a refused move.
   *
   * <p>Three things ride on a move, and each follows from the row rather than from its kind:
   *
   * <ul>
   *   <li><b>{@link #SUPERSEDE}</b> lands the row DROPPED, spawns the successor draft ({@link
   *       #supersede}) and points the old row at it.
   *   <li><b>Moving to {@link EntityStatus#IMPLEMENTED} stamps every descendant still
   *       unimplemented</b> ({@link #stampImplemented}) — declaring the work done is declaring its
   *       scope done, and one transaction keeps the stored status and the derived reading from ever
   *       disagreeing. A row with no descendants stamps nothing.
   *   <li><b>Every move clears {@code blocked}</b>, which is what makes the flag temporary rather
   *       than a second lifecycle: a block says the phase the <em>current</em> status starts cannot
   *       finish, and the moment the status moves that phase is over and the next one has not been
   *       tried. It is cleared on a backward move for the same reason. {@link #setBlocked} is the
   *       only thing that sets it; a kind that is never blocked writes false over false.
   * </ul>
   *
   * <p>Held through a cutover ({@link WritePatience}), the whole move in one transaction —
   * supersede's successor tree included, so a retry never leaves half a copy behind.
   *
   * <p><b>Announced once, after the hold returns</b> — never inside it, because the body re-runs on
   * a retry. The batch is the moved row, plus the successor draft when there is one (created by this
   * move, so it left no status: {@code statusBefore} null, status REPORTED). A move is a batch of
   * one; see {@link TransitionAnnouncer}.
   */
  public Transition transition(Archetype archetype, String id, String target, String changedBy) {
    Kind kind = kind(archetype);
    Validations.requireText(target, "target");
    Moved moved =
        writes.hold(kind.label("transition"), () -> move(kind, archetype, id, target, changedBy));
    announce(moved.batch());
    return moved.transition();
  }

  /** The body of {@link #transition}: database-only, so a retry may run it again. */
  private Moved move(Kind kind, Archetype archetype, String id, String target, String changedBy) {
    WorkEntity row = lookup(archetype, id);
    String statusBefore = row.status;
    EntityStatus to = targetStatus(kind, row, target);
    requireSupersedable(kind, row, target);

    WorkEntity successor =
        kind.supersedable() && SUPERSEDE.equals(target) ? supersede(kind, row, changedBy) : null;
    if (to == EntityStatus.IMPLEMENTED) {
      stampImplemented(row, changedBy);
    }
    row.status = to.name();
    row.blocked = false;
    if (successor != null) {
      row.supersededByEntityId = successor.id;
    }
    requireArchetypeValid(row, Demand.ON_UPDATE);
    WorkEntity moved = settled(row);
    audit(moved, moved.id, AuditOperation.UPDATE, changedBy);

    List<TransitionedEntity> batch = new ArrayList<>(2);
    batch.add(TransitionedEntity.of(moved, edgeOf(moved.id), statusBefore, changedBy));
    if (successor != null) {
      batch.add(TransitionedEntity.of(successor, edgeOf(successor.id), null, changedBy));
    }
    return new Moved(new Transition(moved, successor, statusBefore), List.copyOf(batch));
  }

  /** A row's edge, or null for a root — the statement {@code EntityFact.parentId} makes. */
  private EntityMembership edgeOf(String id) {
    return memberships.membershipOf(id).orElse(null);
  }

  /** One call per move, never inside the write — see {@link TransitionAnnouncer}. */
  private void announce(List<TransitionedEntity> batch) {
    if (announcer.isUnsatisfied()) {
      return;
    }
    announcer.get().onEntitiesTransitioned(batch, Instant.now());
  }

  /**
   * <b>Sets or clears {@code blocked}, and it is a door of its own for the reason {@link
   * #transition} is.</b> {@link #update} cannot touch the status, because a statement about where the
   * work stands is not the same act as editing the text that describes it; the flag gets that same
   * separation — an edit that could also block would let a retitle assert that somebody is stuck.
   *
   * <p><b>Legal at every status as far as this module is concerned, and that is not an
   * omission.</b> Blocking is meaningful only where a phase runs, and <em>phase</em> is the service
   * layer's concept ({@code projects/api/PhasePrompts}); this module has no idea a phase exists and
   * depends on {@code domain} nowhere, so the doors refuse a VERIFIED, DONE or DROPPED row with a 409
   * and a second list of the phased statuses written here would be the drift {@code
   * EntityLifecycle.LEGAL_TARGETS} exists to prevent.
   *
   * <p>The reason is not stored on the row and this method does not take one: a blocker is a remark
   * with an author and a time, which is what the thread already is. Idempotent: blocking a blocked
   * row records the UPDATE again, because the door's comment is the point of the call.
   */
  public WorkEntity setBlocked(Archetype archetype, String id, boolean blocked, String changedBy) {
    Kind kind = kind(archetype);
    return writes.hold(
        kind.label("blocked"),
        () -> {
          WorkEntity row = lookup(archetype, id);
          row.blocked = blocked;
          requireArchetypeValid(row, Demand.ON_UPDATE);
          WorkEntity written = settled(row);
          audit(written, written.id, AuditOperation.UPDATE, changedBy);
          return written;
        });
  }

  /**
   * The status a transition {@code target} lands on: the word itself, or {@link EntityStatus#DROPPED}
   * for the {@linkplain #SUPERSEDE supersede} operation of a kind that has it — judged against the
   * lifecycle graph from the row's status. A word naming neither is a 409, and so is asking it of a
   * kind with no lifecycle.
   */
  private static EntityStatus targetStatus(Kind kind, WorkEntity row, String target) {
    if (!kind.hasLifecycle()) {
      throw new ConflictException(
          "A " + kind.word() + " has no lifecycle of its own: " + row.id + " cannot be moved.");
    }
    EntityStatus to =
        kind.supersedable() && SUPERSEDE.equals(target)
            ? EntityStatus.DROPPED
            : EntityLifecycle.parse(target)
                .orElseThrow(
                    () -> new ConflictException("Unknown " + kind.word() + " status: " + target));
    EntityLifecycle.requireTransition(kind.archetype(), EntityStatus.valueOf(row.status), to);
    return to;
  }

  /** The supersede operation's own refusal — see {@link #SUPERSEDE}. */
  private static void requireSupersedable(Kind kind, WorkEntity row, String target) {
    if (kind.supersedable()
        && SUPERSEDE.equals(target)
        && EntityStatus.REPORTED.name().equals(row.status)) {
      throw new ConflictException(
          kind.noun()
              + " "
              + row.id
              + " is REPORTED, a draft with no frozen scope to supersede — edit it, or drop it.");
    }
  }

  /**
   * The other half of moving to {@link EntityStatus#IMPLEMENTED}: every descendant still
   * unimplemented is stamped now, each with its own audit row. Markers already set keep their
   * timestamps — a feature implemented in June stays implemented in June; the stamp records when the
   * declaration covered the rest, not a rewrite of history.
   *
   * <p>Children before their parent, depth first in membership order — a feature's tasks, then the
   * feature — over the subtree read a level at a time.
   */
  private void stampImplemented(WorkEntity root, String changedBy) {
    Instant now = Instant.now();
    Subtree subtree = subtreeOf(root.id);
    for (WorkEntity node : subtree.postOrder(root.id)) {
      if (node.implementedAt == null) {
        node.implementedAt = now;
        audit(node, root.id, AuditOperation.UPDATE, changedBy);
      }
    }
  }

  /**
   * The successor draft of a superseded row: a new {@link EntityStatus#REPORTED} row of the same kind
   * carrying the old title and description and the whole tree beneath it, so refinement restarts
   * from what was discarded rather than from a blank page.
   *
   * <p>Copies get fresh ids and keep their slugs — each node's scope is its new parent, so the name
   * is free again. The <em>root's</em> slug is the exception: its scope is the project, where the old
   * row still holds it, so the successor mints the next free one exactly as a hand-created row would.
   * The implemented markers reset (nothing is implemented in a draft) and {@code dependsOn} is
   * remapped to the new ids — in a second pass, because a dependency may point at a sibling copied
   * after it. The memberships are copied with the rows, in the source's order and re-numbered dense
   * from zero, so the successor's plan is drawn in the order the discarded one was.
   *
   * <p><b>ONE number allocation for the whole copied tree</b> rather than one per row, taken in the
   * order the copies are made — depth first, each node before its children — and <b>a copy is a new
   * entity with a new number</b>: the source keeps its own, and two rows sharing a number would make
   * the qualified form ambiguous in exactly the project it is scoped by. The audit rows are written a
   * level at a time — the successor, then its children, then theirs.
   */
  private WorkEntity supersede(Kind kind, WorkEntity old, String changedBy) {
    WorkEntity successorRow =
        insert(
            kind,
            old.projectId,
            old.projectId,
            EntityWrite.epic(old.title, old.description),
            old.ticketType);
    requireArchetypeValid(successorRow, Demand.AT_CREATE);
    entities.persist(successorRow);

    Subtree source = subtreeOf(old.id);
    List<WorkEntity> preOrder = source.preOrder(old.id);
    long nextNumber = preOrder.isEmpty() ? 0 : numbers.allocate(old.projectId, preOrder.size());

    Map<String, WorkEntity> copies = new LinkedHashMap<>();
    Map<Integer, List<WorkEntity>> byLevel = new LinkedHashMap<>();
    Map<String, String> copiedParent = new LinkedHashMap<>();
    Map<String, Integer> nextPosition = new LinkedHashMap<>();
    copiedParent.put(old.id, successorRow.id);
    for (WorkEntity node : preOrder) {
      String parent = copiedParent.get(source.parentOf(node.id));
      // Re-derived rather than copied, so the successor's positions are dense and zero-based
      // whatever the source's were; the order is the source's, since preOrder walks it in order.
      int position = nextPosition.merge(parent, 1, Integer::sum) - 1;
      WorkEntity copy = copyUnder(node, parent, position, nextNumber++);
      copies.put(node.id, copy);
      copiedParent.put(node.id, copy.id);
      byLevel.computeIfAbsent(source.levelOf(node.id), level -> new ArrayList<>()).add(copy);
    }
    // Second pass: every copy exists now, so a pointer can be remapped whichever way it points.
    for (WorkEntity node : preOrder) {
      WorkEntity target = copies.get(node.dependsOnEntityId);
      copies.get(node.id).dependsOnEntityId = (target == null) ? null : target.id;
    }

    // Audited after the remap so each snapshot is the finished row; settled() flushes the batch.
    WorkEntity successor = settled(successorRow);
    audit(successor, successor.id, AuditOperation.CREATE, changedBy);
    byLevel.keySet().stream()
        .sorted()
        .forEach(
            level -> {
              for (WorkEntity copy : byLevel.get(level)) {
                audit(copy, successor.id, AuditOperation.CREATE, changedBy);
              }
            });
    return successor;
  }

  /**
   * A fresh row carrying {@code source}'s content under {@code parentId} at {@code position}, and
   * the edge that puts it there. The slug is kept, the implemented marker resets and {@code
   * dependsOn} is left for the second pass. The edge's id is the child's, V10's rule.
   */
  private WorkEntity copyUnder(WorkEntity source, String parentId, int position, long number) {
    WorkEntity copy = new WorkEntity();
    copy.id = UUID.randomUUID().toString();
    copy.archetype = source.archetype;
    copy.projectId = source.projectId;
    copy.number = number;
    copy.title = source.title;
    copy.slug = source.slug;
    copy.slugScope = parentId;
    copy.description = source.description;
    copy.repositoryId = source.repositoryId;
    entities.persist(copy);

    EntityMembership edge = new EntityMembership();
    edge.id = copy.id;
    edge.parentId = parentId;
    edge.childId = copy.id;
    edge.position = position;
    memberships.persist(edge);
    return copy;
  }

  // --- delete ---------------------------------------------------------------------------------------

  /**
   * Removes a row of {@code archetype} and everything that hangs off it, in one transaction held
   * through a cutover ({@link WritePatience}), so a retry never leaves half a cascade behind:
   *
   * <ol>
   *   <li>siblings of the same kind that depend on it lose the pointer, each audited — in-service
   *       rather than the FK's SET NULL, which would leave no trace;
   *   <li>its descendants go, each with a DELETE audit row, oldest first — the subtree read a level
   *       at a time, their edges with them by the FK's cascade;
   *   <li>a kind with a thread loses its comments, each with a DELETE audit row;
   *   <li>its own edge goes and the gap it leaves is closed, so the siblings stay dense and
   *       zero-based;
   *   <li>the row goes, audited.
   * </ol>
   *
   * <p>A node's delete is a scope change and obeys its owner's freeze; a root's is allowed in every
   * status — it removes the scope rather than editing it, and the audit log outlives it.
   */
  public void delete(Archetype archetype, String id, String changedBy) {
    Kind kind = kind(archetype);
    writes.run(
        kind.label("delete"),
        () -> {
          WorkEntity row = lookup(archetype, id);
          EntityMembership edge = kind.isRoot() ? null : memberships.membershipOf(id).orElse(null);
          String parentId = edge == null ? null : edge.parentId;
          WorkEntity owner = kind.isRoot() ? null : owner(kind, row, parentId);
          if (owner != null) {
            EntityLifecycle.requireReported(owner);
          }
          String rootId = owner == null ? id : owner.id;

          for (WorkEntity dependent : entities.listDependents(id)) {
            if (dependent.archetype != archetype) {
              continue;
            }
            dependent.dependsOnEntityId = null;
            audit(dependent, rootId, AuditOperation.UPDATE, changedBy);
          }

          Subtree subtree = subtreeOf(id);
          for (WorkEntity descendant : subtree.oldestFirst()) {
            entities.delete(descendant);
            audit(descendant, rootId, AuditOperation.DELETE, changedBy);
          }

          if (kind.thread()) {
            for (TicketComment comment : comments.listByTicket(id)) {
              comments.delete(comment);
              auditService.record(
                  AuditEntityType.TICKET_COMMENT,
                  comment.id,
                  id,
                  AuditOperation.DELETE,
                  changedBy,
                  comment);
            }
          }

          if (edge != null) {
            int gone = edge.position;
            memberships.delete(edge);
            memberships.closeGapAfter(parentId, gone);
          }
          entities.delete(row);
          audit(row, rootId, AuditOperation.DELETE, changedBy);
        });
  }

  // --- the tree -------------------------------------------------------------------------------------

  /**
   * <b>Everything below a row, read a level at a time</b> — {@code childrenOfAll} then {@code
   * listByIds} per level, never one query per node. It terminates because the nesting rule makes a
   * membership cycle impossible, and the seen-set means a malformed edge could not make it loop
   * either. The walks above ask it in the three orders they need.
   */
  private Subtree subtreeOf(String rootId) {
    Map<String, List<EntityMembership>> childrenOf = new LinkedHashMap<>();
    Map<String, WorkEntity> rows = new LinkedHashMap<>();
    Map<String, EntityMembership> edgeOf = new LinkedHashMap<>();
    Map<String, Integer> levelOf = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    seen.add(rootId);
    Collection<String> level = List.of(rootId);
    int depth = 0;
    List<WorkEntity> all = new ArrayList<>();
    while (!level.isEmpty()) {
      depth++;
      List<EntityMembership> edges = memberships.childrenOfAll(level);
      List<String> next = new ArrayList<>();
      for (EntityMembership edge : edges) {
        if (seen.add(edge.childId)) {
          childrenOf.computeIfAbsent(edge.parentId, parent -> new ArrayList<>()).add(edge);
          edgeOf.put(edge.childId, edge);
          levelOf.put(edge.childId, depth);
          next.add(edge.childId);
        }
      }
      if (!next.isEmpty()) {
        for (WorkEntity row : entities.listByIds(next)) {
          rows.put(row.id, row);
        }
      }
      level = next;
    }
    return new Subtree(childrenOf, rows, edgeOf, levelOf);
  }

  /** The rows below one root, and the edges between them — see {@link #subtreeOf}. */
  private record Subtree(
      Map<String, List<EntityMembership>> childrenOf,
      Map<String, WorkEntity> rows,
      Map<String, EntityMembership> edgeOf,
      Map<String, Integer> levelOf) {

    /** Each node before its children, siblings in membership order. */
    List<WorkEntity> preOrder(String from) {
      List<WorkEntity> out = new ArrayList<>();
      walk(from, out, true);
      return out;
    }

    /** Each node after its children, siblings in membership order. */
    List<WorkEntity> postOrder(String from) {
      List<WorkEntity> out = new ArrayList<>();
      walk(from, out, false);
      return out;
    }

    private void walk(String id, List<WorkEntity> out, boolean pre) {
      for (EntityMembership edge : childrenOf.getOrDefault(id, List.of())) {
        WorkEntity row = rows.get(edge.childId);
        if (row == null) {
          continue;
        }
        if (pre) {
          out.add(row);
        }
        walk(row.id, out, pre);
        if (!pre) {
          out.add(row);
        }
      }
    }

    /** Every row below the root, oldest first with the id as the tie-break — the table's order. */
    List<WorkEntity> oldestFirst() {
      return rows.values().stream()
          .sorted(
              java.util.Comparator.comparing(
                      (WorkEntity row) -> row.createdAt,
                      java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                  .thenComparing(row -> row.id))
          .toList();
    }

    String parentOf(String id) {
      return edgeOf.get(id).parentId;
    }

    int levelOf(String id) {
      return levelOf.get(id);
    }
  }

  // --- plumbing -------------------------------------------------------------------------------------

  /**
   * The row this id names if it is of {@code archetype}, or a 404 in that kind's noun. The four kinds
   * share one table and one id space, so "no feature with this id" has to mean "no FEATURE row with
   * this id" rather than "no row at all".
   */
  private WorkEntity lookup(Archetype archetype, String id) {
    WorkEntity row = id == null ? null : entities.findById(id);
    if (row == null || row.archetype != archetype) {
      throw new NotFoundException(kind(archetype).noun() + " not found: " + id);
    }
    return row;
  }

  /** What this row hangs under, or null for one that hangs under nothing. */
  private String parentOf(String childId) {
    return memberships.membershipOf(childId).map(edge -> edge.parentId).orElse(null);
  }

  /**
   * The row whose phase decides what may be written to a row of {@code kind}, or null for a kind
   * nothing freezes: the row itself when it is its own owner (an epic), else the root its parent
   * hangs under — {@code depth - 1} membership hops up from the parent, then read as the owner's
   * kind, so a missing one is that kind's 404.
   */
  private WorkEntity owner(Kind kind, WorkEntity row, String parentId) {
    if (kind.freezeOwner() == null) {
      return null;
    }
    if (kind.freezeOwner() == kind.archetype()) {
      return row;
    }
    return lookup(kind.freezeOwner(), ancestor(parentId, kind.depth() - 1));
  }

  /** {@link #owner} from an already-resolved parent, as a create has it. */
  private WorkEntity ownerAbove(Kind kind, WorkEntity parent) {
    if (kind.freezeOwner() == null) {
      return null;
    }
    if (parent.archetype == kind.freezeOwner()) {
      return parent;
    }
    return lookup(kind.freezeOwner(), ancestor(parent.id, kind(parent.archetype).depth()));
  }

  /** The id {@code hops} membership edges above {@code id}; null once the walk leaves the tree. */
  private String ancestor(String id, int hops) {
    String cursor = id;
    for (int i = 0; i < hops && cursor != null; i++) {
      cursor = parentOf(cursor);
    }
    return cursor;
  }

  /** The audit subtree key of a row: its owner's id for a node, its own for a root. */
  private static String rootOf(Kind kind, WorkEntity row, WorkEntity owner) {
    return kind.isRoot() || owner == null ? row.id : owner.id;
  }

  /**
   * The edge that makes {@code row} part of {@code parentId}, appended at the end of the parent's
   * children, then judged by {@link Nesting} over the post-state it produces. <b>The edge's id is the
   * child's</b>, V10's rule: an edge's identity is the end of it that can only be in one. {@code
   * dependsOn} is never handed to the nesting rule — a dependency is a sibling ordering edge, and
   * containment is what {@code Nesting} is about.
   */
  private void attach(String parentId, WorkEntity row) {
    EntityMembership edge = new EntityMembership();
    edge.id = row.id;
    edge.parentId = parentId;
    edge.childId = row.id;
    edge.position = memberships.maxPosition(parentId) + 1;
    memberships.persist(edge);
    List<NestingViolation> violations =
        Nesting.check(List.of(new EntityFact(row.id, row.archetype, parentId)), facts);
    if (!violations.isEmpty()) {
      throw new BadRequestException(
          violations.stream().map(NestingViolation::message).collect(Collectors.joining("; ")));
    }
  }

  /** A dependency must be a sibling: a row of the same kind under the same parent. */
  private void requireDependencyUnder(Kind kind, String dependencyId, String parentId) {
    WorkEntity dependency = entities.findById(dependencyId);
    if (dependency == null
        || dependency.archetype != kind.archetype()
        || !java.util.Objects.equals(parentId, parentOf(dependencyId))) {
      throw new BadRequestException(
          "Unknown or out-of-"
              + kind(kind.parent()).word()
              + " "
              + kind.dependencyField()
              + ": "
              + dependencyId);
    }
  }

  /**
   * Rejects a dependency edge that would close a cycle, by walking the target's chain over {@code
   * depends_on_entity_id} — never over a membership.
   */
  private void requireNoCycle(Kind kind, String id, String targetId) {
    Set<String> visited = new HashSet<>();
    String cursor = targetId;
    while (cursor != null) {
      if (cursor.equals(id)) {
        throw new BadRequestException(kind.dependencyField() + " would create a dependency cycle");
      }
      if (!visited.add(cursor)) {
        break; // a pre-existing cycle elsewhere in the chain — stop rather than loop forever
      }
      WorkEntity next = entities.findById(cursor);
      cursor = (next == null) ? null : next.dependsOnEntityId;
    }
  }

  /**
   * <b>Intake, read off the registry.</b> Every property {@code requiredAtCreate} names that a caller
   * supplies is checked for text, in {@link #INTAKE_ORDER}, so the first one missing is the 400 —
   * before the wrap and before any row is read. This is where {@code IMPETUS} is demanded of a ticket
   * and the only place it is: {@link Demand#ON_UPDATE} does not ask for it, because {@code
   * entity.impetus} is nullable and emptying it after intake is behaviour a person has.
   */
  private static void requireIntake(Archetype archetype, EntityWrite write) {
    Set<EntityProperty> demanded = Archetypes.spec(archetype).requiredAtCreate();
    for (Map.Entry<EntityProperty, String> field : INTAKE_ORDER.entrySet()) {
      if (demanded.contains(field.getKey())) {
        Validations.requireText(valueOf(write, field.getKey()), field.getValue());
      }
    }
  }

  private static String valueOf(EntityWrite write, EntityProperty property) {
    return switch (property) {
      case TITLE -> write.title();
      case IMPETUS -> write.impetus();
      case TICKET_TYPE -> write.type();
      case REPOSITORY_ID -> write.repositoryId();
      default -> throw new IllegalArgumentException("Not an intake property: " + property);
    };
  }

  /** The type named by {@code type}, null when none is supplied, or a 400 naming the word. */
  private static TicketType parseType(String type) {
    if (type == null) {
      return null;
    }
    return EntityLifecycle.parseType(type)
        .orElseThrow(() -> new BadRequestException("Unknown ticket type: " + type));
  }

  /**
   * The row as a caller sees it, taken after an explicit flush so the Hibernate-managed timestamps
   * are populated — a create is promised a {@code createdAt} and an update an {@code updatedAt} that
   * is not before it.
   */
  private WorkEntity settled(WorkEntity row) {
    entities.getEntityManager().flush();
    return row;
  }

  private void audit(WorkEntity row, String rootId, AuditOperation operation, String changedBy) {
    auditService.record(AuditEntityType.of(row.archetype), row.id, rootId, operation, changedBy, row);
  }

  /**
   * <b>The archetype registry on the ordinary write.</b> A row the registry refuses is a 400 naming
   * every violation at once — which is what {@code Archetypes.validate} answers for and why it
   * returns all of them rather than the first. {@code demand} says which moment this is; see {@link
   * Demand}. There is no tolerated violation anywhere.
   */
  private static void requireArchetypeValid(WorkEntity candidate, Demand demand) {
    List<ArchetypeViolation> violations = Archetypes.validate(candidate, demand);
    if (!violations.isEmpty()) {
      throw new BadRequestException(
          violations.stream().map(ArchetypeViolation::message).collect(Collectors.joining("; ")));
    }
  }

  /** A supplied-but-empty assignee means nobody, not the empty string. */
  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }

  private static List<String> childIds(List<EntityMembership> edges) {
    return edges.stream().map(edge -> edge.childId).toList();
  }

  private static Map<String, WorkEntity> byId(List<WorkEntity> rows) {
    Map<String, WorkEntity> indexed = new LinkedHashMap<>();
    for (WorkEntity row : rows) {
      indexed.put(row.id, row);
    }
    return indexed;
  }
}
