package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.DossierAsset;
import eu.wohlben.qits.epics.entity.DossierOwner;
import eu.wohlben.qits.epics.entity.DossierPage;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.Ticket;
import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.epics.error.ConflictException;
import eu.wohlben.qits.epics.error.NotFoundException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The dossier's second owner (V8): a ticket, beside the epic it already had.
 *
 * <p>The cases here are the ones that carry the decision rather than the happy path. <b>Exactly one
 * owner</b> is the database's rule and is asserted against the database, because it is what makes
 * two nullable FK columns a safe shape instead of a polymorphic pair. <b>Nothing freezes</b> is the
 * whole difference from the epic half: a ticket's page is writable while the ticket is REPORTED,
 * IMPLEMENTED and DONE alike, while an epic's is still refused outside REFINING. And <b>a ticket
 * page has no figures</b> — {@code dossier_asset} is deliberately epic-only, so markdown naming an
 * asset on a ticket page copies nothing and references nothing.
 */
@QuarkusTest
class DossierTicketOwnerTest extends EpicsTestSupport {

  @Inject EpicService epicService;
  @Inject TicketService ticketService;
  @Inject DossierService dossier;
  @Inject DossierAssetService assets;
  @Inject AuditService auditService;

  private Epic epic() {
    return epicService.create("proj-1", "Epic", null, "t");
  }

  private Ticket ticket() {
    return ticketService.create("proj-1", "The button is wrong", "It is wrong.", null, "BUG", null, "t");
  }

  // --- the constraint -------------------------------------------------------

  @Test
  void theDatabaseRefusesAPageWithTwoOwnersAndOneWithNone() {
    Epic e = epic();
    Ticket t = ticket();

    String both = insertFailure(e.id, t.id);
    assertTrue(both.contains("ck_dossier_page_owner"), both);

    String neither = insertFailure(null, null);
    assertTrue(neither.contains("ck_dossier_page_owner"), neither);

    // And one owner is accepted, so the two refusals above are the constraint and not the insert.
    assertNotNull(dossier.create(DossierOwner.ticket(t.id), "One", "", "t").id);
  }

  /** The message of the failure a direct insert produces, so the check itself is what is asserted. */
  private String insertFailure(String epicId, String ticketId) {
    Throwable refused =
        assertThrows(
            Throwable.class,
            () ->
                QuarkusTransaction.requiringNew()
                    .run(
                        () ->
                            dossierPageRepository
                                .getEntityManager()
                                .createNativeQuery(
                                    "insert into dossier_page (id, epic_id, ticket_id, slug, title,"
                                        + " position, body, version, created_at, updated_at)"
                                        + " values (?1, ?2, ?3, 'a-page', 'A page', 0, '', 0, ?4,"
                                        + " ?4)")
                                .setParameter(1, UUID.randomUUID().toString())
                                .setParameter(2, epicId)
                                .setParameter(3, ticketId)
                                .setParameter(4, Instant.now())
                                .executeUpdate()));
    StringBuilder all = new StringBuilder();
    for (Throwable cause = refused; cause != null; cause = cause.getCause()) {
      all.append(cause.getMessage()).append('\n');
    }
    return all.toString();
  }

  // --- nothing freezes ------------------------------------------------------

  @Test
  void aTicketOwnedPageIsWritableAtEveryStatus() {
    Ticket t = ticket();
    DossierOwner owner = DossierOwner.ticket(t.id);

    // REPORTED: the refine phase's own write.
    DossierPage page = dossier.create(owner, "The root cause", "four services deep", "t");
    assertEquals(TicketStatus.REPORTED, ticketService.get(t.id).status);

    ticketService.transition(t.id, TicketStatus.REFINED.name(), "t");
    ticketService.transition(t.id, TicketStatus.IMPLEMENTED.name(), "t");
    // IMPLEMENTED: the implement phase correcting what it found wrong is the ordinary case.
    DossierPage rewritten = dossier.update(page.id, null, "five, as it turns out", 0L, "t");
    assertEquals("five, as it turns out", rewritten.body);

    ticketService.transition(t.id, TicketStatus.VERIFIED.name(), "t");
    ticketService.transition(t.id, TicketStatus.DONE.name(), "t");
    // DONE: a closed ticket stays editable, exactly as its description and its thread do.
    DossierPage second = dossier.create(owner, "What verifying looked like", "", "t");
    dossier.move(second.id, 0, "t");
    assertEquals(List.of("What verifying looked like", "The root cause"), titles(owner));
    dossier.delete(second.id, "t");
    assertEquals(List.of("The root cause"), titles(owner));
  }

  @Test
  void anEpicOwnedPageIsStillRefusedOutsideRefining() {
    Epic e = epic();
    DossierOwner owner = DossierOwner.epic(e.id);
    DossierPage page = dossier.create(owner, "The claim loop", "the body", "t");
    epicService.transition(e.id, "IMPLEMENTATION", "t");

    assertThrows(ConflictException.class, () -> dossier.create(owner, "Another", "", "t"));
    assertThrows(ConflictException.class, () -> dossier.update(page.id, "New", null, 0L, "t"));
  }

  // --- ownership ------------------------------------------------------------

  @Test
  void slugsCollideWithinAnOwnerAndNeverAcrossOwners() {
    Ticket t = ticket();
    Epic e = epic();

    assertEquals("the-claim-loop", dossier.create(DossierOwner.ticket(t.id), "The claim loop", "", "t").slug);
    assertEquals(
        "the-claim-loop-2",
        dossier.create(DossierOwner.ticket(t.id), "The claim loop", "", "t").slug,
        "a second page of the same ticket suffixes");

    // The epic is another scope entirely: the clean slug is free there, and the two never meet.
    assertEquals("the-claim-loop", dossier.create(DossierOwner.epic(e.id), "The claim loop", "", "t").slug);
  }

  @Test
  void deletingATicketTakesItsPages() {
    Ticket t = ticket();
    DossierPage page = dossier.create(DossierOwner.ticket(t.id), "The root cause", "", "t");

    ticketService.delete(t.id, "t");

    inFreshTx(
        () -> {
          assertThrows(NotFoundException.class, () -> dossier.get(page.id));
          assertTrue(dossier.listByOwner(DossierOwner.ticket(t.id)).isEmpty());
        });
  }

  @Test
  void aTicketOwnedPagesAuditEntriesCarryTheTicketAsTheirSubtreeKey() {
    Ticket t = ticket();
    DossierPage page = dossier.create(DossierOwner.ticket(t.id), "The root cause", "", "t");

    // V4's reading of auditentry.epic_id is the subtree key, not literally an epic — so the whole
    // history of this ticket keeps including what its pages did, with no schema change.
    assertTrue(
        auditService.listForEpic(t.id).stream()
            .anyMatch(
                entry ->
                    entry.entityType == AuditEntityType.DOSSIER_PAGE
                        && entry.entityId.equals(page.id)));
  }

  // --- figures are epic-only ------------------------------------------------

  @Test
  void aTicketPageNamingAnAssetNeitherCopiesItNorReferencesIt() {
    Epic e = epic();
    Ticket t = ticket();
    String assetId = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                assets.copyFrom(
                    e.id,
                    assetId,
                    DossierAsset.Kind.IMAGE,
                    "png-bytes".getBytes(StandardCharsets.UTF_8),
                    "image/png",
                    "The claim loop"));

    String markdown =
        "see ![The claim loop](" + DossierAssetService.contentUrl(e.id, assetId) + ")";
    DossierPage page = dossier.create(DossierOwner.ticket(t.id), "The root cause", markdown, "t");

    inFreshTx(
        () -> {
          assertTrue(
              dossierPageAssetRepository.listByPage(page.id).isEmpty(),
              "a ticket page writes no dossier_page_asset row");
          assertFalse(
              dossierPageAssetRepository.anyReferences(assetId),
              "and nothing counted it as a reference");
          // The epic's own asset is untouched: the ticket page neither copied it nor collected it.
          assertNotNull(dossierAssetRepository.findById(assetId));
        });
  }

  private List<String> titles(DossierOwner owner) {
    return dossier.listByOwner(owner).stream().map(page -> page.title).toList();
  }
}
