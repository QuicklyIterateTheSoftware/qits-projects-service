package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.campaign.CampaignStartRecordRepository;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.MembershipKind;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.EntityComment;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ConflictException;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.EntityCommentRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 *       under, which kind's phase freezes its scope and what its dependency is called on the wire.
 *       One row per archetype, in {@link #KINDS}. (It also said whether a kind had a comment thread,
 *       until qits-551 gave every kind one.)
 * </ul>
 *
 * <h2>The rules, each stated once</h2>
 *
 * <ul>
 *   <li><b>A row of another archetype is a 404</b>, spelled with the kind asked for: four kinds share
 *       one id space, so "no epic with this id" means "no EPIC row with this id".
 *   <li><b>The scope freeze is the owner's phase.</b> A kind whose {@link Kind#freezeOwner} is set
 *       has its scope frozen by that ancestor's status — an epic by its own, a feature by its epic's,
 *       a task by the epic two hops up ({@link EntityLifecycle#requireReported}); the task
 *       markers move only while the epic is READY_FOR_DEV or IMPLEMENTING ({@link
 *       EntityLifecycle#requireBeingImplemented}). A ticket names no
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
 *   <li><b>Every removed row gets its own DELETE audit row</b> — descendants and every comment in the
 *       subtree
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

  @Inject EntityCommentRepository comments;

  /** The nesting rule's view of the two tables — see {@link #attach}. */
  @Inject StoredEntityFacts facts;

  @Inject AuditService auditService;

  @Inject ReadPatience patience;

  @Inject WritePatience writes;

  /** The per-project numeric id every created row takes; see {@link EntityNumbers}. */
  @Inject EntityNumbers numbers;

  /** A campaign's start — read by the pause hook in {@link #move}, nothing else here. */
  @Inject CampaignStartRecordRepository campaignStarts;

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
   * The retitle seam (qits-617), optional for the same reason: with no implementation an edit still
   * edits and announces nothing. Told by {@link #update}, after the hold, only when the title moved.
   */
  @Inject Instance<RetitleAnnouncer> retitles;

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
   */
  record Kind(
      Archetype archetype,
      String noun,
      Archetype parent,
      Archetype freezeOwner,
      String dependencyField) {

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
    kinds.put(Archetype.EPIC, new Kind(Archetype.EPIC, "Epic", null, Archetype.EPIC, null));
    kinds.put(Archetype.TICKET, new Kind(Archetype.TICKET, "Ticket", null, null, null));
    // A root with no freeze owner — its title and description stay editable at every status, as a
    // ticket's do — and no sibling dependency. It is the fifth kind (qits-411).
    kinds.put(Archetype.CAMPAIGN, new Kind(Archetype.CAMPAIGN, "Campaign", null, null, null));
    kinds.put(
        Archetype.FEATURE,
        new Kind(
            Archetype.FEATURE,
            "Feature",
            Archetype.EPIC,
            Archetype.EPIC,
            "dependsOnFeatureId"));
    kinds.put(
        Archetype.TASK,
        new Kind(
            Archetype.TASK, "Task", Archetype.FEATURE, Archetype.EPIC, "dependsOnTaskId"));
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
   * The kind a row of {@code archetype} is created under — an EPIC for a feature, a FEATURE for a
   * task — or null for a root. {@link Kind#parent} read from outside, so the generic create door and
   * the schema it publishes name the parent this service will look the id up as (qits-548), rather
   * than a second copy of the tree's shape.
   */
  public static Archetype parentKindOf(Archetype archetype) {
    return kind(archetype).parent();
  }

  /**
   * {@code "Epic"} — the noun a refusal about a row of {@code archetype} names it by. {@link
   * Kind#noun} read from outside, so a door one module up that refuses in this service's words
   * (the block door, qits-592) spells the kind the way every refusal here does rather than keeping
   * a second list.
   */
  public static String nounOf(Archetype archetype) {
    return kind(archetype).noun();
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

  /**
   * The row this id names, <b>of whatever archetype</b>, or a 404. For the callers to whom the kind
   * is not the question — a comment thread belongs to the row, not to the kind it is today.
   */
  public WorkEntity find(String id) {
    WorkEntity row = id == null ? null : entities.findById(id);
    if (row == null) {
      throw new NotFoundException("Entity not found: " + id);
    }
    return row;
  }

  /**
   * <b>The audit subtree key of a row</b>: its own id for a root (an epic, a ticket, a campaign), its
   * epic's for a feature or a task. The key the rows' own audit entries carry, offered to the
   * writers beside this service — a comment — so what is said about a node lands in its root's
   * history too.
   */
  public String auditRootOf(WorkEntity row) {
    Kind kind = kind(row.archetype);
    if (kind.isRoot()) {
      return row.id;
    }
    return rootOf(kind, row, owner(kind, row, parentOf(row.id)));
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
    List<String> criteria = AcceptanceCriteria.require(write.acceptanceCriteria());
    if (criteria != null) {
      row.acceptanceCriteria.addAll(criteria);
    }
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
   *
   * <p><b>A changed title is announced once, after the hold returns</b> ({@link RetitleAnnouncer}),
   * never inside it — the body re-runs on a retry, which is also why "changed" is decided afresh on
   * every attempt rather than carried over from one that rolled back. An edit that restates the
   * title it already had announces nothing.
   */
  public Nested update(Archetype archetype, String id, EntityWrite write, String changedBy) {
    Kind kind = kind(archetype);
    boolean[] retitled = {false};
    List<TransitionedEntity> moved = new ArrayList<>(1);
    Nested updated =
        writes.hold(
            kind.label("update"),
            () -> {
              retitled[0] = false;
              moved.clear();
              return edit(kind, archetype, id, write, changedBy, retitled, moved);
            });
    if (!moved.isEmpty()) {
      announce(List.copyOf(moved));
    }
    if (retitled[0] && !retitles.isUnsatisfied()) {
      retitles.get().onRetitled(updated.entity());
    }
    return updated;
  }

  /**
   * The body of {@link #update}: database-only, so a retry may run it again. {@code retitled[0]} is
   * set when the write changes the title, and {@code moved} receives the row when a marker it wrote
   * moved its status too — announced by the caller, after the hold, as every move is.
   *
   * <p><b>A marker write is a status move (qits-763).</b> Stamping {@code implementedAt} — {@code
   * mark_task_implemented}, {@code PUT /tasks}, {@code PUT /features}, {@code PATCH /entities} —
   * moves the feature or task to IMPLEMENTED, and {@code implementingAt} to IMPLEMENTING, in this
   * same transaction ({@link #advanceTo}); clearing {@code implementedAt} takes an IMPLEMENTED row
   * back to where its remaining marker says it stood ({@link #retreatFromImplemented}). Only these
   * doors write a marker, and each moves the status with it, so the two can never disagree.
   */
  private Nested edit(
      Kind kind,
      Archetype archetype,
      String id,
      EntityWrite write,
      String changedBy,
      boolean[] retitled,
      List<TransitionedEntity> moved) {
    WorkEntity row = lookup(archetype, id);
    String parentId = kind.isRoot() ? null : parentOf(id);
    WorkEntity owner = owner(kind, row, parentId);
    if (owner != null) {
      if (write.touchesScope()) {
        EntityLifecycle.requireReported(owner);
      }
      if (write.touchesMarker()) {
        EntityLifecycle.requireBeingImplemented(owner);
      }
    }
    if (write.title() != null) {
      Validations.requireText(write.title(), "title");
      retitled[0] = !write.title().equals(row.title);
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
    if (write.touchesCriteria()) {
      writeCriteria(kind, row, write.acceptanceCriteria());
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
    String statusBefore = row.status;
    boolean piece = Archetypes.isPlanPiece(archetype);
    if (write.implementingAt() != null) {
      row.implementingAt = write.implementingAt();
      if (piece) {
        advanceTo(row, EntityStatus.IMPLEMENTING);
      }
    }
    if (write.clearImplementedAt()) {
      row.implementedAt = null;
      if (piece) {
        retreatFromImplemented(row);
      }
    } else if (write.implementedAt() != null) {
      row.implementedAt = write.implementedAt();
      if (piece) {
        advanceTo(row, EntityStatus.IMPLEMENTED);
      }
    }
    requireArchetypeValid(row, Demand.ON_UPDATE);
    WorkEntity updated = settled(row);
    audit(updated, rootOf(kind, updated, owner), AuditOperation.UPDATE, changedBy);
    if (!java.util.Objects.equals(statusBefore, updated.status)) {
      moved.add(TransitionedEntity.of(updated, edgeOf(updated.id), statusBefore, changedBy));
    }
    return new Nested(updated, parentId);
  }

  /**
   * <b>The acceptance criteria an edit restates</b> (qits-887), replacing the list whole after the
   * item rules ({@link AcceptanceCriteria}) — a 400 naming every broken item. They sit outside an
   * epic's scope freeze, so a REFINED epic can still gain the criteria its scheduling needs. <b>From
   * READY_FOR_DEV on they are frozen</b>, for an epic and a ticket alike: the scheduled content is
   * what a person approved, so a <em>changed</em> list is a 409 there (unschedule to change it).
   * Restating the list unchanged — the same items in the same order — is no change and passes at
   * every status, which is what lets a form restate the whole row. A kind with no slot for them is
   * left to the registry's 400.
   */
  private static void writeCriteria(Kind kind, WorkEntity row, List<String> stated) {
    List<String> criteria = AcceptanceCriteria.require(stated);
    if (criteria.equals(row.acceptanceCriteria)) {
      return;
    }
    requireCriteriaEditable(kind.noun(), row);
    row.acceptanceCriteria.clear();
    row.acceptanceCriteria.addAll(criteria);
  }

  /**
   * The freeze on a changed acceptance-criteria list (qits-887): a 409 while {@code row} is
   * READY_FOR_DEV or further along the walk. Off the walk (DROPPED) nothing is frozen — a dropped
   * entity is scheduled by nobody. Shared with {@link EntityTransitionService}, whose PUT restates
   * the list on every entry.
   */
  static void requireCriteriaEditable(String noun, WorkEntity row) {
    EntityStatus status = statusOf(row.status);
    if (status != null
        && !EntityStateMachine.isOffWalk(status)
        && EntityStateMachine.isAtOrPast(status, EntityStatus.READY_FOR_DEV)) {
      throw new ConflictException(
          "The acceptance criteria of "
              + noun.toLowerCase(Locale.ROOT)
              + " "
              + row.id
              + " are frozen: it is "
              + row.status
              + ", and what a person scheduled is not changed underneath them"
              + (status == EntityStatus.READY_FOR_DEV
                  ? ". Move it back to REFINED (unschedule it) to change them."
                  : ": they are frozen from READY_FOR_DEV on."));
    }
  }

  /**
   * <b>The status half of a marker write</b> (qits-763): a feature or a task still before {@code
   * target} on the walk moves to it, and one at or past it stays where it is — a second marking does
   * not move a VERIFIED task back. Before means REPORTED, REFINED or READY_FOR_DEV for IMPLEMENTING,
   * and those or IMPLEMENTING for IMPLEMENTED: the forward move and the skip the graph declares, and
   * from REPORTED or REFINED (a piece somebody moved back, or one that was left unscheduled while its
   * epic was being implemented) the marker is the statement that settles it, so it lands there all
   * the same rather than refusing a fact.
   *
   * <p>A DROPPED piece is a 409: it was decided against, and a marker on it would claim work on
   * something nobody is to do. Reopening it is a move of its own. Answers whether the row moved.
   */
  private static boolean advanceTo(WorkEntity row, EntityStatus target) {
    EntityStatus current = EntityStatus.valueOf(row.status);
    if (EntityStateMachine.isOffWalk(current)) {
      throw new ConflictException(
          kind(row.archetype).noun()
              + " "
              + row.id
              + " is "
              + current
              + ": it was decided against, so it takes no marker. Reopen it first if the work is"
              + " to be done after all.");
    }
    if (EntityStateMachine.isAtOrPast(current, target)) {
      return false;
    }
    row.status = target.name();
    return true;
  }

  /**
   * <b>A cleared {@code implementedAt} takes an IMPLEMENTED piece back</b> — to IMPLEMENTING when its
   * implementing marker says the work was started, else to READY_FOR_DEV (scheduled with its epic and
   * not started; markers only move while the epic is READY_FOR_DEV or IMPLEMENTING, qits-887) — so
   * the status does not go on
   * claiming what the marker no longer does. A piece past IMPLEMENTED (somebody verified it) keeps
   * its status: the clear is a correction of the marker's history, not of a verification.
   */
  private static void retreatFromImplemented(WorkEntity row) {
    if (EntityStatus.IMPLEMENTED.name().equals(row.status)) {
      row.status =
          (row.implementingAt != null ? EntityStatus.IMPLEMENTING : EntityStatus.READY_FOR_DEV)
              .name();
    }
  }

  /**
   * <b>Marks a task's implementation started</b> (qits-749) — what {@code mark_task_implementing}
   * does, and since qits-763 a status move: the task goes to IMPLEMENTING with its marker.
   *
   * <ul>
   *   <li>The task's {@code implementingAt} is stamped now if it is unset, and kept if it is set: the
   *       call is idempotent, and a second press does not rewrite when the work started. Its status
   *       moves to IMPLEMENTING when it is still before it ({@link #advanceTo}), in the same
   *       transaction, so marker and status never disagree.
   *   <li>Its feature is stamped too, the first time one of its tasks is marked, and moved to
   *       IMPLEMENTING the same way: a feature reads as implementing once any of its tasks is, and
   *       stamping it here puts {@code implementingOn} and the status on the wire without every
   *       consumer deriving them. A DROPPED feature is left alone rather than refusing its task.
   *   <li>Legal while the owning epic is READY_FOR_DEV or IMPLEMENTING ({@link
   *       EntityLifecycle#requireBeingImplemented}); <b>a READY_FOR_DEV epic moves to
   *       IMPLEMENTING</b> ({@link EntityStateMachine#startedStatusOf}), so
   *       an agent that starts work without a dispatch press still shows on the board. That move is
   *       an ordinary move ({@link #transitionFrom}), after the stamp, so it is announced like any
   *       other.
   * </ul>
   *
   * Skippable: {@code mark_task_implemented} without this before it stays legal and leaves the
   * marker null. Entering IMPLEMENTING on the epic stamps and moves nothing beneath it. The task's
   * and the feature's moves are announced together, after the hold, and before the epic's own.
   */
  public Nested markImplementing(String taskId, String changedBy) {
    Kind kind = kind(Archetype.TASK);
    WorkEntity[] epic = {null};
    List<TransitionedEntity> moved = new ArrayList<>(2);
    Nested marked =
        writes.hold(
            kind.label("implementing"),
            () -> {
              moved.clear();
              WorkEntity row = lookup(Archetype.TASK, taskId);
              String featureId = parentOf(taskId);
              WorkEntity owner = owner(kind, row, featureId);
              EntityLifecycle.requireBeingImplemented(owner);
              epic[0] = owner;
              // Truncated to what the column holds, so the answer handed back equals the row read.
              Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
              String taskBefore = row.status;
              boolean taskMoved = advanceTo(row, EntityStatus.IMPLEMENTING);
              if (row.implementingAt == null || taskMoved) {
                if (row.implementingAt == null) {
                  row.implementingAt = now;
                }
                requireArchetypeValid(row, Demand.ON_UPDATE);
                audit(row, owner.id, AuditOperation.UPDATE, changedBy);
              }
              WorkEntity feature = featureId == null ? null : entities.findById(featureId);
              String featureBefore = feature == null ? null : feature.status;
              boolean featureMoved = false;
              if (feature != null && feature.implementingAt == null) {
                feature.implementingAt = now;
                featureMoved =
                    !EntityStatus.DROPPED.name().equals(feature.status)
                        && advanceTo(feature, EntityStatus.IMPLEMENTING);
                requireArchetypeValid(feature, Demand.ON_UPDATE);
                audit(feature, owner.id, AuditOperation.UPDATE, changedBy);
              }
              WorkEntity settledRow = settled(row);
              if (taskMoved) {
                moved.add(
                    TransitionedEntity.of(settledRow, edgeOf(settledRow.id), taskBefore, changedBy));
              }
              if (featureMoved) {
                moved.add(
                    TransitionedEntity.of(feature, edgeOf(feature.id), featureBefore, changedBy));
              }
              return new Nested(settledRow, featureId);
            });
    if (!moved.isEmpty()) {
      announce(List.copyOf(moved));
    }
    // The epic's "implementation started" move: READY_FOR_DEV → IMPLEMENTING, read off the machine
    // rather than spelled here. An epic already IMPLEMENTING has no started move and stays.
    EntityStatus epicStatus = statusOf(epic[0].status);
    if (epicStatus != null
        && EntityStateMachine.phaseStartedBy(epicStatus)
            .equals(Optional.of(EntityStateMachine.Phase.IMPLEMENT))) {
      Optional<EntityStatus> started = EntityStateMachine.startedStatusOf(epicStatus);
      if (started.isPresent()) {
        transitionFrom(Archetype.EPIC, epic[0].id, epicStatus, started.get(), changedBy);
      }
    }
    return marked;
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
   *   <li><b>Five moves carry the descendants</b> ({@link #carryDescendants}, qits-763, qits-887):
   *       an epic REPORTED → REFINED and back, REFINED → READY_FOR_DEV and back, and any move to
   *       {@link EntityStatus#IMPLEMENTED}, which also
   *       stamps every descendant still unimplemented ({@link #stampImplemented}) — declaring the
   *       work done is declaring its scope done. Every carried child joins the announcement. No
   *       other move touches a child.
   *   <li><b>A feature's or a task's own move waits for its epic</b>: refused while the epic is
   *       REPORTED ({@link #requireOwnerPastDraft}), and its own scheduling and unscheduling are the
   *       epic's ({@link #requirePieceScheduledWithItsEpic}, qits-887).
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
   * move, so it left no status: {@code statusBefore} null, status REPORTED), plus every descendant
   * the move carried, each with the status it left. See {@link TransitionAnnouncer}.
   */
  public Transition transition(Archetype archetype, String id, String target, String changedBy) {
    Kind kind = kind(archetype);
    Validations.requireText(target, "target");
    Moved moved =
        writes.hold(
            kind.label("transition"), () -> move(kind, archetype, id, null, target, changedBy));
    announce(moved.batch());
    return moved.transition();
  }

  /**
   * <b>{@link #transition}, but only from {@code from}</b> (qits-749): the move is made when the row
   * is still at {@code from} as the write reads it, and nothing happens — no write, no announcement
   * — when it is anywhere else. Empty then. For the platform's own moves into IMPLEMENTING (a
   * dispatch press, a FLOW hand-off, a first {@code mark_task_implementing}), each of which means
   * "READY_FOR_DEV, and implementation starts now": an agent that got further in the meantime (straight
   * to IMPLEMENTED by the skip) must not be moved BACK by a platform that was a moment late.
   */
  public Optional<Transition> transitionFrom(
      Archetype archetype, String id, EntityStatus from, EntityStatus target, String changedBy) {
    Kind kind = kind(archetype);
    Moved moved =
        writes.hold(
            kind.label("transition"),
            () -> move(kind, archetype, id, from, target.name(), changedBy));
    if (moved == null) {
      return Optional.empty();
    }
    announce(moved.batch());
    return Optional.of(moved.transition());
  }

  /**
   * The body of {@link #transition}: database-only, so a retry may run it again. With {@code
   * expected} set, a row at any other status is left alone and the answer is null.
   */
  private Moved move(
      Kind kind,
      Archetype archetype,
      String id,
      EntityStatus expected,
      String target,
      String changedBy) {
    WorkEntity row = lookup(archetype, id);
    if (expected != null && !expected.name().equals(row.status)) {
      return null;
    }
    if (archetype == Archetype.CAMPAIGN) {
      // The campaign row first (qits-417), re-read under its lock, and only then the start row the
      // pause hook below updates: the order CampaignService.start takes the same two rows in, so a
      // pause racing a start press serialises on this row instead of deadlocking on the pair.
      entities.getEntityManager().refresh(row, LockModeType.PESSIMISTIC_WRITE);
    }
    String statusBefore = row.status;
    EntityStatus to = targetStatus(kind, row, target);
    requireSupersedable(kind, row, target);
    if (Archetypes.isPlanPiece(archetype)) {
      requireOwnerPastDraft(kind, row);
      requirePieceScheduledWithItsEpic(kind, row, to);
    }

    WorkEntity successor =
        kind.supersedable() && SUPERSEDE.equals(target) ? supersede(kind, row, changedBy) : null;
    List<Carried> carried = carryDescendants(row, statusOf(statusBefore), to, changedBy);
    row.status = to.name();
    row.blocked = false;
    if (archetype == Archetype.CAMPAIGN
        && EntityStatus.REFINED.name().equals(statusBefore)
        && to != EntityStatus.REFINED) {
      // The pause hook (qits-413): a campaign that leaves REFINED stops its executor, in this very
      // transaction, so no claim can land between the move and the pause. The UPDATE takes the row
      // lock the executor's claim waits on; a campaign never started has no row and nothing happens.
      // Resuming is a new start press at REFINED — moving back is not enough.
      campaignStarts.pause(row.id);
    }
    if (successor != null) {
      row.supersededByEntityId = successor.id;
    }
    requireArchetypeValid(row, Demand.ON_UPDATE);
    WorkEntity moved = settled(row);
    audit(moved, moved.id, AuditOperation.UPDATE, changedBy);

    List<TransitionedEntity> batch = new ArrayList<>(2 + carried.size());
    batch.add(TransitionedEntity.of(moved, edgeOf(moved.id), statusBefore, changedBy));
    if (successor != null) {
      batch.add(TransitionedEntity.of(successor, edgeOf(successor.id), null, changedBy));
    }
    for (Carried child : carried) {
      batch.add(TransitionedEntity.of(child.row(), child.edge(), child.statusBefore(), changedBy));
    }
    return new Moved(new Transition(moved, successor, statusBefore), List.copyOf(batch));
  }

  /** A descendant a move carried with it, the status it left and the edge it hangs by. */
  private record Carried(WorkEntity row, EntityMembership edge, String statusBefore) {}

  /** The stored word as the enum, or null for none. */
  private static EntityStatus statusOf(String word) {
    return word == null ? null : EntityStatus.valueOf(word);
  }

  /**
   * <b>A feature's or a task's own move waits for its epic to be refined</b> (qits-763): while the
   * epic is REPORTED the plan is a draft, and a piece of a draft is edited or removed, not moved —
   * removing one there is a delete, and its status is carried to REFINED with the epic's. A 409
   * naming the epic. This is the only phase rule on a piece's move: {@link
   * EntityLifecycle#requireReported} guards structural writes, and a status move is not one.
   */
  private void requireOwnerPastDraft(Kind kind, WorkEntity row) {
    WorkEntity epic = owner(kind, row, parentOf(row.id));
    if (epic != null && EntityStatus.REPORTED.name().equals(epic.status)) {
      throw new ConflictException(
          kind.noun()
              + " "
              + row.id
              + " does not move on its own while its epic "
              + epic.id
              + " is REPORTED: the plan is still a draft — edit the "
              + kind.word()
              + " or remove it, and its status moves to REFINED with the epic's.");
    }
  }

  /**
   * <b>A piece is not scheduled on its own</b> (qits-887): scheduling is the epic's, and its move
   * carries the pieces ({@link #carryDescendants}). So a feature's or a task's own move to
   * READY_FOR_DEV is refused while its epic is not READY_FOR_DEV or past it, and its own READY_FOR_DEV
   * → REFINED while the epic is still READY_FOR_DEV. A 409 naming the epic. Once the epic is past
   * READY_FOR_DEV (work started), a piece may catch up on its own — that is not scheduling anything.
   */
  private void requirePieceScheduledWithItsEpic(Kind kind, WorkEntity row, EntityStatus to) {
    EntityStatus from = statusOf(row.status);
    boolean scheduling = from == EntityStatus.REFINED && to == EntityStatus.READY_FOR_DEV;
    boolean unscheduling = from == EntityStatus.READY_FOR_DEV && to == EntityStatus.REFINED;
    if (!scheduling && !unscheduling) {
      return;
    }
    WorkEntity epic = owner(kind, row, parentOf(row.id));
    if (epic == null) {
      return;
    }
    EntityStatus epicStatus = statusOf(epic.status);
    boolean epicScheduled =
        epicStatus != null
            && !EntityStateMachine.isOffWalk(epicStatus)
            && EntityStateMachine.isAtOrPast(epicStatus, EntityStatus.READY_FOR_DEV);
    if (scheduling && !epicScheduled) {
      throw new ConflictException(
          kind.noun()
              + " "
              + row.id
              + " is not scheduled on its own: its epic "
              + epic.id
              + " is "
              + epic.status
              + " — schedule the epic, and its move to READY_FOR_DEV carries the "
              + kind.word()
              + " with it.");
    }
    if (unscheduling && epicStatus == EntityStatus.READY_FOR_DEV) {
      throw new ConflictException(
          kind.noun()
              + " "
              + row.id
              + " is not unscheduled on its own while its epic "
              + epic.id
              + " is READY_FOR_DEV — unschedule the epic, and its move back to REFINED carries the "
              + kind.word()
              + " with it.");
    }
  }

  /**
   * <b>The downward cascades of a move</b> (qits-763, qits-887), in the move's transaction, each
   * child audited under the move's own {@code changedBy} and handed back to join the move's one
   * announcement. Five moves carry descendants, and no other does:
   *
   * <ul>
   *   <li><b>An epic REPORTED → REFINED</b> moves its REPORTED descendants to REFINED. Scope
   *       freezes at REFINED, so the refinement covers the whole tree: a piece left REPORTED under a
   *       frozen plan would advertise a draft nobody can edit.
   *   <li><b>An epic REFINED → REPORTED</b> moves its REFINED descendants back, the same rule read
   *       the other way: the scope is a draft again, and so are its pieces. A piece already further
   *       on stays where it is.
   *   <li><b>An epic REFINED → READY_FOR_DEV</b> (qits-887) moves its REFINED descendants to
   *       READY_FOR_DEV: scheduling the epic schedules its plan. A piece is never scheduled on its own
   *       ({@link #requirePieceScheduledWithItsEpic}); a DROPPED one stays dropped. A person's gate on
   *       the scheduling is judged once, on the epic, never per piece.
   *   <li><b>An epic READY_FOR_DEV → REFINED</b> (qits-887, unscheduling) moves its descendants
   *       still READY_FOR_DEV — not started — back to REFINED. A piece at IMPLEMENTING or further, and
   *       a DROPPED one, stays. An epic back at REFINED can never go on to REPORTED with a piece
   *       still READY_FOR_DEV behind it, because READY_FOR_DEV → REPORTED is not a move.
   *   <li><b>Any move to IMPLEMENTED</b> — the epic's, or a feature's own — moves every descendant
   *       still before IMPLEMENTED on the walk to IMPLEMENTED, and stamps every unimplemented marker
   *       ({@link #stampImplemented}): declaring the work done is declaring its scope done. A
   *       DROPPED descendant was decided against and is neither moved nor stamped.
   * </ul>
   *
   * <p><b>Nothing else moves a child.</b> In particular an epic going to VERIFYING or VERIFIED
   * leaves its tasks where they are — that sibling drag is what qits-763 removed: a task is verified
   * on its own. The cascades set the word directly rather than through the graph, because they
   * state where the plan stands (a REPORTED task under an epic declared IMPLEMENTED is not a move
   * anybody asked to judge). And none of them reads children to decide a parent: that would be
   * derivation, which the lifecycle deliberately does not do.
   */
  private List<Carried> carryDescendants(
      WorkEntity row, EntityStatus from, EntityStatus to, String changedBy) {
    boolean epic = row.archetype == Archetype.EPIC;
    boolean refining = epic && from == EntityStatus.REPORTED && to == EntityStatus.REFINED;
    boolean reopening = epic && from == EntityStatus.REFINED && to == EntityStatus.REPORTED;
    boolean scheduling = epic && from == EntityStatus.REFINED && to == EntityStatus.READY_FOR_DEV;
    boolean unscheduling = epic && from == EntityStatus.READY_FOR_DEV && to == EntityStatus.REFINED;
    boolean implementing = to == EntityStatus.IMPLEMENTED;
    if (!refining && !reopening && !scheduling && !unscheduling && !implementing) {
      return List.of();
    }
    Subtree subtree = subtreeOf(row.id);
    String rootId = epic ? row.id : auditRootOf(row);
    if (implementing) {
      stampImplemented(row, subtree, rootId, changedBy);
    }
    List<Carried> carried = new ArrayList<>();
    for (WorkEntity node : subtree.preOrder(row.id)) {
      EntityStatus current = statusOf(node.status);
      EntityStatus next = null;
      if (refining && current == EntityStatus.REPORTED) {
        next = EntityStatus.REFINED;
      } else if (reopening && current == EntityStatus.REFINED) {
        next = EntityStatus.REPORTED;
      } else if (scheduling && current == EntityStatus.REFINED) {
        next = EntityStatus.READY_FOR_DEV;
      } else if (unscheduling && current == EntityStatus.READY_FOR_DEV) {
        next = EntityStatus.REFINED;
      } else if (implementing
          && current != null
          && !EntityStateMachine.isOffWalk(current)
          && !EntityStateMachine.isAtOrPast(current, EntityStatus.IMPLEMENTED)) {
        next = EntityStatus.IMPLEMENTED;
      }
      if (next == null) {
        continue;
      }
      node.status = next.name();
      audit(node, rootId, AuditOperation.UPDATE, changedBy);
      carried.add(new Carried(node, subtree.edgeOf().get(node.id), current.name()));
    }
    return List.copyOf(carried);
  }

  /** A row's edge, or null for a root — the statement {@code EntityFact.parentId} makes. */
  private EntityMembership edgeOf(String id) {
    return memberships.structuralMembershipOf(id).orElse(null);
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
   * omission.</b> Blocking is meaningful only where a phase runs; which status starts a phase is
   * {@link EntityStateMachine#phaseStartedBy}, and refusing on it is the doors' business (they
   * answer a VERIFIED, DONE or DROPPED row with a 409 through {@code projects/api/PhasePrompts}). A
   * second list of the phased statuses written here would be the drift the machine exists to
   * prevent.
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
   * kind with no lifecycle, and so is IMPLEMENTING or VERIFYING for a campaign, which enters
   * neither.
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
    if (kind.archetype() == Archetype.CAMPAIGN
        && !EntityStateMachine.states(Archetype.CAMPAIGN).contains(to)) {
      // A campaign never enters IMPLEMENTING or VERIFYING (qits-749): its press starts it and
      // REFINED is what "running" means, so either status would say nothing a campaign's start does
      // not already say. Its lifecycle elides both already; this refusal is here for the sentence.
      throw new ConflictException(
          "A campaign never moves to "
              + to
              + ": campaign "
              + row.id
              + " runs while it is REFINED, and its members are what is implemented and verified.");
    }
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
   * feature — over the subtree read a level at a time. A feature or a task moved to IMPLEMENTED on
   * its own is stamped too, for the reason its marker doors move its status (qits-763): the marker
   * and the status say the same thing. A DROPPED descendant is not stamped — nothing of it is to be
   * implemented. Each stamp is audited here only when {@link #carryDescendants} will not audit the
   * same node's status move a moment later, so a carried node gets one UPDATE row and not two.
   */
  private void stampImplemented(
      WorkEntity root, Subtree subtree, String rootId, String changedBy) {
    Instant now = Instant.now();
    for (WorkEntity node : subtree.postOrder(root.id)) {
      if (node.implementedAt == null && !EntityStatus.DROPPED.name().equals(node.status)) {
        node.implementedAt = now;
        if (!movesToImplemented(node)) {
          audit(node, rootId, AuditOperation.UPDATE, changedBy);
        }
      }
    }
    if (Archetypes.isPlanPiece(root.archetype) && root.implementedAt == null) {
      root.implementedAt = now;
    }
  }

  /** Whether {@link #carryDescendants} moves this node to IMPLEMENTED (and audits it then). */
  private static boolean movesToImplemented(WorkEntity node) {
    EntityStatus current = statusOf(node.status);
    return current != null
        && !EntityStateMachine.isOffWalk(current)
        && !EntityStateMachine.isAtOrPast(current, EntityStatus.IMPLEMENTED);
  }

  /**
   * The successor draft of a superseded row: a new {@link EntityStatus#REPORTED} row of the same kind
   * carrying the old title, description and acceptance criteria and the whole tree beneath it, so
   * refinement restarts
   * from what was discarded rather than from a blank page.
   *
   * <p>Copies get fresh ids and keep their slugs — each node's scope is its new parent, so the name
   * is free again. The <em>root's</em> slug is the exception: its scope is the project, where the old
   * row still holds it, so the successor mints the next free one exactly as a hand-created row would.
   * The markers reset and every copied feature and task is REPORTED (nothing is implemented in a
   * draft) and {@code dependsOn} is
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
            EntityWrite.epic(old.title, old.description)
                .withAcceptanceCriteria(List.copyOf(old.acceptanceCriteria)),
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
   * the edge that puts it there. The slug is kept, both markers reset, the status is REPORTED — a
   * piece of a draft, as its new epic is (qits-763) — and {@code dependsOn} is left for the second
   * pass. The edge's id is the child's, V10's rule.
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
    copy.status = EntityStatus.REPORTED.name();
    entities.persist(copy);

    EntityMembership edge = new EntityMembership();
    edge.id = copy.id;
    edge.kind = MembershipKind.STRUCTURAL;
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
   *   <li>every campaign membership of the row or a descendant goes and its gap is closed, so every
   *       campaign it was gathered into stays dense — the FK would cascade the edges away but leave
   *       the holes. The subtree is the <em>structural</em> one: a campaign's own members are not
   *       below it, so deleting a campaign removes its edges (by the cascade) and none of its work;
   *   <li>every comment on the row or a descendant goes, each with a DELETE audit row under the
   *       root's key — <em>before</em> any row does, because the audit write flushes, and a row
   *       already gone would have cascaded its comments away with no trace (qits-551: every kind has
   *       a thread now, so a deleted epic's tasks carry comments too);
   *   <li>its descendants go, each with a DELETE audit row, oldest first — the subtree read a level
   *       at a time, their edges with them by the FK's cascade;
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
          EntityMembership edge =
              kind.isRoot() ? null : memberships.structuralMembershipOf(id).orElse(null);
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
          // Before any row goes: a query here auto-flushes, and a row already deleted would have
          // cascaded its campaign edges away and left their gaps behind.
          leaveCampaigns(id, subtree);
          List<String> threaded = new ArrayList<>();
          threaded.add(id);
          threaded.addAll(subtree.rows().keySet());
          for (EntityComment comment : comments.listByEntities(threaded)) {
            comments.delete(comment);
            auditService.record(
                AuditEntityType.COMMENT,
                comment.id,
                rootId,
                AuditOperation.DELETE,
                changedBy,
                comment);
          }
          for (WorkEntity descendant : subtree.oldestFirst()) {
            entities.delete(descendant);
            audit(descendant, rootId, AuditOperation.DELETE, changedBy);
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

  /**
   * Removes every CAMPAIGN edge whose child is {@code id} or one of its descendants, closing each
   * campaign's gap as it goes. <b>Highest position first</b>: {@code campaignCloseGapAfter} is a bulk
   * update the session does not see, so an edge still to be removed must never be one a previous
   * close has already shifted — and removing from the top down means no close ever touches one.
   */
  private void leaveCampaigns(String id, Subtree subtree) {
    List<String> gone = new ArrayList<>();
    gone.add(id);
    gone.addAll(subtree.rows().keySet());
    List<EntityMembership> edges = new ArrayList<>();
    for (String member : gone) {
      edges.addAll(memberships.campaignMembershipsOf(member));
    }
    edges.sort(
        java.util.Comparator.comparingInt((EntityMembership edge) -> edge.position).reversed());
    for (EntityMembership edge : edges) {
      memberships.delete(edge);
      memberships.campaignCloseGapAfter(edge.parentId, edge.position);
    }
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
    return memberships.structuralMembershipOf(childId).map(edge -> edge.parentId).orElse(null);
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
   * children, then judged by {@link Nesting} over the post-state it produces. <b>A structural edge's
   * id is the child's</b>, V10's rule: an edge's identity is the end of it that can only be in one (a
   * campaign edge, which a child may have several of, takes a random id instead). {@code
   * dependsOn} is never handed to the nesting rule — a dependency is a sibling ordering edge, and
   * containment is what {@code Nesting} is about.
   */
  private void attach(String parentId, WorkEntity row) {
    EntityMembership edge = new EntityMembership();
    edge.id = row.id;
    edge.kind = MembershipKind.STRUCTURAL;
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
