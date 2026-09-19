package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.Archetype;
import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.EntityMembership;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.entity.Task;
import eu.wohlben.qits.epics.entity.WorkEntity;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.NotFoundException;
import eu.wohlben.qits.epics.persistence.EntityMembershipRepository;
import eu.wohlben.qits.epics.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD for the {@link Archetype#FEATURE} rows of the merged {@link WorkEntity} table. The parent
 * epic is validated against that same table (404 if absent or of another kind); {@code
 * dependsOnFeatureId}, when set, must reference an existing feature <em>under the same parent</em>
 * (400 otherwise), may not point at the feature itself, and may not close a dependency cycle.
 * Updates are partial (null field = leave unchanged); the two nullable relations use explicit clear
 * flags so a partial edit can't silently drop a dependency or ship date. Every mutation — including
 * the tasks removed on cascade delete and the dependents cleared when a depended-on feature is
 * deleted — is recorded in the {@link AuditService audit log}.
 *
 * <p>Every write here obeys the owning epic's phase ({@link EpicLifecycle}): a feature is scope, so
 * creating, deleting or structurally editing one needs a draft, while {@code implementedOn} moves
 * only once that scope is frozen.
 *
 * <h2>The merged table is the source of truth, and the parent is a row of its own</h2>
 *
 * <p><b>Every read and every rule here is answered from {@code entity} and {@code
 * entity_membership}</b>, exactly as {@code EpicService}'s are. What a caller gets back is a {@link
 * WorkEntityProjections detached projection} shaped as a {@link Feature}, so {@code
 * FeatureController}, {@code FeatureMapper}, {@code FeatureDto}, {@code EpicController}, {@code
 * EpicChangeHints}, {@code EpicMcpTools} and {@code EpicDispatchController} are untouched and every
 * route, status code and error body is what it was.
 *
 * <p>The old {@code feature.epic_id} column is an {@link EntityMembership} row now, and three things
 * follow from that and are rules rather than details:
 *
 * <ul>
 *   <li><b>"The same epic" is "the same parent".</b> The dependency scope check is one membership
 *       lookup, not a column comparison, and its message is unchanged.
 *   <li><b>The listing is in MEMBERSHIP POSITION ORDER</b>, which is what {@code position} is for.
 *       {@code WorkEntityRepository.listByIds} answers oldest-first, so the rows are indexed by id
 *       and re-emitted in the edges' order — <b>two queries, never one per row</b>.
 *   <li><b>Positions stay dense and zero-based.</b> A create appends at {@code maxPosition + 1} and
 *       a delete closes the gap it leaves, {@code DossierService}'s idiom applied to the edge table.
 * </ul>
 *
 * <p><b>Nothing here writes the legacy {@code feature} table any more, and there is deliberately no
 * write-behind mirror.</b> {@code EpicService} and {@code TicketService} keep one because live
 * foreign keys and out-of-scope readers still name {@code epic} and {@code ticket}; nothing on this
 * platform foreign-keys to {@code feature} or to {@code task}, and with {@code EpicService}'s subtree
 * walks moved onto the memberships nothing reads those rows either. A half-live table is the worst of
 * both, so the write simply stops. The table itself is left exactly where it is — it is the recovery
 * path and the verification door's comparison target.
 */
@ApplicationScoped
public class FeatureService {

  @Inject WorkEntityRepository entities;

  @Inject EntityMembershipRepository memberships;

  /** The nesting rule's view of the two tables — see {@link #attach}. */
  @Inject StoredEntityFacts facts;

  @Inject AuditService auditService;

  @Inject ReadPatience patience;

  @Inject WritePatience writes;

  /**
   * The epic's features, in membership position order, held through a postgres cutover ({@link
   * ReadPatience}). The caller is a plain GET with no transaction of its own; the same repository
   * calls inside this module's writes go to the repositories directly and stay unwrapped, because a
   * retry inside an open transaction re-runs on a rollback-only connection.
   *
   * <p><b>Two queries whatever the size of the epic.</b> The edges come back in order and the rows
   * come back oldest-first, so the rows are indexed and re-emitted in the edges' order rather than
   * being fetched one at a time.
   */
  public List<Feature> listByEpic(String epicId) {
    return patience.hold(
        "feature list",
        () -> {
          List<EntityMembership> edges = memberships.childrenOf(epicId);
          Map<String, WorkEntity> rows = byId(entities.listByIds(childIds(edges)));
          List<Feature> features = new ArrayList<>();
          for (EntityMembership edge : edges) {
            WorkEntity row = rows.get(edge.childId);
            if (row != null && row.archetype == Archetype.FEATURE) {
              features.add(WorkEntityProjections.feature(row, epicId));
            }
          }
          return List.copyOf(features);
        });
  }

  public Feature get(String id) {
    WorkEntity row = entity(id);
    return WorkEntityProjections.feature(row, parentOf(id));
  }

  /** A new feature, held through a postgres cutover ({@link WritePatience}). */
  public Feature create(
      String epicId,
      String title,
      String description,
      String dependsOnFeatureId,
      String changedBy) {
    Validations.requireText(title, "title");
    return writes.hold(
        "feature create",
        () -> {
          WorkEntity epicRow = epic(epicId);
          // The phase is read off the entity row, projected only so the rule keeps the signature
          // its other callers use.
          EpicLifecycle.requireRefining(WorkEntityProjections.epic(epicRow));
          if (dependsOnFeatureId != null) {
            requireDependencyUnder(dependsOnFeatureId, epicId);
          }
          WorkEntity row = new WorkEntity();
          row.id = UUID.randomUUID().toString();
          row.archetype = Archetype.FEATURE;
          // On every row, root and descendant alike — no walk up to answer "whose is this".
          row.projectId = epicRow.projectId;
          row.title = title;
          // The scope is the PARENT, which is what uq_entity_slug_scope_slug makes of
          // uq_feature_epic_slug: the same rule, written once.
          row.slugScope = epicId;
          // Minted once, at create, and never re-derived on update — see Epic.slug.
          row.slug =
              Slugs.unique(
                  Slugs.slugify(title, row.id, "feature-"), entities.slugsInScope(epicId));
          row.description = description;
          row.dependsOnEntityId = dependsOnFeatureId;
          requireArchetypeValid(row);
          entities.persist(row);
          attach(epicId, row);
          Feature feature = settled(row, epicId);
          auditService.record(
              AuditEntityType.FEATURE,
              feature.id,
              epicId,
              AuditOperation.CREATE,
              changedBy,
              feature);
          return feature;
        });
  }

  /**
   * Partial update. A null {@code title}/{@code description} leaves that field unchanged; the
   * dependency and ship-date are changed only via their explicit value/clear flag pair.
   *
   * <p>The freeze is applied per field: whatever this call touches must be allowed by the epic's
   * phase. A call that supplies both kinds therefore always fails — no status allows both.
   *
   * <p>The epic is one membership hop away, and it is resolved once.
   *
   * <p>Held through a postgres cutover ({@link WritePatience}).
   */
  public Feature update(
      String id,
      String title,
      String description,
      String dependsOnFeatureId,
      boolean clearDependsOn,
      Instant implementedOn,
      boolean clearImplementedOn,
      String changedBy) {
    return writes.hold(
        "feature update",
        () -> {
          WorkEntity row = entity(id);
          String epicId = parentOf(id);
          boolean touchesMarker = implementedOn != null || clearImplementedOn;
          // An edit that supplies nothing at all is counted as structural: it is the scope endpoint.
          boolean touchesScope =
              title != null
                  || description != null
                  || dependsOnFeatureId != null
                  || clearDependsOn
                  || !touchesMarker;
          Epic epic = WorkEntityProjections.epic(epic(epicId));
          if (touchesScope) {
            EpicLifecycle.requireRefining(epic);
          }
          if (touchesMarker) {
            EpicLifecycle.requireImplementation(epic);
          }
          if (title != null) {
            Validations.requireText(title, "title");
            row.title = title;
          }
          if (description != null) {
            row.description = description;
          }
          if (clearDependsOn) {
            row.dependsOnEntityId = null;
          } else if (dependsOnFeatureId != null) {
            if (dependsOnFeatureId.equals(id)) {
              throw new BadRequestException("A feature cannot depend on itself");
            }
            requireDependencyUnder(dependsOnFeatureId, epicId);
            requireNoCycle(id, dependsOnFeatureId);
            row.dependsOnEntityId = dependsOnFeatureId;
          }
          if (clearImplementedOn) {
            row.implementedAt = null;
          } else if (implementedOn != null) {
            row.implementedAt = implementedOn;
          }
          requireArchetypeValid(row);
          Feature feature = settled(row, epicId);
          auditService.record(
              AuditEntityType.FEATURE,
              feature.id,
              epicId,
              AuditOperation.UPDATE,
              changedBy,
              feature);
          return feature;
        });
  }

  /**
   * Removes a feature, its tasks and its dependents' pointers, held through a cutover ({@link
   * WritePatience}) — one transaction, so a retry never leaves half a cascade behind.
   *
   * <p>The gap the removed edge leaves is closed, so the surviving siblings keep a dense zero-based
   * order rather than a sparse one that happens to sort right.
   */
  public void delete(String id, String changedBy) {
    writes.run(
        "feature delete",
        () -> {
          WorkEntity row = entity(id);
          EntityMembership edge = memberships.membershipOf(id).orElse(null);
          String epicId = edge == null ? null : edge.parentId;
          EpicLifecycle.requireRefining(WorkEntityProjections.epic(epic(epicId)));
          Feature feature = WorkEntityProjections.feature(row, epicId);

          // Clear same-epic dependents' pointer in-service (audited) rather than leaning on the FK's
          // SET NULL, which would leave no trace.
          for (WorkEntity dependent : entities.listDependents(id)) {
            if (dependent.archetype != Archetype.FEATURE) {
              continue;
            }
            dependent.dependsOnEntityId = null;
            auditService.record(
                AuditEntityType.FEATURE,
                dependent.id,
                epicId,
                AuditOperation.UPDATE,
                changedBy,
                WorkEntityProjections.feature(dependent, epicId));
          }

          // Delete child tasks in-service so each gets a DELETE audit row, edge included.
          List<EntityMembership> childEdges = memberships.childrenOf(id);
          Map<String, WorkEntity> children = byId(entities.listByIds(childIds(childEdges)));
          for (EntityMembership childEdge : childEdges) {
            WorkEntity child = children.get(childEdge.childId);
            if (child == null || child.archetype != Archetype.TASK) {
              continue;
            }
            Task task = WorkEntityProjections.task(child, id);
            memberships.delete(childEdge);
            entities.delete(child);
            auditService.record(
                AuditEntityType.TASK, task.id, epicId, AuditOperation.DELETE, changedBy, task);
          }

          if (edge != null) {
            int gone = edge.position;
            memberships.delete(edge);
            memberships.closeGapAfter(epicId, gone);
          }
          entities.delete(row);
          auditService.record(
              AuditEntityType.FEATURE, id, epicId, AuditOperation.DELETE, changedBy, feature);
        });
  }

  // --- plumbing -------------------------------------------------------------

  /**
   * The row this id names, or a 404 — and a row of another archetype is a 404 too. The four kinds
   * share one table and one id space now, so "no feature with this id" has to mean "no FEATURE row
   * with this id" rather than "no row at all".
   */
  private WorkEntity entity(String id) {
    WorkEntity row = id == null ? null : entities.findById(id);
    if (row == null || row.archetype != Archetype.FEATURE) {
      throw new NotFoundException("Feature not found: " + id);
    }
    return row;
  }

  /** The owning epic row — the one whose phase decides what may be written here. */
  private WorkEntity epic(String epicId) {
    WorkEntity row = epicId == null ? null : entities.findById(epicId);
    if (row == null || row.archetype != Archetype.EPIC) {
      throw new NotFoundException("Epic not found: " + epicId);
    }
    return row;
  }

  /** What this row hangs under, or null for one that hangs under nothing. */
  private String parentOf(String childId) {
    return memberships.membershipOf(childId).map(edge -> edge.parentId).orElse(null);
  }

  /**
   * The edge that makes {@code row} part of {@code parentId}, appended at the end of the parent's
   * children.
   *
   * <p><b>The edge's id is the child's</b>, V10's rule: an edge's identity <em>is</em> the child —
   * the end of it that can only be in one — and a generated id would let a second edge for the same
   * pair exist as far as the primary key is concerned.
   *
   * <p>The new membership is then judged by {@link Nesting}, over the post-state it produces rather
   * than over the pair, because that is the only question that rule answers. <b>{@code dependsOn} is
   * never handed to it</b>: a dependency is a sibling ordering edge and containment is what {@code
   * Nesting} is about — see {@code WorkEntity.dependsOnEntityId}.
   */
  private void attach(String parentId, WorkEntity row) {
    EntityMembership edge = new EntityMembership();
    edge.id = row.id;
    edge.parentId = parentId;
    edge.childId = row.id;
    edge.position = memberships.maxPosition(parentId) + 1;
    memberships.persist(edge);
    requireNestingValid(new EntityFact(row.id, row.archetype, parentId));
  }

  private void requireDependencyUnder(String featureId, String epicId) {
    WorkEntity dependency = entities.findById(featureId);
    if (dependency == null
        || dependency.archetype != Archetype.FEATURE
        || !Objects.equals(epicId, parentOf(featureId))) {
      throw new BadRequestException("Unknown or out-of-epic dependsOnFeatureId: " + featureId);
    }
  }

  /**
   * Rejects a dependency edge that would close a cycle by walking the target's dependency chain.
   * The walk follows {@code depends_on_entity_id} on the entity rows — the merge of what were {@code
   * depends_on_feature_id} and {@code depends_on_task_id} — and never a membership.
   */
  private void requireNoCycle(String featureId, String targetId) {
    Set<String> visited = new HashSet<>();
    String cursor = targetId;
    while (cursor != null) {
      if (cursor.equals(featureId)) {
        throw new BadRequestException("dependsOnFeatureId would create a dependency cycle");
      }
      if (!visited.add(cursor)) {
        break; // pre-existing cycle elsewhere in the chain — stop rather than loop forever
      }
      WorkEntity next = entities.findById(cursor);
      cursor = (next == null) ? null : next.dependsOnEntityId;
    }
  }

  /**
   * The row as a caller sees it, taken after an explicit flush so the Hibernate-managed timestamps
   * are populated — a create is promised a {@code createdAt} and an update an {@code updatedAt} that
   * is not before it.
   */
  private Feature settled(WorkEntity row, String epicId) {
    entities.getEntityManager().flush();
    return WorkEntityProjections.feature(row, epicId);
  }

  /**
   * <b>The archetype registry on the ordinary write.</b> A candidate the registry refuses is a 400
   * naming every violation at once, which is what {@code Archetypes.validate} answers for and why it
   * returns all of them rather than the first.
   */
  private static void requireArchetypeValid(WorkEntity candidate) {
    List<ArchetypeViolation> violations = Archetypes.validate(candidate);
    if (!violations.isEmpty()) {
      throw new BadRequestException(
          violations.stream().map(ArchetypeViolation::message).collect(Collectors.joining("; ")));
    }
  }

  /** The nesting rule on a membership this service is about to stand behind. */
  private void requireNestingValid(EntityFact stated) {
    List<NestingViolation> violations = Nesting.check(List.of(stated), facts);
    if (!violations.isEmpty()) {
      throw new BadRequestException(
          violations.stream().map(NestingViolation::message).collect(Collectors.joining("; ")));
    }
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
