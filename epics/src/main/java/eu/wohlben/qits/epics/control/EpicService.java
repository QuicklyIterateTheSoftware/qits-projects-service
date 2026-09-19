package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.Archetype;
import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.EpicStatus;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.entity.Task;
import eu.wohlben.qits.epics.entity.WorkEntity;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.ConflictException;
import eu.wohlben.qits.epics.error.NotFoundException;
import eu.wohlben.qits.epics.persistence.EntityMembershipRepository;
import eu.wohlben.qits.epics.persistence.EpicRepository;
import eu.wohlben.qits.epics.persistence.FeatureRepository;
import eu.wohlben.qits.epics.persistence.TaskRepository;
import eu.wohlben.qits.epics.persistence.WorkEntityRepository;
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
 * <h2>The merged table is the source of truth, and the old one is a mirror</h2>
 *
 * <p><b>Every read and every rule here is answered from {@code entity}.</b> The status a transition
 * is judged against, the slugs a new one is minted around, the listing the board draws and the
 * timestamps a caller is handed all come from that row and from nothing else. What a caller gets
 * back is a {@link WorkEntityProjections detached projection} of it, shaped as an {@link Epic} so
 * the mappers, the DTO and the five controllers above are untouched.
 *
 * <p><b>The legacy {@code epic} row is still written, as a write-behind mirror</b>, and
 * {@link #mirrorLegacyRow} is the whole of it. Nothing here reads it; see that method for the three
 * reasons it cannot go yet and for when it does.
 *
 * <p><b>The subtree walks stay on {@code Feature}/{@code Task}</b> — {@link #stampImplemented},
 * {@link #supersede} and the cascade in {@link #delete}. {@code FeatureService} and {@code
 * TaskService} are not part of this change and still write only the old tables, so a feature created
 * after it has no {@code entity} row at all and a membership-driven walk would silently find
 * nothing. What {@link #delete} <em>does</em> do through the memberships is remove the descendant
 * {@code entity} rows V10 backfilled, so a delete leaves no orphan behind.
 */
@ApplicationScoped
public class EpicService {

  @Inject WorkEntityRepository entities;

  @Inject EntityMembershipRepository memberships;

  /** The mirror's table, and nothing else — see {@link #mirrorLegacyRow}. */
  @Inject EpicRepository epicRepository;

  @Inject FeatureRepository featureRepository;

  @Inject TaskRepository taskRepository;

  @Inject AuditService auditService;

  @Inject ReadPatience patience;

  @Inject WritePatience writes;

  /**
   * The outcome of a {@link #transition}: the epic in its new status, plus the successor draft when
   * the move was to {@link EpicStatus#SUPERSEDED} (null otherwise).
   */
  public record Transition(Epic epic, Epic successor) {}

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
  public record PlannedTransition(Epic epic, EpicStatus target, boolean resolving) {}

  /** The preview of a move — see {@link PlannedTransition}. */
  public PlannedTransition planTransition(String id, String target) {
    Validations.requireText(target, "target");
    Epic epic = get(id);
    EpicStatus to =
        EpicLifecycle.parse(target)
            .orElseThrow(() -> new ConflictException("Unknown epic status: " + target));
    EpicLifecycle.requireTransition(epic.status, to);
    return new PlannedTransition(epic, to, EpicLifecycle.resolves(to));
  }

  public List<Epic> listByProject(String projectId) {
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
  public List<Epic> listByProject(String projectId, String status) {
    if (status == null || status.isBlank()) {
      return patience.hold(
          "epic list",
          () -> project(entities.listByProjectAndArchetype(projectId, Archetype.EPIC)));
    }
    EpicStatus filter =
        EpicLifecycle.parse(status)
            .orElseThrow(() -> new BadRequestException("Unknown epic status: " + status));
    return patience.hold(
        "epic list by status",
        () ->
            project(
                entities.listByProjectArchetypeAndStatus(
                    projectId, Archetype.EPIC, filter.name())));
  }

  public Epic get(String id) {
    return WorkEntityProjections.epic(entity(id));
  }

  /**
   * A new epic, in its own transaction, held through a postgres cutover ({@link WritePatience}). The
   * validations run before the wrap so a missing title is still a 400 on the first attempt rather
   * than a question retried for fifteen seconds; the id and the slug are minted <em>inside</em> it,
   * which is what makes a second attempt a fresh row rather than a duplicate of a lost one.
   */
  public Epic create(String projectId, String title, String description, String changedBy) {
    Validations.requireText(projectId, "projectId");
    Validations.requireText(title, "title");
    return writes.hold(
        "epic create",
        () -> {
          Epic epic = settled(insert(projectId, title, description));
          auditService.record(
              AuditEntityType.EPIC, epic.id, epic.id, AuditOperation.CREATE, changedBy, epic);
          return epic;
        });
  }

  /** Retitles an epic, held through a cutover ({@link WritePatience}). */
  public Epic update(String id, String title, String description, String changedBy) {
    return writes.hold(
        "epic update",
        () -> {
          WorkEntity row = entity(id);
          // Title and description are scope, so an edit needs a draft. The phase is read off the
          // entity row, projected only so the rule keeps the signature its three other callers use.
          EpicLifecycle.requireRefining(WorkEntityProjections.epic(row));
          Validations.requireText(title, "title");
          row.title = title;
          row.description = description;
          requireArchetypeValid(row);
          mirrorLegacyRow(row);
          Epic epic = settled(row);
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

          Epic successor = (to == EpicStatus.SUPERSEDED) ? supersede(row, changedBy) : null;
          if (to == EpicStatus.IMPLEMENTED) {
            stampImplemented(row, changedBy);
          }
          row.status = to.name();
          if (successor != null) {
            row.supersededByEntityId = successor.id;
          }
          requireArchetypeValid(row);
          mirrorLegacyRow(row);
          Epic epic = settled(row);
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
   * <p>It walks {@code Feature}/{@code Task} and not the memberships, for the reason the class
   * javadoc gives: those two services still write only the old tables.
   */
  private void stampImplemented(WorkEntity epic, String changedBy) {
    Instant now = Instant.now();
    for (Feature feature : featureRepository.listByEpic(epic.id)) {
      for (Task task : taskRepository.listByFeature(feature.id)) {
        if (task.implementedAt == null) {
          task.implementedAt = now;
          auditService.record(
              AuditEntityType.TASK, task.id, epic.id, AuditOperation.UPDATE, changedBy, task);
        }
      }
      if (feature.implementedOn == null) {
        feature.implementedOn = now;
        auditService.record(
            AuditEntityType.FEATURE, feature.id, epic.id, AuditOperation.UPDATE, changedBy, feature);
      }
    }
  }

  /** Removes an epic and its subtree, held through a cutover ({@link WritePatience}). */
  public void delete(String id, String changedBy) {
    writes.run(
        "epic delete",
        () -> {
          WorkEntity row = entity(id);
          Epic epic = WorkEntityProjections.epic(row);
          // Deliberately allowed in every status: this removes the row rather than editing a frozen
          // scope, and the audit log outlives it.
          // Delete the subtree in-service (not via DB cascade) so every removed feature/task gets
          // its own DELETE audit row. Feature dependencies are epic-local (validated on write), so
          // no other epic can reference these rows — no external dependents to clear.
          for (Feature feature : featureRepository.listByEpic(id)) {
            for (Task task : taskRepository.listByFeature(feature.id)) {
              taskRepository.delete(task);
              auditService.record(
                  AuditEntityType.TASK, task.id, id, AuditOperation.DELETE, changedBy, task);
            }
            featureRepository.delete(feature);
            auditService.record(
                AuditEntityType.FEATURE, feature.id, id, AuditOperation.DELETE, changedBy, feature);
          }
          deleteDescendantEntities(id);
          entities.delete(row);
          deleteLegacyRow(id);
          auditService.record(AuditEntityType.EPIC, id, id, AuditOperation.DELETE, changedBy, epic);
        });
  }

  /**
   * <b>The membership-reachable half of the cascade.</b> V10 copied every feature and task into
   * {@code entity} and gave each one an {@code entity_membership} edge, so an epic deleted here
   * would leave those rows standing with nothing above them. They are removed level by level — one
   * membership query and one row read per level, never one query per node — and the edges themselves
   * go with the FK's {@code on delete cascade}.
   *
   * <p>No audit row is written for them: the feature/task DELETE entries the caller has already
   * recorded are about the same planning rows, and a second entry per row would say one deletion
   * twice. The walk terminates because the nesting rule makes a membership cycle impossible.
   */
  private void deleteDescendantEntities(String rootId) {
    Collection<String> level = List.of(rootId);
    Set<String> doomed = new LinkedHashSet<>();
    while (!level.isEmpty()) {
      List<String> children =
          memberships.childrenOfAll(level).stream()
              .map(membership -> membership.childId)
              .filter(doomed::add)
              .toList();
      level = children;
    }
    for (WorkEntity descendant : entities.listByIds(doomed)) {
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
    mirrorLegacyRow(row);
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
   * dependsOn*} is remapped to the new ids. Remapping needs the second pass: a dependency may point
   * at a sibling created after it, so the whole id map has to exist before any pointer is set.
   */
  private Epic supersede(WorkEntity old, String changedBy) {
    WorkEntity successorRow = insert(old.projectId, old.title, old.description);

    List<Feature> oldFeatures = featureRepository.listByEpic(old.id);
    List<Task> oldTasks = new ArrayList<>();
    // Keyed by the old row's id, insertion-ordered so the audit rows land in the original order.
    Map<String, Feature> featureCopies = new LinkedHashMap<>();
    Map<String, Task> taskCopies = new LinkedHashMap<>();

    for (Feature feature : oldFeatures) {
      Feature copy = new Feature();
      copy.id = UUID.randomUUID().toString();
      copy.epicId = successorRow.id;
      copy.title = feature.title;
      copy.slug = feature.slug;
      copy.description = feature.description;
      featureRepository.persist(copy);
      featureCopies.put(feature.id, copy);

      for (Task task : taskRepository.listByFeature(feature.id)) {
        Task taskCopy = new Task();
        taskCopy.id = UUID.randomUUID().toString();
        taskCopy.featureId = copy.id;
        taskCopy.repositoryId = task.repositoryId;
        taskCopy.title = task.title;
        taskCopy.slug = task.slug;
        taskCopy.description = task.description;
        taskRepository.persist(taskCopy);
        oldTasks.add(task);
        taskCopies.put(task.id, taskCopy);
      }
    }

    // Second pass: every copy exists now, so a pointer can be remapped whichever way it points.
    for (Feature feature : oldFeatures) {
      Feature target = featureCopies.get(feature.dependsOnFeatureId);
      featureCopies.get(feature.id).dependsOnFeatureId = (target == null) ? null : target.id;
    }
    for (Task task : oldTasks) {
      Task target = taskCopies.get(task.dependsOnTaskId);
      taskCopies.get(task.id).dependsOnTaskId = (target == null) ? null : target.id;
    }

    // Audited after the remap so each snapshot is the finished row. settled() flushes the whole
    // batch, which is what populates the copies' creation timestamps.
    Epic successor = settled(successorRow);
    auditService.record(
        AuditEntityType.EPIC,
        successor.id,
        successor.id,
        AuditOperation.CREATE,
        changedBy,
        successor);
    for (Feature copy : featureCopies.values()) {
      auditService.record(
          AuditEntityType.FEATURE, copy.id, successor.id, AuditOperation.CREATE, changedBy, copy);
    }
    for (Task copy : taskCopies.values()) {
      auditService.record(
          AuditEntityType.TASK, copy.id, successor.id, AuditOperation.CREATE, changedBy, copy);
    }
    return successor;
  }

  /**
   * <b>The legacy {@code epic} row, written from the entity row and never read back here.</b>
   *
   * <p>It is a mirror and not a second source of truth. Three things still name that table and none
   * of them is in this task's scope: {@code fk_feature_epic} is a live foreign key from
   * {@code feature.epic_id}, {@code dossier_page.epic_id} is another, and {@code FeatureService},
   * {@code TaskService} and {@code DossierService} all read the row — the first two to ask {@link
   * EpicLifecycle} what the phase permits, the third to resolve a page's owner. Dropping the write
   * would break every one of them on the first create.
   *
   * <p><b>So the order is fixed: the entity row is written first and this second</b>, everywhere,
   * and nothing in this class ever reads what it wrote. It goes in the task that moves those three
   * services onto the merged table, together with the two tables themselves.
   */
  private void mirrorLegacyRow(WorkEntity source) {
    Epic row = epicRepository.findById(source.id);
    boolean fresh = row == null;
    if (fresh) {
      row = new Epic();
      row.id = source.id;
      // @Column(updatable = false) on both sides — settable on the insert alone.
      row.slug = source.slug;
    }
    row.projectId = source.projectId;
    row.title = source.title;
    row.description = source.description;
    row.status = EpicStatus.valueOf(source.status);
    row.supersededByEpicId = source.supersededByEntityId;
    if (fresh) {
      epicRepository.persist(row);
    }
  }

  /** The mirror's removal — see {@link #mirrorLegacyRow} for why there is one at all. */
  private void deleteLegacyRow(String id) {
    Epic row = epicRepository.findById(id);
    if (row != null) {
      epicRepository.delete(row);
    }
  }

  /**
   * The row as a caller sees it, taken after an explicit flush so the Hibernate-managed timestamps
   * are populated — a create is promised a {@code createdAt} and an update an {@code updatedAt} that
   * is not before it.
   */
  private Epic settled(WorkEntity row) {
    entities.getEntityManager().flush();
    return WorkEntityProjections.epic(row);
  }

  private List<Epic> project(List<WorkEntity> rows) {
    return rows.stream().map(WorkEntityProjections::epic).toList();
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
