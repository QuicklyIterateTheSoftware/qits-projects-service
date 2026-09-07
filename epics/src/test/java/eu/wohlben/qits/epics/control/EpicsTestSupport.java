package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.persistence.AuditRepository;
import eu.wohlben.qits.epics.persistence.EpicRepository;
import eu.wohlben.qits.epics.persistence.FeatureRepository;
import eu.wohlben.qits.epics.persistence.TaskRepository;
import eu.wohlben.qits.epics.persistence.TicketCommentRepository;
import eu.wohlben.qits.epics.persistence.TicketRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for epics control-layer tests: wipes every table before each test in FK-safe order so every
 * case starts from an empty planning DB. Runs against an embedded postgres this module's suite
 * spawns as a child process (see {@code testdb/EmbeddedPg} and
 * src/test/resources/application.properties) — no docker, no auth variant.
 *
 * <p>The order is the FK graph read leaves-first, and the two roots are independent: comments
 * before tickets, tasks before features before epics. A new table wiped in the wrong place fails
 * with a constraint violation rather than a wrong answer, which is the failure worth having.
 */
public abstract class EpicsTestSupport {

  @Inject EpicRepository epicRepository;
  @Inject FeatureRepository featureRepository;
  @Inject TaskRepository taskRepository;
  @Inject AuditRepository auditRepository;
  @Inject TicketRepository ticketRepository;
  @Inject TicketCommentRepository ticketCommentRepository;

  @BeforeEach
  void wipe() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              auditRepository.deleteAll();
              ticketCommentRepository.deleteAll();
              ticketRepository.deleteAll();
              taskRepository.deleteAll();
              featureRepository.deleteAll();
              epicRepository.deleteAll();
            });
  }

  /**
   * Runs {@code assertion} inside a fresh transaction. Direct control-layer tests call several
   * transactional services on one thread with no request scope; a prior non-transactional read can
   * leave a thread-bound session whose first-level cache masks a later committed delete. Wrapping
   * the "is it gone?" assertion in a new transaction forces a fresh session so it reflects DB truth
   * (a @QuarkusTest artifact only — real HTTP requests each get their own session).
   */
  protected static void inFreshTx(Runnable assertion) {
    QuarkusTransaction.requiringNew().run(assertion);
  }
}
