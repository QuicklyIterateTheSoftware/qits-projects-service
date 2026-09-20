package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.Archetype;
import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.EntityMembership;
import eu.wohlben.qits.epics.entity.WorkEntity;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.persistence.EntityMembershipRepository;
import eu.wohlben.qits.epics.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <b>The multi-entity transition: one intended post-state, validated whole, applied whole.</b>
 *
 * <p>A caller hands in a map of entity id to the <em>full</em> state that entity is to have
 * afterwards. Everything in it is judged together, written in one transaction, and announced once.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A feature becoming an epic while its tasks are rescoped is a state <b>no ordering of
 * single-entity writes can reach legally</b>. Re-archetype the feature first and there is an epic
 * under an epic; reparent it first and there is a feature at the root; move the tasks first and they
 * hang under something that is still a feature. Every intermediate shape is refused by a rule that
 * is correct, and the operation as a whole is correct — which is the argument {@code Nesting}'s
 * javadoc makes and this class is the caller it was written for.
 *
 * <p>So the unit of work is the post-state, not the row. That is also the concurrency answer:
 * <b>validation and application happen in the same transaction</b>, inside one {@link WritePatience}
 * body, so nothing read during validation can move before it is written. There is no subtree token,
 * no re-read and <b>no per-entity version</b> — {@code WorkEntity} and {@code EntityMembership}
 * carry no {@code @Version}, the single per-entity {@code PUT}s use none, and inventing optimistic
 * locking here would be a second concurrency model for one table.
 *
 * <h2>Existing ids only</h2>
 *
 * <p><b>Nothing is created and nothing is deleted here.</b> An id in the map that names no row is a
 * refusal, and so is a {@code membership.parent} that is in neither the map nor the store. Creating
 * on an unknown id is the one thing a transition must never do: the caller supplied that id, and an
 * id it got wrong would become a row nobody meant rather than a message somebody reads.
 *
 * <p>Those are <b>collected as violations rather than thrown as 404s</b>, one at a time. A caller
 * fixing one id per round trip is the failure mode the structured violations exist to avoid, and it
 * is worse here than anywhere else in the module because the fixes are moves: told one at a time, a
 * caller walks a tree through several invalid shapes to reach a valid one. So the answer is 400 with
 * every complaint in it, including the ones that are really "no such thing".
 *
 * <h2>Three layers of validation, ONE rejection</h2>
 *
 * <p>All three run before anything is written, and every finding from all three comes back together:
 *
 * <ol>
 *   <li><b>The row.</b> Each entry against its <em>target</em> archetype, through {@code
 *       Archetypes.validate(EntityState)} — so a property the target has no slot for is refused
 *       rather than dropped, and a status word from the other lifecycle is named.
 *   <li><b>The slug scope.</b> A move changes what a slug is unique within, so a slug that was free
 *       under one parent may be taken under another. That is a <b>validation refusal naming the slug
 *       and the new parent</b>, computed over the post-state occupancy of every affected scope —
 *       never a constraint violation arriving as a 500, and never a silent re-mint.
 *   <li><b>The tree.</b> {@code Nesting.check} over the stated facts plus {@link StoredEntityFacts},
 *       which is what brings in the entities the request never mentioned: upwards for an untouched
 *       parent's archetype, downwards because re-archetyping a row re-judges every child it already
 *       has.
 * </ol>
 *
 * <h2>The two rules this operation states itself</h2>
 *
 * <ul>
 *   <li><b>An entry whose target archetype declares status words must state a status.</b> {@code
 *       Archetypes} declares {@code STATUS} merely <em>permitted</em> on an {@code EPIC} because an
 *       epic's first status is minted by {@code EpicService.create} and requiring it would fail every
 *       create. A transition mints nothing, so under the PUT rule an omitted status would
 *       <em>clear</em> one and leave a status-less epic {@code EpicLifecycle.parse} cannot read.
 *       Requiring it of the caller is the only answer that neither invents a value nor ships a
 *       lifecycle-broken row. The word is still judged by the registry against the target's
 *       vocabulary.
 *   <li><b>A transition does not move work between projects.</b> Every row carries {@code
 *       project_id} and a descendant's is copied from its parent at create; a reparent across
 *       projects would either leave a stale value or need a cascade down into entities the request
 *       never mentioned. Refusing it is the honest answer, and no surface asks for the move.
 * </ul>
 *
 * <p><b>This is NOT a lifecycle move.</b> {@code EpicLifecycle.requireTransition} and {@code
 * TicketLifecycle.requireTransition} are not run here and must not be: the adjacency rules —
 * one step forward or back along five statuses — stay owned by the two existing transition
 * endpoints, which is where a caller asking "advance this ticket" goes. This endpoint answers a
 * different question, "make the shape of the plan be this", and a status it is handed is part of the
 * shape rather than a step.
 *
 * <p><b>The impetus concession applies here in full</b>, and it is the same one {@code
 * TicketService.update} gets — see {@link ImpetusConcession} for why it is a property of every
 * update path rather than of the ticket path. A promotion to {@code TICKET} with no impetus is
 * therefore accepted; one carrying a foreign property, an illegal status or a missing title, ticket
 * type or status is refused as ever.
 *
 * <h2>What the write maintains</h2>
 *
 * <ul>
 *   <li><b>{@code slug_scope}</b> is recomputed for every entry — the parent id for a child, the
 *       project id for a root — while {@code slug} itself is untouched, which is the separation
 *       {@code WorkEntity.slugScope} exists for.
 *   <li><b>Positions stay dense and zero-based on BOTH the old and the new parent.</b> One
 *       renumber, in {@link #replaceMemberships}, rather than a {@code closeGapAfter} per entity:
 *       several entities leaving one parent in a single request would make a sequence of gap-closes
 *       read stale positions from each other.
 *   <li><b>One audit row per entry</b>, {@code UPDATE}, with the {@code AuditEntityType} of the
 *       <em>target</em> archetype and the post-state subtree root as its key — the epic's id for an
 *       {@code EPIC}/{@code FEATURE}/{@code TASK}, the ticket's own id for a {@code TICKET}.
 * </ul>
 *
 * <p><b>No SSE hint is fired here</b>, and no announcement is made inside the write. The {@link
 * WritePatience} body re-runs on a retry, so everything in it is rows and nothing else; the
 * controller fires the change hints after this method returns, and the announcement below is made
 * after the transaction has committed.
 */
@ApplicationScoped
public class EntityTransitionService {

  /**
   * <b>The two properties a caller neither states nor clears</b>, in one place because two things
   * read them: {@link #propertyViolations} below, which carries them onto the candidate so neither
   * is a {@code NOT_PERMITTED} complaint and neither is cleared by omission, and {@code
   * ArchetypeRegistryDocument}, which serves them so a client renders no form field for either. Left
   * as a literal in both, the list a client is told and the list the server enforces would be free
   * to drift, and the drift would show up as a field a caller can fill in and the server silently
   * ignores. {@link EntityTransition} carries the full reasoning for each.
   */
  public static final Set<EntityProperty> SERVER_OWNED =
      Set.of(EntityProperty.SLUG, EntityProperty.CREATED_BY);

  @Inject WorkEntityRepository entities;

  @Inject EntityMembershipRepository memberships;

  /** The nesting rule's view of the two tables — the untouched half of the post-state. */
  @Inject StoredEntityFacts facts;

  @Inject AuditService auditService;

  @Inject WritePatience writes;

  /**
   * The announcement seam. Optional, like every port this repository declares: with no
   * implementation a transition simply announces nothing.
   */
  @Inject Instance<TransitionAnnouncer> announcer;

  /**
   * Applies {@code requested} as one post-state and answers what was written, keyed by id in the
   * order it was stated.
   *
   * @param requested entity id to the full state that entity is to have afterwards
   * @param changedBy the audit principal
   * @throws BadRequestException carrying <b>every</b> violation, joined with {@code "; "}, when any
   *     part of the post-state is refused. Nothing is written in that case
   */
  public Map<String, TransitionedEntity> transition(
      Map<String, EntityTransition> requested, String changedBy) {
    Map<String, EntityTransition> stated = normalise(requested);

    Map<String, TransitionedEntity> written =
        writes.hold("entity transition", () -> apply(stated, changedBy));

    // After the transaction, never inside it: the WritePatience body re-runs on a retry and an
    // announcement in it would be made twice.
    announce(written);
    return written;
  }

  // --- the request, before any row is read ----------------------------------

  /**
   * The shape checks that need no row, so a malformed request is a 400 on the first attempt rather
   * than a question retried for fifteen seconds. The order of the caller's map is kept: it decides
   * the order violations are reported in and the order two entries claiming one position resolve in.
   */
  private static Map<String, EntityTransition> normalise(Map<String, EntityTransition> requested) {
    if (requested == null || requested.isEmpty()) {
      throw new BadRequestException("a transition must state at least one entity");
    }
    Map<String, EntityTransition> stated = new LinkedHashMap<>();
    List<String> refused = new ArrayList<>();
    for (Map.Entry<String, EntityTransition> entry : requested.entrySet()) {
      String id = entry.getKey();
      EntityTransition target = entry.getValue();
      if (id == null || id.isBlank()) {
        refused.add("an entity id is required as the key of every entry");
        continue;
      }
      if (target == null) {
        refused.add(id + " states no target state — a transition entry is the entity in full");
        continue;
      }
      if (target.archetype() == null) {
        refused.add(id + " states no archetype, and the target archetype is what it is judged as");
        continue;
      }
      stated.put(id, target);
    }
    if (!refused.isEmpty()) {
      throw new BadRequestException(String.join("; ", refused));
    }
    return stated;
  }

  // --- the transaction ------------------------------------------------------

  /** Validate the whole post-state, then write it. One transaction, so nothing moves in between. */
  private Map<String, TransitionedEntity> apply(
      Map<String, EntityTransition> stated, String changedBy) {

    Map<String, WorkEntity> rows = index(entities.listByIds(stated.keySet()));
    Map<String, WorkEntity> statedParents = parentRows(stated, rows);

    List<String> violations = validate(stated, rows, statedParents);
    if (!violations.isEmpty()) {
      throw new BadRequestException(String.join("; ", violations));
    }

    for (Map.Entry<String, EntityTransition> entry : stated.entrySet()) {
      write(rows.get(entry.getKey()), entry.getValue(), scopeOf(entry.getValue(), rows.get(entry.getKey())));
    }
    replaceMemberships(stated);

    // Flushed so the edges and the managed timestamps are settled before anything is read back.
    entities.getEntityManager().flush();

    Map<String, EntityMembership> edges = edgesOf(stated.keySet());
    Map<String, TransitionedEntity> written = new LinkedHashMap<>();
    for (String id : stated.keySet()) {
      WorkEntity row = rows.get(id);
      written.put(id, TransitionedEntity.of(row, edges.get(id)));
    }

    for (Map.Entry<String, TransitionedEntity> entry : written.entrySet()) {
      WorkEntity row = rows.get(entry.getKey());
      auditService.record(
          AuditEntityType.of(row.archetype),
          row.id,
          subtreeRootOf(row.id, stated),
          AuditOperation.UPDATE,
          changedBy,
          row);
    }
    // Collections.unmodifiableMap and NOT Map.copyOf: the caller's order is this operation's
    // contract — it decides the order violations are reported in, the order two entries claiming
    // one position resolve in, and the order the batch is announced in — and Map.copyOf answers an
    // ImmutableCollections.MapN whose iteration order is a hash order perturbed by a per-JVM salt
    // (ImmutableCollections.SALT32L, seeded from System.nanoTime() at class init). It is therefore
    // not merely "some other order" but a DIFFERENT order in different runs of the same code,
    // which is what made a batch of two come back reversed in one JVM and forward in the next.
    // `written` is local to this method and escapes only through this wrapper, so the view cannot
    // be written behind a caller's back.
    return Collections.unmodifiableMap(written);
  }

  // --- validation -----------------------------------------------------------

  /**
   * Every complaint about the post-state, from all three layers, in a stable order: the ids that
   * name nothing, then each entry's own properties in the caller's order, then the slug scopes, then
   * the tree. A caller gets all of it in one 400 and fixes it in one round trip.
   */
  private List<String> validate(
      Map<String, EntityTransition> stated,
      Map<String, WorkEntity> rows,
      Map<String, WorkEntity> statedParents) {

    List<String> violations = new ArrayList<>();

    for (String id : stated.keySet()) {
      if (!rows.containsKey(id)) {
        violations.add(
            "there is no " + id + " to transition — a transition moves what exists and creates"
                + " nothing");
      }
    }

    for (Map.Entry<String, EntityTransition> entry : stated.entrySet()) {
      WorkEntity row = rows.get(entry.getKey());
      if (row == null) {
        continue;
      }
      violations.addAll(propertyViolations(entry.getValue(), row));
      violations.addAll(projectViolations(entry.getKey(), entry.getValue(), rows, statedParents));
    }

    violations.addAll(slugViolations(stated, rows));

    List<EntityFact> postState = new ArrayList<>();
    for (Map.Entry<String, EntityTransition> entry : stated.entrySet()) {
      if (rows.containsKey(entry.getKey())) {
        postState.add(
            new EntityFact(entry.getKey(), entry.getValue().archetype(), entry.getValue().parent()));
      }
    }
    for (NestingViolation violation : Nesting.check(postState, facts)) {
      violations.add(violation.message());
    }

    return violations;
  }

  /**
   * <b>Layer one: the entry against its target archetype.</b> The candidate state is what the caller
   * stated plus the two server-owned properties, carried for the reason {@link EntityTransition}
   * gives: a caller could not have written either, so neither may be a {@code NOT_PERMITTED}
   * complaint and neither is cleared merely by not being mentioned.
   */
  private static List<String> propertyViolations(EntityTransition target, WorkEntity row) {
    Archetype archetype = target.archetype();
    ArchetypeSpec spec = Archetypes.spec(archetype);

    EnumSet<EntityProperty> present = EnumSet.noneOf(EntityProperty.class);
    add(present, EntityProperty.TITLE, target.title());
    add(present, EntityProperty.DESCRIPTION, target.description());
    add(present, EntityProperty.TICKET_TYPE, target.ticketType());
    add(present, EntityProperty.IMPETUS, target.impetus());
    add(present, EntityProperty.ASSIGNEE, target.assignee());
    add(present, EntityProperty.SUPERSEDED_BY, target.supersededBy());
    add(present, EntityProperty.REPOSITORY_ID, target.repositoryId());
    add(present, EntityProperty.IMPLEMENTED_AT, target.implementedAt());
    add(present, EntityProperty.DEPENDS_ON, target.dependsOn());

    // The SERVER_OWNED pair, read off the constant rather than named here, so the list this check
    // carries and the list the registry document serves are the same list. The two travel
    // differently and the switch is that difference: the slug travels with the row and is permitted
    // everywhere; createdBy travels only where the target has a slot for it, and is cleared rather
    // than refused where it has not. A third server-owned property added to the constant and to
    // nothing else throws here by name rather than being carried by accident.
    for (EntityProperty owned : SERVER_OWNED) {
      add(
          present,
          owned,
          switch (owned) {
            case SLUG -> row.slug;
            case CREATED_BY -> spec.permits(EntityProperty.CREATED_BY) ? row.createdBy : null;
            default ->
                throw new IllegalStateException(
                    "SERVER_OWNED names " + owned + " and nothing here says how it travels");
          });
    }

    List<String> refused = new ArrayList<>();
    for (ArchetypeViolation violation :
        Archetypes.validate(new EntityState(archetype, target.status(), present))) {
      if (!ImpetusConcession.theImpetusTheColumnStillAllowsToBeAbsent(violation)) {
        refused.add(violation.message());
      }
    }

    // The transition's own rule — see the class javadoc and requiresStatusOnTransition. Stated in
    // the registry's vocabulary so a caller reads one kind of sentence, but it is this operation's
    // rule and not the registry's.
    if (requiresStatusOnTransition(spec) && blankToNull(target.status()) == null) {
      refused.add(
          new ArchetypeViolation(
                  archetype,
                  EntityProperty.STATUS,
                  ArchetypeViolation.Reason.MISSING_REQUIRED,
                  null)
              .message());
    }
    return refused;
  }

  /**
   * <b>Whether a transition entry for this archetype must state a status.</b> True exactly when the
   * kind declares any status words — so both ends of the epic/ticket asymmetry come out right
   * without either being named.
   *
   * <p><b>It is the transition's rule and not the registry's</b>, which is why it is a method here
   * rather than a field on {@link ArchetypeSpec}. {@code Archetypes} declares {@code STATUS} merely
   * <em>permitted</em> on an {@code EPIC} because an epic's first status is minted by {@code
   * EpicService.create} and requiring it would fail every create before the writer had run. A
   * transition mints nothing, so under the PUT rule an omitted status would <em>clear</em> one and
   * leave a status-less epic {@code EpicLifecycle.parse} cannot read.
   *
   * <p>It is public and named because it has two readers that must not drift: {@link
   * #propertyViolations}, which enforces it after the press, and {@code ArchetypeRegistryDocument},
   * which serves it so a form can gather the status before the press. Two spellings of {@code
   * !spec.legalStatuses().isEmpty()} would be two rules the day either end changed.
   *
   * @param spec the <b>target</b> archetype's declaration — what the entity becomes, never what it
   *     was
   */
  public static boolean requiresStatusOnTransition(ArchetypeSpec spec) {
    return !spec.legalStatuses().isEmpty();
  }

  /**
   * <b>A transition does not move work between projects.</b> Every row carries its project and a
   * descendant's is copied from its parent at create, so a cross-project reparent would either leave
   * a stale {@code project_id} or need a cascade into entities the request never mentioned. Neither
   * is something a caller could have meant by "put this under that", so it is refused and named.
   */
  private static List<String> projectViolations(
      String id,
      EntityTransition target,
      Map<String, WorkEntity> rows,
      Map<String, WorkEntity> statedParents) {
    String parentId = target.parent();
    if (parentId == null) {
      return List.of();
    }
    WorkEntity parent = rows.containsKey(parentId) ? rows.get(parentId) : statedParents.get(parentId);
    WorkEntity row = rows.get(id);
    if (parent == null || row == null || Objects.equals(parent.projectId, row.projectId)) {
      return List.of();
    }
    return List.of(
        id
            + " is in project "
            + row.projectId
            + " and "
            + parentId
            + " is in project "
            + parent.projectId
            + " — a transition does not move work between projects");
  }

  /**
   * <b>Layer two: the slug scope.</b> A slug is immutable and a move changes what it is unique
   * within, so the question is whether the post-state has two rows holding one slug in one scope.
   *
   * <p>The occupancy is computed from the map <em>and</em> the store: every stored row in an
   * affected scope counts, except one that is itself in the map and therefore about to be re-placed.
   * That last exclusion is what makes two siblings swapping parents legal rather than a collision
   * against their own former selves.
   */
  private List<String> slugViolations(
      Map<String, EntityTransition> stated, Map<String, WorkEntity> rows) {

    Map<String, String> newScopes = new LinkedHashMap<>();
    for (Map.Entry<String, EntityTransition> entry : stated.entrySet()) {
      WorkEntity row = rows.get(entry.getKey());
      if (row != null) {
        newScopes.put(entry.getKey(), scopeOf(entry.getValue(), row));
      }
    }

    // scope -> slug -> the id holding it in the post-state
    Map<String, Map<String, String>> occupancy = new LinkedHashMap<>();
    for (WorkEntity resident : entities.listBySlugScopes(new LinkedHashSet<>(newScopes.values()))) {
      if (stated.containsKey(resident.id)) {
        continue; // it is being re-placed by this very request
      }
      occupancy
          .computeIfAbsent(resident.slugScope, scope -> new LinkedHashMap<>())
          .put(resident.slug, resident.id);
    }

    List<String> refused = new ArrayList<>();
    for (Map.Entry<String, String> moved : newScopes.entrySet()) {
      String id = moved.getKey();
      String scope = moved.getValue();
      String slug = rows.get(id).slug;
      String holder =
          occupancy.computeIfAbsent(scope, ignored -> new LinkedHashMap<>()).putIfAbsent(slug, id);
      if (holder != null) {
        refused.add(
            "the slug "
                + slug
                + " is already taken by "
                + holder
                + " under "
                + scope
                + " — a move keeps its slug, so "
                + id
                + " cannot become part of "
                + scope);
      }
    }
    return refused;
  }

  // --- the write ------------------------------------------------------------

  /**
   * The row as the caller stated it. <b>A PUT: an absent property is cleared</b>, which is the whole
   * difference between this and the four partial updates — see {@link EntityTransition}.
   *
   * <p>{@code slug} is not touched at all ({@code @Column(updatable = false)} makes that a schema
   * fact rather than a convention), {@code projectId} is carried, and {@code createdBy} is carried
   * or cleared depending on whether the target archetype has a slot for it.
   */
  private static void write(WorkEntity row, EntityTransition target, String slugScope) {
    row.archetype = target.archetype();
    row.title = target.title();
    row.description = blankToNull(target.description());
    row.status = blankToNull(target.status());
    row.ticketType = target.ticketType();
    row.impetus = blankToNull(target.impetus());
    row.assignee = blankToNull(target.assignee());
    row.supersededByEntityId = blankToNull(target.supersededBy());
    row.repositoryId = blankToNull(target.repositoryId());
    row.implementedAt = target.implementedAt();
    row.dependsOnEntityId = blankToNull(target.dependsOn());
    if (!Archetypes.spec(target.archetype()).permits(EntityProperty.CREATED_BY)) {
      row.createdBy = null;
    }
    row.slugScope = slugScope;
  }

  /**
   * <b>The edges, rewritten so both the old and the new parent end dense and zero-based.</b>
   *
   * <p>Everything is read before anything is mutated, and the renumber is one pass per affected
   * parent. The alternative — {@code closeGapAfter} per departing child, the idiom a single-entity
   * delete uses — cannot be right here: several children leaving one parent in a single request
   * would each compute their gap from positions a previous close had already moved.
   *
   * <p><b>An edge's id is the child's</b>, V10's rule, so a reparent is an {@code UPDATE} of the one
   * edge a child can have rather than a delete and an insert. A child becoming a root loses its edge
   * outright: a root <em>has</em> no membership, which is a statement and not an absence.
   */
  private void replaceMemberships(Map<String, EntityTransition> stated) {
    Map<String, EntityMembership> existing = edgesOf(stated.keySet());

    Set<String> affected = new LinkedHashSet<>();
    for (EntityMembership edge : existing.values()) {
      affected.add(edge.parentId);
    }
    for (EntityTransition target : stated.values()) {
      if (target.parent() != null) {
        affected.add(target.parent());
      }
    }

    // The surviving children of every affected parent, in position order, read before any mutation.
    Map<String, List<EntityMembership>> ordered = new LinkedHashMap<>();
    for (String parentId : affected) {
      ordered.put(parentId, new ArrayList<>());
    }
    for (EntityMembership edge : memberships.childrenOfAll(affected)) {
      if (!stated.containsKey(edge.childId)) {
        ordered.get(edge.parentId).add(edge);
      }
    }

    for (Map.Entry<String, EntityTransition> entry : stated.entrySet()) {
      String id = entry.getKey();
      String parentId = entry.getValue().parent();
      EntityMembership edge = existing.get(id);

      if (parentId == null) {
        if (edge != null) {
          memberships.delete(edge);
        }
        continue;
      }
      if (edge == null) {
        edge = new EntityMembership();
        edge.id = id;
        edge.childId = id;
        edge.parentId = parentId;
        edge.position = 0;
        memberships.persist(edge);
      } else {
        edge.parentId = parentId;
      }

      List<EntityMembership> siblings = ordered.get(parentId);
      Integer position = entry.getValue().position();
      if (position == null) {
        siblings.add(edge);
      } else {
        // Clamped rather than refused: a caller stating 99 means "last", and making it count the
        // siblings first would be a round trip bought for nothing.
        siblings.add(Math.clamp(position.longValue(), 0, siblings.size()), edge);
      }
    }

    for (List<EntityMembership> siblings : ordered.values()) {
      for (int index = 0; index < siblings.size(); index++) {
        siblings.get(index).position = index;
      }
    }
  }

  // --- plumbing -------------------------------------------------------------

  /**
   * What a slug is unique within after the move: the parent's id for a child, the project's for a
   * root. {@code WorkEntity.slugScope}'s rule, applied to the post-state.
   */
  private static String scopeOf(EntityTransition target, WorkEntity row) {
    String parentId = target.parent();
    return parentId == null ? row.projectId : parentId;
  }

  /**
   * The stored rows of parents the request names but does not itself state — needed for the
   * cross-project check, which is about a parent this transition is not otherwise reading.
   */
  private Map<String, WorkEntity> parentRows(
      Map<String, EntityTransition> stated, Map<String, WorkEntity> rows) {
    Set<String> wanted = new LinkedHashSet<>();
    for (EntityTransition target : stated.values()) {
      String parentId = target.parent();
      if (parentId != null && !rows.containsKey(parentId)) {
        wanted.add(parentId);
      }
    }
    return index(entities.listByIds(wanted));
  }

  /** The one edge above each of {@code ids}, keyed by child; a root is simply absent. */
  private Map<String, EntityMembership> edgesOf(Collection<String> ids) {
    Map<String, EntityMembership> edges = new HashMap<>();
    for (EntityMembership edge : memberships.membershipsOfAll(ids)) {
      edges.put(edge.childId, edge);
    }
    return edges;
  }

  /**
   * <b>The subtree key an audit row carries</b>: the root of the tree this entity is in after the
   * transition. That is the epic's id for an {@code EPIC}, a {@code FEATURE} or a {@code TASK}, and
   * the ticket's own id for a {@code TICKET} — the rule {@code AuditEntry.epicId} already states,
   * read off the post-state rather than off the rows the request left behind.
   *
   * <p>The walk is over the stated parents first and the stored edges second, and it is bounded by
   * the depth of a tree rather than by its size. A cycle cannot reach here — {@code Nesting} has
   * already refused one — and the visited set is there so a bug reports a root rather than hanging.
   */
  private String subtreeRootOf(String id, Map<String, EntityTransition> stated) {
    Set<String> seen = new HashSet<>();
    String cursor = id;
    while (seen.add(cursor)) {
      String parentId;
      EntityTransition target = stated.get(cursor);
      if (target != null) {
        parentId = target.parent();
      } else {
        parentId = memberships.membershipOf(cursor).map(edge -> edge.parentId).orElse(null);
      }
      if (parentId == null) {
        return cursor;
      }
      cursor = parentId;
    }
    return cursor;
  }

  /** One call for the batch, never one per entity — see {@link TransitionAnnouncer}. */
  private void announce(Map<String, TransitionedEntity> written) {
    if (announcer.isUnsatisfied()) {
      return;
    }
    announcer.get().onEntitiesTransitioned(List.copyOf(written.values()), Instant.now());
  }

  private static Map<String, WorkEntity> index(List<WorkEntity> rows) {
    Map<String, WorkEntity> indexed = new LinkedHashMap<>();
    for (WorkEntity row : rows) {
      indexed.put(row.id, row);
    }
    return indexed;
  }

  /**
   * A blank string counts as absent, the reading {@code Validations.requireText} and {@code
   * EntityState.of} already apply everywhere in this module: a title of spaces is a missing title.
   */
  private static void add(Set<EntityProperty> into, EntityProperty property, Object value) {
    if (value == null || (value instanceof String text && text.isBlank())) {
      return;
    }
    into.add(property);
  }

  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }
}
