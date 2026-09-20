package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
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
 * CRUD for the {@link Archetype#TASK} rows of the merged {@link WorkEntity} table — {@link
 * FeatureService} one level down, and deliberately its mirror rather than a variation on it. The
 * parent feature is validated against that same table (404 if absent or of another kind); {@code
 * repositoryId} is stored verbatim — cross-boundary existence (and project match) against {@code
 * domain}'s {@code Repository} is validated in the {@code service} controller. {@code
 * dependsOnTaskId}, when set, must reference an existing task <em>under the same parent</em> (400
 * otherwise), may not point at the task itself, and may not close a cycle. Updates are partial (null
 * field = leave unchanged) with explicit clear flags for the dependency and completion marker. Every
 * mutation — including dependents cleared when a depended-on task is deleted — is audited.
 *
 * <p>Every write here obeys the phase of the epic above the task's feature ({@link EpicLifecycle}):
 * a task is scope, so creating, deleting or structurally editing one needs a draft, while {@code
 * implementedAt} moves only once that scope is frozen.
 *
 * <h2>The epic is TWO membership hops up, and it is resolved once per call</h2>
 *
 * <p>{@code task.feature_id} and {@code feature.epic_id} are both {@link EntityMembership} rows now,
 * so "the epic this task belongs to" is task → feature → epic. {@link #epicIdOf} is that walk and
 * every method makes it <b>once</b>, passing the answer down to the phase guard and to every audit
 * row it writes — re-walking per audit row would be two queries a piece for a fact that cannot have
 * changed inside one transaction.
 *
 * <p>What a caller gets back is a {@link Nested} — the merged row and, beside it, the feature its
 * membership edge names — which is {@link FeatureService}'s answer shape one level down and carries
 * its reasoning word for word. It used to be a detached {@code Task}-shaped projection; that class
 * is gone and {@code WorkEntityMapper.toTaskDto(entity, featureId)} takes the two halves, with
 * {@code TaskDto} unmoved. The slug scope is the <b>parent feature</b>, positions on the edges stay
 * dense and zero-based, and the legacy {@code task} table is dropped outright in V13.
 */
@ApplicationScoped
public class TaskService {

  @Inject WorkEntityRepository entities;

  @Inject EntityMembershipRepository memberships;

  /** The nesting rule's view of the two tables — see {@link #attach}. */
  @Inject StoredEntityFacts facts;

  @Inject AuditService auditService;

  @Inject ReadPatience patience;

  @Inject WritePatience writes;

  /** The per-project numeric id every created row takes; see {@link EntityNumbers}. */
  @Inject EntityNumbers numbers;

  /**
   * The feature's tasks, in membership position order, held through a postgres cutover ({@link
   * ReadPatience}). Same placement and same two-query shape as {@link FeatureService#listByEpic}:
   * the wrap is on the read path only, and the identical repository calls inside this module's
   * writes are left alone.
   */
  public List<Nested> listByFeature(String featureId) {
    return patience.hold(
        "task list",
        () -> {
          List<EntityMembership> edges = memberships.childrenOf(featureId);
          Map<String, WorkEntity> rows = byId(entities.listByIds(childIds(edges)));
          List<Nested> tasks = new ArrayList<>();
          for (EntityMembership edge : edges) {
            WorkEntity row = rows.get(edge.childId);
            if (row != null && row.archetype == Archetype.TASK) {
              tasks.add(new Nested(row, featureId));
            }
          }
          return List.copyOf(tasks);
        });
  }

  public Nested get(String id) {
    WorkEntity row = entity(id);
    return new Nested(row, parentOf(id));
  }

  /** A new task, held through a postgres cutover ({@link WritePatience}). */
  public Nested create(
      String featureId,
      String repositoryId,
      String title,
      String description,
      String dependsOnTaskId,
      String changedBy) {
    Validations.requireText(title, "title");
    Validations.requireText(repositoryId, "repositoryId");
    return writes.hold(
        "task create",
        () -> {
          WorkEntity featureRow = feature(featureId);
          String epicId = parentOf(featureId);
          EpicLifecycle.requireRefining(epic(epicId));
          if (dependsOnTaskId != null) {
            requireDependencyUnder(dependsOnTaskId, featureId);
          }
          WorkEntity row = new WorkEntity();
          row.id = UUID.randomUUID().toString();
          row.archetype = Archetype.TASK;
          // Carried on the row rather than walked up to, like every other entity row.
          row.projectId = featureRow.projectId;
          // Per PROJECT, not per feature — the same run of integers the epic above it draws from.
          row.number = numbers.next(row.projectId);
          row.repositoryId = repositoryId;
          row.title = title;
          // The scope is the PARENT — uq_entity_slug_scope_slug's reading of uq_task_feature_slug.
          row.slugScope = featureId;
          // Minted once, at create, and never re-derived on update — see Epic.slug.
          row.slug =
              Slugs.unique(Slugs.slugify(title, row.id, "task-"), entities.slugsInScope(featureId));
          row.description = description;
          row.dependsOnEntityId = dependsOnTaskId;
          requireArchetypeValid(row, Demand.AT_CREATE);
          entities.persist(row);
          attach(featureId, row);
          Nested task = settled(row, featureId);
          auditService.record(
              AuditEntityType.TASK, row.id, epicId, AuditOperation.CREATE, changedBy, row);
          return task;
        });
  }

  /**
   * Partial update. The freeze is applied per field: whatever this call touches must be allowed by
   * the epic's phase, so a call supplying both kinds always fails — no status allows both.
   *
   * <p>Held through a postgres cutover ({@link WritePatience}).
   */
  public Nested update(
      String id,
      String title,
      String description,
      String dependsOnTaskId,
      boolean clearDependsOn,
      Instant implementedAt,
      boolean clearImplementedAt,
      String changedBy) {
    return writes.hold(
        "task update",
        () -> {
          WorkEntity row = entity(id);
          String featureId = parentOf(id);
          boolean touchesMarker = implementedAt != null || clearImplementedAt;
          // An edit that supplies nothing at all is counted as structural: it is the scope endpoint.
          boolean touchesScope =
              title != null
                  || description != null
                  || dependsOnTaskId != null
                  || clearDependsOn
                  || !touchesMarker;
          String epicId = epicIdOf(featureId);
          WorkEntity epic = epic(epicId);
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
          } else if (dependsOnTaskId != null) {
            if (dependsOnTaskId.equals(id)) {
              throw new BadRequestException("A task cannot depend on itself");
            }
            requireDependencyUnder(dependsOnTaskId, featureId);
            requireNoCycle(id, dependsOnTaskId);
            row.dependsOnEntityId = dependsOnTaskId;
          }
          if (clearImplementedAt) {
            row.implementedAt = null;
          } else if (implementedAt != null) {
            row.implementedAt = implementedAt;
          }
          requireArchetypeValid(row, Demand.ON_UPDATE);
          Nested task = settled(row, featureId);
          auditService.record(
              AuditEntityType.TASK, row.id, epicId, AuditOperation.UPDATE, changedBy, row);
          return task;
        });
  }

  /**
   * Removes a task and clears its dependents' pointers, held through a cutover ({@link
   * WritePatience}) — one transaction, so a retry never leaves half the cleanup behind. The gap the
   * removed edge leaves is closed, so its surviving siblings stay dense.
   */
  public void delete(String id, String changedBy) {
    writes.run(
        "task delete",
        () -> {
          WorkEntity row = entity(id);
          EntityMembership edge = memberships.membershipOf(id).orElse(null);
          String featureId = edge == null ? null : edge.parentId;
          String epicId = epicIdOf(featureId);
          EpicLifecycle.requireRefining(epic(epicId));

          // A dependency is scoped to the feature (validated on write), so every dependent is a
          // sibling and shares this task's epic — the walk is not made again for each of them.
          for (WorkEntity dependent : entities.listDependents(id)) {
            if (dependent.archetype != Archetype.TASK) {
              continue;
            }
            dependent.dependsOnEntityId = null;
            auditService.record(
                AuditEntityType.TASK,
                dependent.id,
                epicId,
                AuditOperation.UPDATE,
                changedBy,
                dependent);
          }

          if (edge != null) {
            int gone = edge.position;
            memberships.delete(edge);
            memberships.closeGapAfter(featureId, gone);
          }
          entities.delete(row);
          auditService.record(
              AuditEntityType.TASK, id, epicId, AuditOperation.DELETE, changedBy, row);
        });
  }

  // --- plumbing -------------------------------------------------------------

  /** The row this id names, or a 404 — and a row of another archetype is a 404 too. */
  private WorkEntity entity(String id) {
    WorkEntity row = id == null ? null : entities.findById(id);
    if (row == null || row.archetype != Archetype.TASK) {
      throw new NotFoundException("Task not found: " + id);
    }
    return row;
  }

  /** The parent feature row, or a 404 spelled exactly as it was. */
  private WorkEntity feature(String featureId) {
    WorkEntity row = featureId == null ? null : entities.findById(featureId);
    if (row == null || row.archetype != Archetype.FEATURE) {
      throw new NotFoundException("Feature not found: " + featureId);
    }
    return row;
  }

  /** The epic above a feature — the row whose phase decides what may be written here. */
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
   * <b>The second hop</b>: the epic above a task's feature. Called once per service method and the
   * answer passed down — see the class javadoc.
   */
  private String epicIdOf(String featureId) {
    return featureId == null ? null : parentOf(featureId);
  }

  /** {@link FeatureService#attach}'s rule one level down, and the same two sentences apply. */
  private void attach(String parentId, WorkEntity row) {
    EntityMembership edge = new EntityMembership();
    edge.id = row.id;
    edge.parentId = parentId;
    edge.childId = row.id;
    edge.position = memberships.maxPosition(parentId) + 1;
    memberships.persist(edge);
    requireNestingValid(new EntityFact(row.id, row.archetype, parentId));
  }

  private void requireDependencyUnder(String taskId, String featureId) {
    WorkEntity dependency = entities.findById(taskId);
    if (dependency == null
        || dependency.archetype != Archetype.TASK
        || !Objects.equals(featureId, parentOf(taskId))) {
      throw new BadRequestException("Unknown or out-of-feature dependsOnTaskId: " + taskId);
    }
  }

  /** The dependency chain, walked over {@code depends_on_entity_id} and never over a membership. */
  private void requireNoCycle(String taskId, String targetId) {
    Set<String> visited = new HashSet<>();
    String cursor = targetId;
    while (cursor != null) {
      if (cursor.equals(taskId)) {
        throw new BadRequestException("dependsOnTaskId would create a dependency cycle");
      }
      if (!visited.add(cursor)) {
        break;
      }
      WorkEntity next = entities.findById(cursor);
      cursor = (next == null) ? null : next.dependsOnEntityId;
    }
  }

  /** The row as a caller sees it, taken after an explicit flush — see {@code EpicService.settled}. */
  private Nested settled(WorkEntity row, String featureId) {
    entities.getEntityManager().flush();
    return new Nested(row, featureId);
  }

  /** <b>The archetype registry on the ordinary write</b> — {@link FeatureService}'s rule, unchanged. */
  private static void requireArchetypeValid(WorkEntity candidate, Demand demand) {
    List<ArchetypeViolation> violations = Archetypes.validate(candidate, demand);
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
