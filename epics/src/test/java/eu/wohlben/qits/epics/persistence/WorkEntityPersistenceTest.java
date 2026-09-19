package eu.wohlben.qits.epics.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.entity.Archetype;
import eu.wohlben.qits.epics.entity.EntityMembership;
import eu.wohlben.qits.epics.entity.EpicStatus;
import eu.wohlben.qits.epics.entity.WorkEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * That the two new entities actually map to the two new tables, and that the repositories' reads
 * answer.
 *
 * <p><b>This is the one part of the feature a {@code @QuarkusTest} has to cover.</b> Hibernate does
 * not validate the schema at boot here — Flyway owns the DDL and {@code database.generation} is
 * {@code none} — so a column name that disagrees with V9 is invisible until the first query, and
 * the rest of this feature's tests are pure functions with no session behind them. It adds no
 * {@code @TestProfile}: it runs on the module's own configuration, which is the whole of what this
 * repository's test-profile budget rule asks for.
 *
 * <p>It wipes only its own two tables, in FK-safe order, rather than joining {@code
 * EpicsTestSupport} — nothing else in the suite writes them, and the four old tables are
 * deliberately untouched by everything in this change.
 */
@QuarkusTest
class WorkEntityPersistenceTest {

  @Inject WorkEntityRepository entities;
  @Inject EntityMembershipRepository memberships;

  @BeforeEach
  void wipe() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              memberships.deleteAll();
              entities.deleteAll();
            });
  }

  @Test
  void aRowOfEachArchetypeRoundTripsThroughTheMergedTable() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              WorkEntity epic = root("e-1", Archetype.EPIC, "plan");
              epic.status = EpicStatus.REFINING.name();
              epic.description = "the pitch";
              entities.persist(epic);

              WorkEntity feature = nested("f-1", Archetype.FEATURE, "login", "e-1");
              entities.persist(feature);

              WorkEntity task = nested("t-1", Archetype.TASK, "wire-it", "f-1");
              task.repositoryId = "repo-1";
              entities.persist(task);
            });

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              WorkEntity stored = entities.findById("t-1");
              assertEquals(Archetype.TASK, stored.archetype);
              assertEquals("repo-1", stored.repositoryId);
              assertEquals("f-1", stored.slugScope);
              // The timestamps are Hibernate's, exactly as on every other row in this module.
              assertTrue(stored.createdAt != null && stored.updatedAt != null);
            });
  }

  @Test
  void theListingsNarrowByArchetypeAndByStatus() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              WorkEntity refining = root("e-1", Archetype.EPIC, "one");
              refining.status = EpicStatus.REFINING.name();
              entities.persist(refining);

              WorkEntity frozen = root("e-2", Archetype.EPIC, "two");
              frozen.status = EpicStatus.IMPLEMENTATION.name();
              entities.persist(frozen);

              entities.persist(nested("f-1", Archetype.FEATURE, "part", "e-1"));
            });

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              assertEquals(3, entities.listByProject("proj-1").size());
              assertEquals(
                  2, entities.listByProjectAndArchetype("proj-1", Archetype.EPIC).size());
              assertEquals(
                  List.of("e-1"),
                  entities
                      .listByProjectArchetypeAndStatus(
                          "proj-1", Archetype.EPIC, EpicStatus.REFINING.name())
                      .stream()
                      .map(entity -> entity.id)
                      .toList());
              // The bulk read, and its empty guard: `in ()` is a syntax error in postgres, so an
              // empty input must never reach the database.
              assertEquals(2, entities.listByIds(List.of("e-1", "f-1")).size());
              assertEquals(List.of(), entities.listByIds(List.of()));
              assertEquals(
                  Optional.of("e-1"),
                  entities.findBySlug("proj-1", "one").map(entity -> entity.id));
            });
  }

  @Test
  void positionsAreDenseAndTheGapCloses() {
    // DossierPageRepository's idiom, applied to the edge: maxPosition answers -1 so a create
    // appends, and closeGapAfter renumbers the tail in one statement.
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              entities.persist(root("e-1", Archetype.EPIC, "plan"));
              assertEquals(-1, memberships.maxPosition("e-1"));

              for (int index = 0; index < 3; index++) {
                String id = "f-" + index;
                entities.persist(nested(id, Archetype.FEATURE, "part-" + index, "e-1"));
                memberships.persist(edge("m-" + index, "e-1", id, memberships.maxPosition("e-1") + 1));
              }
            });

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              assertEquals(2, memberships.maxPosition("e-1"));
              assertEquals(
                  List.of("f-0", "f-1", "f-2"),
                  memberships.childrenOf("e-1").stream().map(edge -> edge.childId).toList());

              memberships.delete(memberships.membershipOf("f-0").orElseThrow());
              memberships.closeGapAfter("e-1", 0);
            });

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              assertEquals(
                  List.of(0, 1),
                  memberships.childrenOf("e-1").stream().map(edge -> edge.position).toList());
              assertEquals(
                  2, memberships.childrenOfAll(List.of("e-1", "nowhere")).size());
              assertEquals(List.of(), memberships.childrenOfAll(List.of()));
              assertEquals(2, memberships.membershipsOfAll(List.of("f-1", "f-2")).size());
            });
  }

  // ---- fixtures --------------------------------------------------------------------------------

  private static WorkEntity root(String id, Archetype archetype, String slug) {
    return entity(id, archetype, slug, "proj-1");
  }

  private static WorkEntity nested(String id, Archetype archetype, String slug, String parentId) {
    return entity(id, archetype, slug, parentId);
  }

  private static WorkEntity entity(
      String id, Archetype archetype, String slug, String slugScope) {
    WorkEntity entity = new WorkEntity();
    entity.id = id;
    entity.projectId = "proj-1";
    entity.archetype = archetype;
    entity.title = slug + " title";
    entity.slug = slug;
    entity.slugScope = slugScope;
    return entity;
  }

  private static EntityMembership edge(
      String id, String parentId, String childId, int position) {
    EntityMembership membership = new EntityMembership();
    membership.id = id;
    membership.parentId = parentId;
    membership.childId = childId;
    membership.position = position;
    return membership;
  }
}
