package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.WorkEntity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What is genuinely new once features and tasks are read and written as {@code entity} + {@code
 * entity_membership} (by {@code FeatureService} and {@code TaskService} then, by {@code
 * WorkEntityService} since qits-399): the edge, its position, and the two hops a task's epic is
 * now away.
 *
 * <p>Everything these four assert was previously a column and could not be got wrong — {@code
 * feature.epic_id} either pointed somewhere or it did not, and there was no order to keep. A
 * relation that is a row of its own has an <b>order</b> and a <b>density</b> that a service has to
 * maintain, and a parent that has to be <b>walked to</b>; each of those is a way to be subtly wrong
 * while every old assertion still passes.
 */
@QuarkusTest
class UnifiedDescendantsTest extends EntitiesTestSupport {

  @Inject WorkEntityService workEntities;
  @Inject AuditService auditService;
  @Inject StoredEntityFacts facts;

  private WorkEntity epic() {
    return workEntities
        .create(Archetype.EPIC, "proj-1", EntityWrite.epic("Epic", null), "t")
        .entity();
  }

  /**
   * A listing is drawn in the <b>edges'</b> order, and the edges are dense and zero-based. {@code
   * WorkEntityRepository.listByIds} answers oldest-first, so a service that simply returned what it
   * read would be right by accident here and wrong the moment anything reorders.
   */
  @Test
  void aListingIsDrawnInMembershipPositionOrder() {
    WorkEntity epic = epic();
    Nested a =
        workEntities.create(Archetype.FEATURE, epic.id, EntityWrite.feature("A", null, null), "t");
    Nested b =
        workEntities.create(Archetype.FEATURE, epic.id, EntityWrite.feature("B", null, null), "t");
    Nested c =
        workEntities.create(Archetype.FEATURE, epic.id, EntityWrite.feature("C", null, null), "t");

    assertEquals(
        List.of(a.entity().id, b.entity().id, c.entity().id),
        workEntities.listChildren(Archetype.FEATURE, epic.id).stream()
            .map(f -> f.entity().id)
            .toList());
    inFreshTx(() -> assertEquals(List.of(0, 1, 2), positionsUnder(epic.id)));

    Nested one =
        workEntities.create(
            Archetype.TASK, a.entity().id, EntityWrite.task("repo-1", "One", null, null), "t");
    Nested two =
        workEntities.create(
            Archetype.TASK, a.entity().id, EntityWrite.task("repo-1", "Two", null, null), "t");
    assertEquals(
        List.of(one.entity().id, two.entity().id),
        workEntities.listChildren(Archetype.TASK, a.entity().id).stream()
            .map(t -> t.entity().id)
            .toList());
    inFreshTx(() -> assertEquals(List.of(0, 1), positionsUnder(a.entity().id)));
  }

  /**
   * <b>Removing the middle sibling closes the gap.</b> The order of what is left is the assertion
   * that matters — a sparse {@code 0, 2} happens to sort the same way, so a test that only counted
   * rows or read the listing would pass over exactly the bug {@code closeGapAfter} exists to stop.
   */
  @Test
  void removingAMiddleSiblingLeavesTheRestDenseAndInOrder() {
    WorkEntity epic = epic();
    Nested a =
        workEntities.create(Archetype.FEATURE, epic.id, EntityWrite.feature("A", null, null), "t");
    Nested b =
        workEntities.create(Archetype.FEATURE, epic.id, EntityWrite.feature("B", null, null), "t");
    Nested c =
        workEntities.create(Archetype.FEATURE, epic.id, EntityWrite.feature("C", null, null), "t");

    workEntities.delete(Archetype.FEATURE, b.entity().id, "t");

    assertEquals(
        List.of(a.entity().id, c.entity().id),
        workEntities.listChildren(Archetype.FEATURE, epic.id).stream()
            .map(f -> f.entity().id)
            .toList());
    inFreshTx(() -> assertEquals(List.of(0, 1), positionsUnder(epic.id)));

    Nested one =
        workEntities.create(
            Archetype.TASK, a.entity().id, EntityWrite.task("repo-1", "One", null, null), "t");
    Nested two =
        workEntities.create(
            Archetype.TASK, a.entity().id, EntityWrite.task("repo-1", "Two", null, null), "t");
    Nested three =
        workEntities.create(
            Archetype.TASK, a.entity().id, EntityWrite.task("repo-1", "Three", null, null), "t");
    workEntities.delete(Archetype.TASK, two.entity().id, "t");

    assertEquals(
        List.of(one.entity().id, three.entity().id),
        workEntities.listChildren(Archetype.TASK, a.entity().id).stream()
            .map(t -> t.entity().id)
            .toList());
    inFreshTx(() -> assertEquals(List.of(0, 1), positionsUnder(a.entity().id)));
  }

  /**
   * <b>A task's epic is two membership hops up</b> — task → feature → epic — where it used to be two
   * columns. Both ends of that walk are asserted through things only the walk can produce: the phase
   * guard, which reads the epic the walk found, and the audit row's subtree key.
   */
  @Test
  void aTasksEpicIsReachedByTwoMembershipHops() {
    WorkEntity epic = epic();
    Nested feature =
        workEntities.create(
            Archetype.FEATURE, epic.id, EntityWrite.feature("Feature", null, null), "t");
    Nested task =
        workEntities.create(
            Archetype.TASK,
            feature.entity().id,
            EntityWrite.task("repo-1", "Task", null, null),
            "t");
    assertEquals(
        feature.entity().id, workEntities.nested(Archetype.TASK, task.entity().id).parentId());

    // The marker is only writable at REFINED, and the only way to know the phase is the walk.
    workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "t");
    Instant when = Instant.parse("2026-07-25T10:15:30.00Z");
    assertEquals(
        when,
        workEntities
            .update(
                Archetype.TASK,
                task.entity().id,
                EntityWrite.nodeEdit(null, null, null, false, when, false),
                "t")
            .entity()
            .implementedAt);

    // auditentry.epic_id is the subtree key, and the walk is what filled it.
    assertTrue(
        auditService.listForEpic(epic.id).stream()
            .anyMatch(
                entry ->
                    entry.entityType == AuditEntityType.TASK
                        && entry.entityId.equals(task.entity().id)
                        && entry.operation == AuditOperation.UPDATE),
        "the task's UPDATE was not filed under its epic");
  }

  /**
   * <b>{@code dependsOn} is a column and {@link Nesting} is never consulted for it.</b>
   *
   * <p>The two edges are easy to conflate — both are a self-reference between two planning rows —
   * and conflating them would be silent in the ordinary direction and wrong in this one: a feature
   * depending on a sibling feature is exactly what the planning surface is for, while a feature
   * <em>under</em> a feature is an illegal membership. So the negative is the assertion: the
   * dependency is accepted, the depended-on row does not become the parent, and the same pair
   * offered to {@code Nesting} as a membership is refused.
   */
  @Test
  void nestingIsNotConsultedForADependency() {
    WorkEntity epic = epic();
    Nested a =
        workEntities.create(Archetype.FEATURE, epic.id, EntityWrite.feature("A", null, null), "t");
    Nested b =
        workEntities.create(
            Archetype.FEATURE, epic.id, EntityWrite.feature("B", null, a.entity().id), "t");

    assertEquals(a.entity().id, b.entity().dependsOnEntityId);
    // The parent is still the epic. A dependency says "do that one first", not "part of".
    assertEquals(epic.id, b.parentId());
    inFreshTx(
        () ->
            assertEquals(
                epic.id,
                entityMembershipRepository.structuralMembershipOf(b.entity().id).orElseThrow().parentId,
                "the dependency was written as a membership"));

    // And had it been judged as one, it would have been refused: FEATURE cannot contain FEATURE.
    assertFalse(Nesting.mayContain(Archetype.FEATURE, Archetype.FEATURE));
    inFreshTx(
        () -> {
          var violations =
              Nesting.check(
                  List.of(new EntityFact(b.entity().id, Archetype.FEATURE, a.entity().id)), facts);
          assertEquals(1, violations.size());
          assertEquals(NestingViolation.Reason.NOT_NESTABLE, violations.get(0).reason());
          assertNotNull(violations.get(0).message());
        });
  }

  private List<Integer> positionsUnder(String parentId) {
    return entityMembershipRepository.childrenOf(parentId).stream()
        .map(edge -> edge.position)
        .toList();
  }
}
