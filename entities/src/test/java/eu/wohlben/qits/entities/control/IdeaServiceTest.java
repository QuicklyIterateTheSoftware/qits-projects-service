package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.Idea;
import eu.wohlben.qits.entities.entity.IdeaStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ConflictException;
import eu.wohlben.qits.entities.error.NotFoundException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The idea rules (qits-1387): the title held to the acceptance-criterion item rules, the
 * description's three paragraphs and 600 characters, {@code enabledBy} one layer deep inside one
 * project, the archive's reason, and which work may name an idea — and the ranking (qits-1791): the
 * patch's priority, the listing's order rule, and the drop.
 */
@QuarkusTest
class IdeaServiceTest extends EntitiesTestSupport {

  private static final String PROJECT = "idea-proj";
  private static final String ELSEWHERE = "idea-elsewhere";

  @Inject IdeaService ideas;

  @Inject WorkEntityService work;

  @Inject RecordingIdeaAnnouncer announced;

  @Inject IdeaPriorityService priorities;

  @BeforeEach
  void quiet() {
    announced.clear();
  }

  private Idea idea(String title) {
    return ideas.create(PROJECT, title, null, null, "t");
  }

  private static BadRequestException refused(org.junit.jupiter.api.function.Executable write) {
    return assertThrows(BadRequestException.class, write);
  }

  // --- the title -------------------------------------------------------------------------------

  @Test
  void aNewIdeaIsPlannedAndAnnounced() {
    Idea created = ideas.create(PROJECT, "Faster builds", "Cache the deps.", null, "alice");

    assertEquals(IdeaStatus.PLANNED, created.status);
    assertFalse(created.archived);
    assertEquals("Cache the deps.", ideas.get(created.id).description);
    assertEquals(1, announced.changes().size());
    IdeaChange change = announced.changes().get(0);
    assertEquals(IdeaChange.Kind.CREATED, change.kind());
    assertEquals(created.id, change.ideaId());
    assertEquals(PROJECT, change.projectId());
    assertEquals("PLANNED", change.status());
    assertEquals("alice", change.changedBy());
  }

  @Test
  void theTitleIsOneShortLine() {
    assertTrue(refused(() -> idea(" ")).getMessage().contains("title is required"));
    assertTrue(refused(() -> idea(null)).getMessage().contains("title is required"));
    assertTrue(
        refused(() -> idea("Two\nlines")).getMessage().contains("title contains a line break"));
    assertTrue(refused(() -> idea("One. Two. Three")).getMessage().contains("'.' characters"));
    String twentyGaps = "a b c d e f g h i j k l m n o p q r s t u";
    assertTrue(refused(() -> idea(twentyGaps)).getMessage().contains("whitespace characters"));
    assertEquals(0, announced.changes().size(), "a refused idea is announced to nobody");

    String nineteenGaps = "a b c d e f g h i j k l m n o p q r s t";
    assertNotNull(idea(nineteenGaps).id);
    assertNotNull(idea("One sentence.").id);
  }

  @Test
  void anEditKeepsTheTitleRuleAndCannotClearIt() {
    Idea idea = idea("Before");
    assertThrows(
        BadRequestException.class,
        () -> ideas.update(idea.id, new IdeaService.IdeaEdit("a.b.c", null, false, null, false), "t"));
    assertThrows(
        BadRequestException.class,
        () -> ideas.update(idea.id, new IdeaService.IdeaEdit(null, null, false, null, false), "t"));

    Idea renamed =
        ideas.update(idea.id, new IdeaService.IdeaEdit("After", null, false, null, false), "t");
    assertEquals("After", renamed.title);
  }

  // --- the description -------------------------------------------------------------------------

  @Test
  void aDescriptionHoldsAtMostThreeParagraphs() {
    assertEquals(1, IdeaService.paragraphs("one line\nand its continuation"));
    assertEquals(3, IdeaService.paragraphs("one\n\ntwo\n\n\n\nthree"));
    assertEquals(2, IdeaService.paragraphs("one\n  \t\ntwo"));
    assertEquals(2, IdeaService.paragraphs("one\r\n\r\ntwo"));

    assertEquals(
        "one\n\ntwo\n\nthree",
        ideas.create(PROJECT, "Three", "\n\none\n\ntwo\n\nthree\n\n", null, "t").description);
    BadRequestException four =
        refused(() -> ideas.create(PROJECT, "Four", "one\n\ntwo\n\nthree\n\nfour", null, "t"));
    assertTrue(four.getMessage().contains("4 paragraphs"), four.getMessage());
  }

  @Test
  void aDescriptionHoldsAtMostSixHundredCharacters() {
    String six = "x".repeat(600);
    assertEquals(600, ideas.create(PROJECT, "Long", six, null, "t").description.length());
    BadRequestException over =
        refused(() -> ideas.create(PROJECT, "Longer", six + "y", null, "t"));
    assertTrue(over.getMessage().contains("601 characters"), over.getMessage());

    Idea idea = idea("Edited");
    assertThrows(
        BadRequestException.class,
        () -> ideas.update(idea.id, new IdeaService.IdeaEdit(null, six + "y", false, null, false), "t"));
    assertNull(
        ideas.update(idea.id, new IdeaService.IdeaEdit(null, null, true, null, false), "t")
            .description);
  }

  // --- enabledBy -------------------------------------------------------------------------------

  @Test
  void anIdeaIsEnabledByOneIdeaOfItsProject() {
    Idea enabler = idea("Enabler");
    Idea enabled = ideas.create(PROJECT, "Enabled", null, enabler.id, "t");

    assertEquals(enabler.id, enabled.enabledBy);
    assertEquals(List.of(enabled.id), ideas.enables(enabler.id).stream().map(i -> i.id).toList());
  }

  @Test
  void anIdeaIsNotEnabledByItself() {
    Idea idea = idea("Self");
    BadRequestException self =
        refused(
            () ->
                ideas.update(
                    idea.id, new IdeaService.IdeaEdit(null, null, false, idea.id, false), "t"));
    assertTrue(self.getMessage().contains("itself"), self.getMessage());
  }

  @Test
  void theTargetMayNotItselfBeEnabled() {
    Idea root = idea("Root");
    Idea middle = ideas.create(PROJECT, "Middle", null, root.id, "t");

    BadRequestException deep =
        refused(() -> ideas.create(PROJECT, "Leaf", null, middle.id, "t"));
    assertTrue(deep.getMessage().contains("one layer deep"), deep.getMessage());
  }

  @Test
  void anIdeaThatEnablesOthersIsNotGivenAnEnabler() {
    Idea enabler = idea("Enabler");
    ideas.create(PROJECT, "Enabled", null, enabler.id, "t");
    Idea other = idea("Other");

    BadRequestException deep =
        refused(
            () ->
                ideas.update(
                    enabler.id, new IdeaService.IdeaEdit(null, null, false, other.id, false), "t"));
    assertTrue(deep.getMessage().contains("already enables"), deep.getMessage());
  }

  @Test
  void theTargetIsOfTheSameProjectAndExists() {
    Idea foreign = ideas.create(ELSEWHERE, "Foreign", null, null, "t");
    BadRequestException cross = refused(() -> ideas.create(PROJECT, "Here", null, foreign.id, "t"));
    assertTrue(cross.getMessage().contains("same project"), cross.getMessage());

    BadRequestException unknown = refused(() -> ideas.create(PROJECT, "Here", null, "nope", "t"));
    assertTrue(unknown.getMessage().contains("names no idea"), unknown.getMessage());
  }

  @Test
  void anArchivedTargetIsAllowedAndNullClears() {
    Idea enabler = idea("Archived enabler");
    ideas.archive(enabler.id, "parked", "t");
    Idea enabled = ideas.create(PROJECT, "Still enabled", null, enabler.id, "t");
    assertEquals(enabler.id, enabled.enabledBy);

    Idea cleared =
        ideas.update(enabled.id, new IdeaService.IdeaEdit(null, null, false, null, true), "t");
    assertNull(cleared.enabledBy);
  }

  // --- the archive -----------------------------------------------------------------------------

  @Test
  void archivingNeedsAReasonAndKeepsTheStatus() {
    Idea idea = idea("To archive");
    assertThrows(BadRequestException.class, () -> ideas.archive(idea.id, " ", "t"));
    assertThrows(BadRequestException.class, () -> ideas.archive(idea.id, null, "t"));
    assertThrows(NotFoundException.class, () -> ideas.archive("missing", "why", "t"));
    announced.clear();

    Idea archived = ideas.archive(idea.id, "superseded by another", "t");
    assertTrue(archived.archived);
    assertEquals("superseded by another", archived.archiveReason);
    assertNotNull(archived.archivedAt);
    assertEquals(IdeaStatus.PLANNED, archived.status);
    assertEquals(List.of(), ideas.list(PROJECT, false, null).stream().filter(i -> i.id.equals(idea.id)).toList());
    assertEquals(1, ideas.list(PROJECT, true, null).stream().filter(i -> i.id.equals(idea.id)).count());

    Idea back = ideas.unarchive(idea.id, "t");
    assertFalse(back.archived);
    assertNull(back.archiveReason);
    assertNull(back.archivedAt);
    assertEquals(
        List.of(IdeaChange.Kind.ARCHIVED, IdeaChange.Kind.UNARCHIVED),
        announced.changes().stream().map(IdeaChange::kind).toList());
  }

  // --- the work that names an idea -------------------------------------------------------------

  @Test
  void onlyAnEpicATicketOrACampaignIsInspired() {
    Idea idea = idea("Inspiring");
    WorkEntity epic =
        work.create(
                Archetype.EPIC,
                PROJECT,
                EntityWrite.epic("Inspired epic", null).withInspiredBy(idea.id, false),
                "t")
            .entity();
    assertEquals(idea.id, epic.inspiredByIdeaId);

    BadRequestException feature =
        refused(
            () ->
                work.create(
                    Archetype.FEATURE,
                    epic.id,
                    EntityWrite.feature("A feature", null, null).withInspiredBy(idea.id, false),
                    "t"));
    assertTrue(feature.getMessage().contains("FEATURE"), feature.getMessage());
  }

  @Test
  void anIdeaOfAnotherProjectIsRefusedAndAnUnknownOne() {
    Idea foreign = ideas.create(ELSEWHERE, "Foreign", null, null, "t");
    BadRequestException cross =
        refused(
            () ->
                work.create(
                    Archetype.EPIC,
                    PROJECT,
                    EntityWrite.epic("Epic", null).withInspiredBy(foreign.id, false),
                    "t"));
    assertTrue(cross.getMessage().contains("same project"), cross.getMessage());

    BadRequestException unknown =
        refused(
            () ->
                work.create(
                    Archetype.EPIC,
                    PROJECT,
                    EntityWrite.epic("Epic", null).withInspiredBy("missing", false),
                    "t"));
    assertTrue(unknown.getMessage().contains("names no idea"), unknown.getMessage());
  }

  // --- the ranking (qits-1791) -----------------------------------------------------------------

  private static IdeaService.IdeaEdit priority(Integer value) {
    return new IdeaService.IdeaEdit(null, null, false, null, false)
        .withPriority(value, value == null);
  }

  private void markDone(Idea idea) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                epicsEntityManager
                    .createNativeQuery("update idea set status = 'DONE' where id = ?1")
                    .setParameter(1, idea.id)
                    .executeUpdate());
  }

  /** Read in a transaction of its own: a read outside one would answer the test's cached rows. */
  private List<String> listed() {
    return QuarkusTransaction.requiringNew()
        .call(() -> ideas.list(PROJECT, false, null).stream().map(i -> i.title).toList());
  }

  private Integer priorityOf(Idea idea) {
    return QuarkusTransaction.requiringNew().call(() -> ideaRepository.findById(idea.id).priority);
  }

  @Test
  void anEditSetsAndClearsThePriorityAndAnnouncesIt() {
    Idea idea = idea("Ranked");
    announced.clear();

    assertEquals(4, ideas.update(idea.id, priority(4), "t").priority);
    assertEquals(4, priorityOf(idea));
    assertNull(ideas.update(idea.id, priority(null), "t").priority);
    assertEquals(
        List.of(IdeaChange.Kind.UPDATED, IdeaChange.Kind.UPDATED),
        announced.changes().stream().map(IdeaChange::kind).toList());
  }

  @Test
  void aPriorityOnAnArchivedOrDoneIdeaIsA409AndArchivingClearsIt() {
    Idea archived = idea("Parked");
    ideas.update(archived.id, priority(3), "t");
    assertNull(ideas.archive(archived.id, "not now", "t").priority, "the archive clears it");
    assertNull(ideas.unarchive(archived.id, "t").priority, "unarchived, it comes back unranked");
    ideas.archive(archived.id, "not now", "t");
    ConflictException onArchived =
        assertThrows(ConflictException.class, () -> ideas.update(archived.id, priority(1), "t"));
    assertTrue(onArchived.getMessage().contains("archived"), onArchived.getMessage());

    Idea done = idea("Finished");
    markDone(done);
    ConflictException onDone =
        assertThrows(ConflictException.class, () -> ideas.update(done.id, priority(1), "t"));
    assertTrue(onDone.getMessage().contains("DONE"), onDone.getMessage());
    assertThrows(ConflictException.class, () -> ideas.update(done.id, priority(null), "t"));
  }

  @Test
  void theListingAnswersInTheOrderRule() {
    Idea first = idea("First");
    Idea second = idea("Second");
    Idea third = idea("Third");
    assertEquals(List.of("First", "Second", "Third"), listed(), "unranked is oldest first");

    ideas.update(third.id, priority(2), "t");
    ideas.update(second.id, priority(5), "t");
    assertEquals(List.of("Second", "Third", "First"), listed(), "ranked first, nulls last");

    ideas.update(first.id, priority(2), "t");
    assertEquals(List.of("Second", "First", "Third"), listed(), "a tie is broken oldest first");
  }

  @Test
  void aDropRanksTheIdeaAndThoseAboveItAndAnnouncesWhatMoved() {
    Idea a = idea("A");
    Idea b = idea("B");
    Idea c = idea("C");
    Idea d = idea("D");
    ideas.update(a.id, priority(2), "t");
    announced.clear();

    // d directly below a: a keeps 2, d takes 1, b and c are left alone.
    assertEquals(
        List.of(new IdeaPriorityService.Ranked(a.id, 2), new IdeaPriorityService.Ranked(d.id, 1)),
        priorities.reprioritize(d.id, a.id, SiblingPriorities.Placement.BELOW, "alice"));
    assertEquals(List.of("A", "D", "B", "C"), listed());
    assertEquals(
        List.of(d.id),
        announced.changes().stream().map(IdeaChange::ideaId).toList(),
        "only the idea whose priority moved is announced");
    assertEquals(IdeaChange.Kind.UPDATED, announced.changes().get(0).kind());
    assertEquals("alice", announced.changes().get(0).changedBy());
    assertNull(priorityOf(b));

    announced.clear();
    priorities.reprioritize(c.id, a.id, SiblingPriorities.Placement.ABOVE, "t");
    assertEquals(List.of("C", "A", "D", "B"), listed());
    assertEquals(List.of(c.id), announced.changes().stream().map(IdeaChange::ideaId).toList());
  }

  @Test
  void aDropRefusesWhatIsNotRankable() {
    Idea idea = idea("Mover");
    Idea anchor = idea("Anchor");
    Idea parked = idea("Parked");
    ideas.archive(parked.id, "not now", "t");
    Idea done = idea("Done");
    markDone(done);
    Idea foreign = ideas.create(ELSEWHERE, "Foreign", null, null, "t");
    SiblingPriorities.Placement above = SiblingPriorities.Placement.ABOVE;

    assertThrows(NotFoundException.class, () -> priorities.reprioritize("missing", anchor.id, above, "t"));
    assertThrows(ConflictException.class, () -> priorities.reprioritize(parked.id, anchor.id, above, "t"));
    assertThrows(ConflictException.class, () -> priorities.reprioritize(done.id, anchor.id, above, "t"));
    assertTrue(refused(() -> priorities.reprioritize(idea.id, idea.id, above, "t")).getMessage().contains("itself"));
    assertTrue(refused(() -> priorities.reprioritize(idea.id, "missing", above, "t")).getMessage().contains("names no idea"));
    for (Idea notRankable : List.of(parked, done, foreign)) {
      assertTrue(
          refused(() -> priorities.reprioritize(idea.id, notRankable.id, above, "t"))
              .getMessage()
              .contains("not a rankable idea"));
    }
    assertNull(priorityOf(idea), "a refused drop writes nothing");
  }
}
