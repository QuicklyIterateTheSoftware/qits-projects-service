package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.EpicStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ConflictException;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD and lifecycle for the {@link Archetype#EPIC} rows of the merged {@link WorkEntity} table.
 * {@code projectId} is stored verbatim — cross-boundary existence against {@code domain}'s {@code
 * Project} is validated in the {@code service} controller (this module has no dependency on {@code
 * domain}). Every mutation is recorded in the {@link AuditService audit log}, including the
 * feature/task rows removed on a cascade delete (done in-service, not via the DB cascade, so each
 * removal gets its own DELETE audit row).
 *
 * <p>A new epic starts in {@link EpicStatus#REFINING}. From there {@link #transition} is the only
 * way the status moves, and {@link EpicLifecycle} is where both the legal moves and the freeze
 * rules live.
 *
 * <h2>The merged table is the source of truth, and there is no mirror left</h2>
 *
 * <p><b>Every read and every rule here is answered from {@code entity}, and that row is what a
 * caller gets back.</b> The status a transition is judged against, the slugs a new one is minted
 * around, the listing the board draws and the timestamps a caller is handed all come from that row
 * and from nothing else.
 *
 * <p>It used to be handed back as a detached {@code Epic}-shaped projection, so that the mappers and
 * the DTO above could stay written against the old class while the storage moved underneath them.
 * <b>The old classes are gone</b> and the projection with them: {@code WorkEntityMapper.toEpicDto}
 * reads the merged row directly and {@code EpicDto} did not move by one byte. A row that is handed
 * out is still only ever taken <em>after</em> an explicit flush — see {@link #settled} — because
 * {@code @CreationTimestamp} and {@code @UpdateTimestamp} are populated at flush and a create is
 * promised a {@code createdAt} the moment it returns.
 *
 * <p><b>The legacy {@code epic} row is not written at all any more, and the write-behind mirror that
 * kept it populated is gone.</b> It existed for two things and both have been answered rather than
 * dropped. The foreign keys that named the old table — {@code dossier_page.epic_id} and {@code
 * dossier_asset.epic_id}, under {@code ck_dossier_page_owner} — point at {@code entity(id)} from
 * epics V12, so the cascade a delete leans on now hangs off the row this class actually removes;
 * and {@code DossierService}, the one reader, resolves a page's owner and its {@code REFINING} guard
 * from the merged row.
 *
 * <p>Keeping the mirror was the alternative and it was refused on the descendants' own terms. A
 * table written by nobody's intent, purely to satisfy a constraint, is the half-live state {@code
 * docs/unified-entity-model.md} already rejected for {@code feature}/{@code task} ("There is NO
 * feature/task mirror, and that is a decision"): it looks authoritative to anyone who opens it, it
 * drifts the first time a path forgets to write it, and no reader would notice. With the write gone,
 * {@code epic} has <b>no writer and no referent</b> — a frozen snapshot of the estate as V10 found
 * it, which is a cleaner thing for the verification door to compare against than a mirror that was
 * still moving.
 *
 * <h2>The subtree walks go through {@code entity_membership}</h2>
 *
 * <p>{@link #stampImplemented}, {@link #supersede} and the cascade in {@link #delete} walk the
 * memberships and the merged table, not {@code FeatureRepository}/{@code TaskRepository}. They had
 * to: {@code FeatureService} and {@code TaskService} write only {@code entity} now, so a feature
 * created after that change has <b>no legacy row at all</b> and a walk over the old tables would
 * silently find nothing.
 *
 * <p>Every one of the three reads <b>a whole level at a time</b> — {@code childrenOfAll} plus {@code
 * listByIds} — and never one query per node. That is the single performance mistake this model makes
 * easy, and these three are the deepest reads the module has.
 */
@ApplicationScoped
public class EpicService {

  @Inject WorkEntityRepository entities;

  @Inject EntityMembershipRepository memberships;

  @Inject AuditService auditService;

  @Inject ReadPatience patience;

  @Inject WritePatience writes;

  /** The per-project numeric id every created row takes; see {@link EntityNumbers}. */
  @Inject EntityNumbers numbers;

  /**
   * The outcome of a {@link #transition}: the epic in its new status, plus the successor draft when
   * the move was to {@link EpicStatus#SUPERSEDED} (null otherwise).
   */
  public record Transition(WorkEntity epic, WorkEntity successor) {}

  /**
   * What a {@link #transition} to {@code target} would be: the epic as it stands, the status it
   * would move to, and whether that status {@linkplain EpicLifecycle#resolves resolves} it.
   *
   * <p>It exists so a caller can act <em>before</em> the move on something the epic is still
   * holding — the refinement container, in the assembling service — and still refuse an illegal
   * move first. Every rejection is the transition's own, thrown here rather than one step later: a
   * 409'd move that had already torn a refinement down would be the worse half of the bug this
   * answers. The move is then re-checked inside {@link #transition}, which is where it is decided;
   * this is a preview and never a reservation.
   */
  public record PlannedTransition(WorkEntity epic, EpicStatus target, boolean resolving) {}

  /** The preview of a move — see {@link PlannedTransition}. */
  public PlannedTransition planTransition(String id, String target) {
    Validations.requireText(target, "target");
    WorkEntity epic = entity(id);
    EpicStatus to =
        EpicLifecycle.parse(target)
            .orElseThrow(() -> new ConflictException("Unknown epic status: " + target));
    EpicLifecycle.requireTransition(EpicStatus.valueOf(epic.status), to);
    return new PlannedTransition(epic, to, EpicLifecycle.resolves(to));
  }

  public List<WorkEntity> listByProject(String projectId) {
    return listByProject(projectId, null);
  }

  /**
   * Epics of a project, optionally narrowed to one status. {@code status} is the enum name; a value
   * naming no status is a 400 rather than an empty list, so a typo in the filter is visible.
   *
   * <p>One query, whichever arm runs, and the rows are projected in memory — nothing is resolved per
   * row, which is the mistake a merged table makes easy.
   *
   * <p>The read itself is held through a postgres cutover ({@link ReadPatience}): this is the
   * board's top level, and a severed connection would draw a project with no epics in it. The
   * status is parsed before the wrap, so a typo is still a 400 on the first attempt rather than a
   * question retried for fifteen seconds. Neither caller — the controller and the MCP tool — opens
   * a transaction, which is what makes the wrap legal here.
   */
  public List<WorkEntity> listByProject(String projectId, String status) {
    if (status == null || status.isBlank()) {
      return patience.hold(
          "epic list", () -> entities.listByProjectAndArchetype(projectId, Archetype.EPIC));
    }
    EpicStatus filter =
        EpicLifecycle.parse(status)
            .orElseThrow(() -> new BadRequestException("Unknown epic status: " + status));
    return patience.hold(
        "epic list by status",
        () ->
            entities.listByProjectArchetypeAndStatus(projectId, Archetype.EPIC, filter.name()));
  }

  /** The {@code EPIC} row this id names, or a 404 — see {@link #entity}. */
  public WorkEntity get(String id) {
    return entity(id);
  }

  /**
   * A new epic, in its own transaction, held through a postgres cutover ({@link WritePatience}). The
   * validations run before the wrap so a missing title is still a 400 on the first attempt rather
   * than a question retried for fifteen seconds; the id and the slug are minted <em>inside</em> it,
   * which is what makes a second attempt a fresh row rather than a duplicate of a lost one.
   */
  public WorkEntity create(String projectId, String title, String description, String changedBy) {
    Validations.requireText(projectId, "projectId");
    Validations.requireText(title, "title");
    return writes.hold(
        "epic create",
        () -> {
          WorkEntity epic = settled(insert(projectId, title, description));
          auditService.record(
              AuditEntityType.EPIC, epic.id, epic.id, AuditOperation.CREATE, changedBy, epic);
          return epic;
        });
  }

  /** Retitles an epic, held through a cutover ({@link WritePatience}). */
  public WorkEntity update(String id, String title, String description, String changedBy) {
    return writes.hold(
        "epic update",
        () -> {
          WorkEntity row = entity(id);
          // Title and description are scope, so an edit needs a draft.
          EpicLifecycle.requireRefining(row);
          Validations.requireText(title, "title");
          row.title = title;
          row.description = description;
          requireArchetypeValid(row);
          WorkEntity epic = settled(row);
          auditService.record(
              AuditEntityType.EPIC, epic.id, epic.id, AuditOperation.UPDATE, changedBy, epic);
          return epic;
        });
  }

  /**
   * Moves an epic to {@code target} (the enum name), rejecting a move the lifecycle does not allow
   * with a 409. Superseding also spawns the successor draft — see {@link #supersede} — and points
   * the old row at it. Moving to {@link EpicStatus#IMPLEMENTED} stamps every feature and task still
   * unimplemented ({@link #stampImplemented}) — declaring the epic done is declaring its scope
   * done, and doing both in one transaction is what keeps the stored status and the derived reading
   * from ever disagreeing.
   *
   * <p>A target naming no status is a 409 too: the caller asked for a phase that does not exist,
   * which is the same kind of answer as asking for one that is not reachable. An absent target is a
   * 400, because that is a malformed request rather than a refused move.
   *
   * <p>Held through a cutover ({@link WritePatience}), the whole move in one transaction —
   * supersede's successor tree included, so a retry never leaves half a copy behind.
   */
  public Transition transition(String id, String target, String changedBy) {
    Validations.requireText(target, "target");
    return writes.hold(
        "epic transition",
        () -> {
          WorkEntity row = entity(id);
          EpicStatus to =
              EpicLifecycle.parse(target)
                  .orElseThrow(() -> new ConflictException("Unknown epic status: " + target));
          EpicLifecycle.requireTransition(EpicStatus.valueOf(row.status), to);

          WorkEntity successor = (to == EpicStatus.SUPERSEDED) ? supersede(row, changedBy) : null;
          if (to == EpicStatus.IMPLEMENTED) {
            stampImplemented(row, changedBy);
          }
          row.status = to.name();
          if (successor != null) {
            row.supersededByEntityId = successor.id;
          }
          requireArchetypeValid(row);
          WorkEntity epic = settled(row);
          auditService.record(
              AuditEntityType.EPIC, epic.id, epic.id, AuditOperation.UPDATE, changedBy, epic);
          return new Transition(epic, successor);
        });
  }

  /**
   * The other half of moving to {@link EpicStatus#IMPLEMENTED}: every feature and task still
   * unimplemented is stamped now, each with its own audit row. Markers already set keep their
   * timestamps — a feature implemented in June stays implemented in June; the stamp records when
   * the declaration covered the rest, not a rewrite of history.
   *
   * <p>It walks the memberships, two levels, four queries whatever the size of the plan: the epic's
   * children, their rows, those rows' children and <em>their</em> rows. Both levels take {@code
   * entity.implemented_at} — the one column what were {@code feature.implemented_on} and {@code
   * task.implemented_at} merged into.
   */
  private void stampImplemented(WorkEntity epic, String changedBy) {
    Instant now = Instant.now();
    Subtree subtree = subtreeOf(epic.id);
    for (WorkEntity feature : subtree.features()) {
      for (WorkEntity task : subtree.tasksOf(feature.id)) {
        if (task.implementedAt == null) {
          task.implementedAt = now;
          auditService.record(
              AuditEntityType.TASK,
              task.id,
              epic.id,
              AuditOperation.UPDATE,
              changedBy,
              task);
        }
      }
      if (feature.implementedAt == null) {
        feature.implementedAt = now;
        auditService.record(
            AuditEntityType.FEATURE,
            feature.id,
            epic.id,
            AuditOperation.UPDATE,
            changedBy,
            feature);
      }
    }
  }

  /** Removes an epic and its subtree, held through a cutover ({@link WritePatience}). */
  public void delete(String id, String changedBy) {
    writes.run(
        "epic delete",
        () -> {
          WorkEntity row = entity(id);
          // Deliberately allowed in every status: this removes the row rather than editing a frozen
          // scope, and the audit log outlives it.
          deleteSubtree(id, changedBy);
          entities.delete(row);
          auditService.record(AuditEntityType.EPIC, id, id, AuditOperation.DELETE, changedBy, row);
        });
  }

  /**
   * <b>The cascade, done in-service so every removed feature and task gets its own DELETE audit
   * row</b> — V1's stated reading of the FK, now driven by the memberships. Feature and task
   * dependencies are parent-local (validated on write), so nothing outside this subtree can point
   * into it and there are no external dependents to clear.
   *
   * <p>It is <b>one walk</b>, level by level — {@code childrenOfAll} then {@code listByIds} per
   * level, never one query per node — where there used to be two nested loops over two tables. The
   * {@code entity_membership} rows go with the FK's {@code on delete cascade} rather than being
   * removed one at a time; the entity rows are removed here because the audit row has to be written
   * from each of them anyway. The walk terminates because the nesting rule makes a membership cycle
   * impossible, and {@code doomed} is a set so a malformed edge could not make it loop either.
   *
   * <p><b>No legacy row is touched here, and that is the whole of the old tables' new state.</b>
   * The legacy {@code epic}, {@code feature} and {@code task} rows V10 left behind stay where they
   * are: nothing writes them, nothing points at them, and they are the frozen snapshot the
   * verification door compares against. A delete is therefore recorded rather than mirrored — the
   * DELETE audit rows written above are what say the row went, which is the direction that door
   * reads in.
   */
  private void deleteSubtree(String rootId, String changedBy) {
    Collection<String> level = List.of(rootId);
    Set<String> doomed = new LinkedHashSet<>();
    Map<String, String> parentOf = new LinkedHashMap<>();
    while (!level.isEmpty()) {
      List<String> children = new ArrayList<>();
      for (EntityMembership edge : memberships.childrenOfAll(level)) {
        if (doomed.add(edge.childId)) {
          parentOf.put(edge.childId, edge.parentId);
          children.add(edge.childId);
        }
      }
      level = children;
    }
    for (WorkEntity descendant : entities.listByIds(doomed)) {
      if (descendant.archetype == Archetype.FEATURE || descendant.archetype == Archetype.TASK) {
        auditService.record(
            AuditEntityType.of(descendant.archetype),
            descendant.id,
            rootId,
            AuditOperation.DELETE,
            changedBy,
            descendant);
      }
      entities.delete(descendant);
    }
  }

  /**
   * A fresh {@link Archetype#EPIC} row in {@link EpicStatus#REFINING}, unaudited — both callers
   * audit their own.
   *
   * <p>The slug's scope is the <b>project id</b>, which is what {@code uq_entity_slug_scope_slug}
   * makes of {@code uq_epic_project_slug}: the same rule, written once. It is now shared with the
   * project's tickets, the narrowing {@code docs/unified-entity-model.md} states.
   */
  private WorkEntity insert(String projectId, String title, String description) {
    WorkEntity row = new WorkEntity();
    row.id = UUID.randomUUID().toString();
    row.archetype = Archetype.EPIC;
    row.projectId = projectId;
    // Allocated inside the write's transaction but committed outside it, which is what makes a
    // create that rolls back leave a gap rather than hand the number back. See EntityNumbers.
    row.number = numbers.next(projectId);
    row.title = title;
    row.slugScope = projectId;
    // Minted once, at create, and never re-derived on update: the slug is a branch path segment,
    // and renaming an epic must not orphan the branches already cut from it.
    row.slug =
        Slugs.unique(
            Slugs.slugify(title, row.id, "epic-"), entities.slugsInScope(projectId));
    row.description = description;
    row.status = EpicStatus.REFINING.name();
    requireArchetypeValid(row);
    entities.persist(row);
    return row;
  }

  /**
   * The successor draft of a superseded epic: a new {@link EpicStatus#REFINING} epic carrying the
   * old title, description and the whole feature/task tree, so refinement restarts from what was
   * discarded rather than from a blank page.
   *
   * <p>Copies get fresh ids and keep their slugs — a feature's slug is unique within its epic and a
   * task's within its feature, and both scopes are new. The <em>epic's</em> slug is the exception:
   * its scope is the project, where the old row still holds it, so the successor mints the next
   * free one exactly as a hand-created epic would.
   *
   * <p>The implemented markers reset to null (nothing is implemented in a draft) and {@code
   * dependsOn} is remapped to the new ids. Remapping needs the second pass: a dependency may point
   * at a sibling created after it, so the whole id map has to exist before any pointer is set.
   *
   * <p><b>The memberships are copied with the rows</b>, and their positions are taken from the
   * source's order rather than re-derived, so the successor's plan is drawn in the order the
   * discarded one was. They stay dense and zero-based because the source's were.
   */
  private WorkEntity supersede(WorkEntity old, String changedBy) {
    WorkEntity successorRow = insert(old.projectId, old.title, old.description);

    Subtree source = subtreeOf(old.id);
    List<WorkEntity> oldTasks = new ArrayList<>();
    // Keyed by the old row's id, insertion-ordered so the audit rows land in the original order.
    Map<String, WorkEntity> featureCopies = new LinkedHashMap<>();
    Map<String, WorkEntity> taskCopies = new LinkedHashMap<>();

    // ONE bump for the whole discarded tree rather than one per copied row: the count is known
    // before anything is written, and a block is what EntityNumbers.allocate exists for. The
    // successor epic's own number came out of insert() above, so this block is the descendants'.
    int copies = source.features().size();
    for (WorkEntity feature : source.features()) {
      copies += source.tasksOf(feature.id).size();
    }
    long nextCopyNumber = (copies == 0) ? 0 : numbers.allocate(old.projectId, copies);

    int featurePosition = 0;
    for (WorkEntity feature : source.features()) {
      WorkEntity copy = copyUnder(feature, successorRow.id, featurePosition++, nextCopyNumber++);
      featureCopies.put(feature.id, copy);

      int taskPosition = 0;
      for (WorkEntity task : source.tasksOf(feature.id)) {
        WorkEntity taskCopy = copyUnder(task, copy.id, taskPosition++, nextCopyNumber++);
        taskCopies.put(task.id, taskCopy);
        oldTasks.add(task);
      }
    }

    // Second pass: every copy exists now, so a pointer can be remapped whichever way it points.
    for (WorkEntity feature : source.features()) {
      WorkEntity target = featureCopies.get(feature.dependsOnEntityId);
      featureCopies.get(feature.id).dependsOnEntityId = (target == null) ? null : target.id;
    }
    for (WorkEntity task : oldTasks) {
      WorkEntity target = taskCopies.get(task.dependsOnEntityId);
      taskCopies.get(task.id).dependsOnEntityId = (target == null) ? null : target.id;
    }

    // Audited after the remap so each snapshot is the finished row. settled() flushes the whole
    // batch, which is what populates the copies' creation timestamps.
    WorkEntity successor = settled(successorRow);
    auditService.record(
        AuditEntityType.EPIC,
        successor.id,
        successor.id,
        AuditOperation.CREATE,
        changedBy,
        successor);
    for (WorkEntity copy : featureCopies.values()) {
      auditService.record(
          AuditEntityType.FEATURE,
          copy.id,
          successor.id,
          AuditOperation.CREATE,
          changedBy,
          copy);
    }
    for (WorkEntity copy : taskCopies.values()) {
      auditService.record(
          AuditEntityType.TASK,
          copy.id,
          successor.id,
          AuditOperation.CREATE,
          changedBy,
          copy);
    }
    return successor;
  }

  /**
   * A fresh row carrying {@code source}'s content under {@code parentId} at {@code position}, and
   * the edge that puts it there.
   *
   * <p>The <b>slug is kept</b>: the new parent is a new scope, so the name is free again and the
   * branch it would have named is the successor's own. The <b>implemented marker resets</b> —
   * nothing is implemented in a draft — and {@code dependsOn} is left null for the second pass to
   * fill. The edge's id is the child's, V10's rule.
   */
  private WorkEntity copyUnder(WorkEntity source, String parentId, int position, long number) {
    WorkEntity copy = new WorkEntity();
    copy.id = UUID.randomUUID().toString();
    copy.archetype = source.archetype;
    copy.projectId = source.projectId;
    // A COPY IS A NEW ENTITY AND TAKES A NEW NUMBER. The source keeps its own: a superseded epic's
    // tree is the record of what was discarded, and two rows sharing a number would make the
    // qualified form ambiguous in exactly the project it is scoped by.
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

  /**
   * <b>An epic's two levels, read in four queries.</b> The features in membership order, then every
   * task under any of them, grouped by feature and each group still in its own membership order —
   * {@code childrenOfAll} sorts by position globally, and filtering by parent preserves that.
   *
   * <p>It exists because all three subtree walks want exactly this and would otherwise each invent
   * their own fan-out, which is where the N+1 would land.
   */
  private Subtree subtreeOf(String epicId) {
    List<EntityMembership> featureEdges = memberships.childrenOf(epicId);
    Map<String, WorkEntity> featureRows = indexById(entities.listByIds(childIds(featureEdges)));
    List<WorkEntity> features = new ArrayList<>();
    for (EntityMembership edge : featureEdges) {
      WorkEntity row = featureRows.get(edge.childId);
      if (row != null && row.archetype == Archetype.FEATURE) {
        features.add(row);
      }
    }

    List<String> featureIds = features.stream().map(row -> row.id).toList();
    List<EntityMembership> taskEdges = memberships.childrenOfAll(featureIds);
    Map<String, WorkEntity> taskRows = indexById(entities.listByIds(childIds(taskEdges)));
    Map<String, List<WorkEntity>> tasksByFeature = new LinkedHashMap<>();
    for (String featureId : featureIds) {
      tasksByFeature.put(featureId, new ArrayList<>());
    }
    for (EntityMembership edge : taskEdges) {
      WorkEntity row = taskRows.get(edge.childId);
      if (row != null && row.archetype == Archetype.TASK) {
        tasksByFeature.get(edge.parentId).add(row);
      }
    }
    return new Subtree(List.copyOf(features), tasksByFeature);
  }

  /** The two levels below an epic, in the order the plan is drawn — see {@link #subtreeOf}. */
  private record Subtree(List<WorkEntity> features, Map<String, List<WorkEntity>> tasks) {

    List<WorkEntity> tasksOf(String featureId) {
      return tasks.getOrDefault(featureId, List.of());
    }
  }

  private static List<String> childIds(List<EntityMembership> edges) {
    return edges.stream().map(edge -> edge.childId).toList();
  }

  private static Map<String, WorkEntity> indexById(List<WorkEntity> rows) {
    Map<String, WorkEntity> indexed = new LinkedHashMap<>();
    for (WorkEntity row : rows) {
      indexed.put(row.id, row);
    }
    return indexed;
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

  /**
   * The row this id names, or a 404 — and a row of another archetype is a 404 too. The four kinds
   * share one table and one id space now, so "no epic with this id" has to mean "no EPIC row with
   * this id" rather than "no row at all".
   */
  private WorkEntity entity(String id) {
    WorkEntity row = id == null ? null : entities.findById(id);
    if (row == null || row.archetype != Archetype.EPIC) {
      throw new NotFoundException("Epic not found: " + id);
    }
    return row;
  }

  /**
   * <b>The archetype registry on the ordinary write.</b> A create or an update that would leave a
   * row the registry refuses — a property an epic has no slot for, a status word from the other
   * lifecycle — is a 400 naming every violation at once, which is what {@code Archetypes.validate}
   * answers for and why it returns all of them rather than the first.
   */
  private static void requireArchetypeValid(WorkEntity candidate) {
    List<ArchetypeViolation> violations = Archetypes.validate(candidate);
    if (!violations.isEmpty()) {
      throw new BadRequestException(
          violations.stream()
              .map(ArchetypeViolation::message)
              .collect(Collectors.joining("; ")));
    }
  }
}
