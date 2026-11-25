package eu.wohlben.qits.entities.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.api.EntityBlocks;
import eu.wohlben.qits.projects.bus.EntityBlockChanged;
import eu.wohlben.qits.projects.bus.RecordingEntityBlockAnnouncer;
import eu.wohlben.qits.projects.entity.Project;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A change of the block flag is announced once, after the commit</b> (qits-2674): the block door
 * ({@link EntityBlocks#apply}) publishes one {@link EntityBlockChanged} per block and unblock that
 * changes the explicit flag, and a transition that clears a standing block publishes one with
 * {@code blocked=false} and {@code reason="cleared by transition to <STATUS>"}. An idempotent
 * re-block, an unblock of an unblocked row and a transition of an unblocked row publish nothing.
 */
@QuarkusTest
class EntityBlockChangedTest {

  @Inject WorkEntityService entities;
  @Inject EntityBlocks blocks;
  @Inject RecordingEntityBlockAnnouncer announced;

  private String projectId;
  private String ticketId;
  private long number;

  @BeforeEach
  void seed() {
    projectId = "block-" + UUID.randomUUID().toString().substring(0, 8);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = projectId;
              project.slug = projectId;
              project.persist();
            });
    WorkEntity ticket =
        entities
            .create(
                Archetype.TICKET,
                projectId,
                EntityWrite.ticket("The totals", "they are wrong", null, "BUG", null)
                    .withAcceptanceCriteria(List.of("The totals are right.")),
                "seed")
            .entity();
    ticketId = ticket.id;
    number = ticket.number;
    announced.reset();
  }

  private WorkEntity row() {
    return QuarkusTransaction.requiringNew().call(() -> entities.get(Archetype.TICKET, ticketId));
  }

  private List<EntityBlockChanged> published() {
    return announced.of(projectId);
  }

  @Test
  void aBlockAndAnUnblockAreEachAnnouncedOnce() {
    blocks.apply(row(), true, "waiting on the schema", "ada");

    assertEquals(1, published().size(), published().toString());
    EntityBlockChanged blocked = published().get(0);
    assertEquals(ticketId, blocked.entityId());
    assertEquals("TICKET", blocked.archetype());
    assertEquals(number, blocked.number());
    assertEquals(projectId + "-" + number, blocked.qualifiedId());
    assertEquals("REPORTED", blocked.status());
    assertTrue(blocked.blocked());
    assertEquals("waiting on the schema", blocked.reason());
    assertEquals("ada", blocked.changedBy());

    // Re-blocking restates the flag: a comment, no event.
    blocks.apply(row(), true, "still the schema", "ada");
    assertEquals(1, published().size(), published().toString());

    blocks.apply(row(), false, "the schema landed", "bob");
    assertEquals(2, published().size(), published().toString());
    EntityBlockChanged unblocked = published().get(1);
    assertEquals(false, unblocked.blocked());
    assertEquals("the schema landed", unblocked.reason());
    assertEquals("bob", unblocked.changedBy());

    // An unblock of an unblocked row changes nothing, and an unblock may state nothing.
    blocks.apply(row(), false, null, "bob");
    assertEquals(2, published().size(), published().toString());
    blocks.apply(row(), true, "again", "ada");
    blocks.apply(row(), false, "  ", "bob");
    assertEquals(4, published().size(), published().toString());
    assertNull(published().get(3).reason());
  }

  @Test
  void aTransitionThatClearsABlockAnnouncesTheClear() {
    blocks.apply(row(), true, "waiting on the schema", "ada");
    announced.reset();

    entities.transition(Archetype.TICKET, ticketId, "REFINED", Mover.machine("agent-7"));

    assertEquals(1, published().size(), published().toString());
    EntityBlockChanged cleared = published().get(0);
    assertEquals(false, cleared.blocked());
    assertEquals("cleared by transition to REFINED", cleared.reason());
    assertEquals("REFINED", cleared.status());
    assertEquals("agent-7", cleared.changedBy());
  }

  @Test
  void aTransitionOfAnUnblockedRowAnnouncesNothing() {
    entities.transition(Archetype.TICKET, ticketId, "REFINED", Mover.machine("agent-7"));
    assertTrue(published().isEmpty(), published().toString());
  }
}
