package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.persistence.AuditRepository;
import eu.wohlben.qits.entities.persistence.DossierAssetRepository;
import eu.wohlben.qits.entities.persistence.DossierPageAssetRepository;
import eu.wohlben.qits.entities.persistence.DossierPageRepository;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.TicketCommentRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for epics control-layer tests: wipes every table before each test in FK-safe order so every
 * case starts from an empty planning DB. Runs against an embedded postgres this module's suite
 * spawns as a child process (see {@code testdb/EmbeddedPg} and
 * src/test/resources/application.properties) — no docker, no auth variant.
 *
 * <p>The order is the FK graph read leaves-first, and since epics V12 that graph hangs off ONE root:
 * the dossier pages, the dossier assets and the ticket comments are foreign-keyed to {@code entity}
 * now — {@code fk_ticket_comment_ticket}, {@code fk_dossier_page_owner_epic}, {@code
 * fk_dossier_page_owner_ticket} and {@code fk_dossier_asset_epic} all name {@code entity (id)} — so
 * all three have to go before {@code workEntityRepository}. The membership edges go before the
 * merged rows they point at, for the same reason. A new table wiped in the wrong place fails with a
 * constraint violation rather than a wrong answer, which is the failure worth having.
 *
 * <p><b>The four legacy wipes are gone with the four legacy tables</b> (epics V13). {@code
 * TicketCommentRepository} stays and its position does not move: a comment is not an archetype of
 * the merged model, it is still written by {@code TicketService}, and its key is {@code entity (id)}
 * — which is exactly why it goes before the merged rows and not after them.
 */
public abstract class EntitiesTestSupport {

  @Inject AuditRepository auditRepository;
  @Inject TicketCommentRepository ticketCommentRepository;
  @Inject DossierPageRepository dossierPageRepository;
  @Inject DossierAssetRepository dossierAssetRepository;
  @Inject DossierPageAssetRepository dossierPageAssetRepository;
  @Inject EntityMembershipRepository entityMembershipRepository;
  @Inject WorkEntityRepository workEntityRepository;

  /** For {@code entity_number_sequence}, which has no entity class and so no repository. */
  @Inject
  @PersistenceUnit("epics")
  EntityManager epicsEntityManager;

  @BeforeEach
  void wipe() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              auditRepository.deleteAll();
              ticketCommentRepository.deleteAll();
              dossierPageAssetRepository.deleteAll();
              dossierAssetRepository.deleteAll();
              dossierPageRepository.deleteAll();
              entityMembershipRepository.deleteAll();
              workEntityRepository.deleteAll();
              // The allocator's counters go with the rows they numbered. In production a number is
              // NEVER reused, which is exactly why this line is needed here: without it the counter
              // survives the wipe and the next test's first epic is numbered by however many rows
              // the previous one happened to create. The rule under test is uniqueness within a
              // project, not the absolute value, but a suite whose numbers depend on execution
              // order is one nobody can assert against.
              epicsEntityManager.createNativeQuery("delete from entity_number_sequence")
                  .executeUpdate();
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
