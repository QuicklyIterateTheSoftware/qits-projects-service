package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.entity.Archetype;
import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.entity.Task;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What is genuinely new once {@code FeatureService} and {@code TaskService} read and write {@code
 * entity} + {@code entity_membership}: the edge, its position, and the two hops a task's epic is
 * now away.
 *
 * <p>Everything these four assert was previously a column and could not be got wrong — {@code
 * feature.epic_id} either pointed somewhere or it did not, and there was no order to keep. A
 * relation that is a row of its own has an <b>order</b> and a <b>density</b> that a service has to
 * maintain, and a parent that has to be <b>walked to</b>; each of those is a way to be subtly wrong
 * while every old assertion still passes.
 */
@QuarkusTest
class UnifiedDescendantsTest extends EpicsTestSupport {

  @Inject EpicService epicService;
  @Inject FeatureService featureService;
  @Inject TaskService taskService;
  @Inject AuditService auditService;
  @Inject StoredEntityFacts facts;

  private Epic epic() {
    return epicService.create("proj-1", "Epic", null, "t");
  }

  /**
   * A listing is drawn in the <b>edges'</b> order, and the edges are dense and zero-based. {@code
   * WorkEntityRepository.listByIds} answers oldest-first, so a service that simply returned what it
   * read would be right by accident here and wrong the moment anything reorders.
   */
  @Test
  void aListingIsDrawnInMembershipPositionOrder() {
    Epic epic = epic();
    Feature a = featureService.create(epic.id, "A", null, null, "t");
    Feature b = featureService.create(epic.id, "B", null, null, "t");
    Feature c = featureService.create(epic.id, "C", null, null, "t");

    assertEquals(
        List.of(a.id, b.id, c.id),
        featureService.listByEpic(epic.id).stream().map(f -> f.id).toList());
    inFreshTx(() -> assertEquals(List.of(0, 1, 2), positionsUnder(epic.id)));

    Task one = taskService.create(a.id, "repo-1", "One", null, null, "t");
    Task two = taskService.create(a.id, "repo-1", "Two", null, null, "t");
    assertEquals(
        List.of(one.id, two.id),
        taskService.listByFeature(a.id).stream().map(t -> t.id).toList());
    inFreshTx(() -> assertEquals(List.of(0, 1), positionsUnder(a.id)));
  }

  /**
   * <b>Removing the middle sibling closes the gap.</b> The order of what is left is the assertion
   * that matters — a sparse {@code 0, 2} happens to sort the same way, so a test that only counted
   * rows or read the listing would pass over exactly the bug {@code closeGapAfter} exists to stop.
   */
  @Test
  void removingAMiddleSiblingLeavesTheRestDenseAndInOrder() {
    Epic epic = epic();
    Feature a = featureService.create(epic.id, "A", null, null, "t");
    Feature b = featureService.create(epic.id, "B", null, null, "t");
    Feature c = featureService.create(epic.id, "C", null, null, "t");

    featureService.delete(b.id, "t");

    assertEquals(
        List.of(a.id, c.id), featureService.listByEpic(epic.id).stream().map(f -> f.id).toList());
    inFreshTx(() -> assertEquals(List.of(0, 1), positionsUnder(epic.id)));

    Task one = taskService.create(a.id, "repo-1", "One", null, null, "t");
    Task two = taskService.create(a.id, "repo-1", "Two", null, null, "t");
    Task three = taskService.create(a.id, "repo-1", "Three", null, null, "t");
    taskService.delete(two.id, "t");

    assertEquals(
        List.of(one.id, three.id),
        taskService.listByFeature(a.id).stream().map(t -> t.id).toList());
    inFreshTx(() -> assertEquals(List.of(0, 1), positionsUnder(a.id)));
  }

  /**
   * <b>A task's epic is two membership hops up</b> — task → feature → epic — where it used to be two
   * columns. Both ends of that walk are asserted through things only the walk can produce: the phase
   * guard, which reads the epic the walk found, and the audit row's subtree key.
   */
  @Test
  void aTasksEpicIsReachedByTwoMembershipHops() {
    Epic epic = epic();
    Feature feature = featureService.create(epic.id, "Feature", null, null, "t");
    Task task = taskService.create(feature.id, "repo-1", "Task", null, null, "t");
    assertEquals(feature.id, taskService.get(task.id).featureId);

    // The marker is only writable in IMPLEMENTATION, and the only way to know the phase is the walk.
    epicService.transition(epic.id, "IMPLEMENTATION", "t");
    Instant when = Instant.parse("2026-07-25T10:15:30.00Z");
    assertEquals(when, taskService.update(task.id, null, null, null, false, when, false, "t")
        .implementedAt);

    // auditentry.epic_id is the subtree key, and the walk is what filled it.
    assertTrue(
        auditService.listForEpic(epic.id).stream()
            .anyMatch(
                entry ->
                    entry.entityType == AuditEntityType.TASK
                        && entry.entityId.equals(task.id)
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
    Epic epic = epic();
    Feature a = featureService.create(epic.id, "A", null, null, "t");
    Feature b = featureService.create(epic.id, "B", null, a.id, "t");

    assertEquals(a.id, b.dependsOnFeatureId);
    // The parent is still the epic. A dependency says "do that one first", not "part of".
    assertEquals(epic.id, b.epicId);
    inFreshTx(
        () ->
            assertEquals(
                epic.id,
                entityMembershipRepository.membershipOf(b.id).orElseThrow().parentId,
                "the dependency was written as a membership"));

    // And had it been judged as one, it would have been refused: FEATURE cannot contain FEATURE.
    assertFalse(Nesting.mayContain(Archetype.FEATURE, Archetype.FEATURE));
    inFreshTx(
        () -> {
          var violations =
              Nesting.check(List.of(new EntityFact(b.id, Archetype.FEATURE, a.id)), facts);
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
