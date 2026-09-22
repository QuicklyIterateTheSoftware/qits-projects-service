package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditEntry;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.EpicStatus;
import eu.wohlben.qits.entities.entity.TicketStatus;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The multi-entity transition, asserted as the thing it exists to be: <b>one post-state, validated
 * whole, applied whole</b>.
 *
 * <p>The first test in this class is the one that must fail loudest. A feature becoming an epic
 * while its tasks are rescoped has <em>no</em> legal expression as a sequence of single-entity
 * writes — re-archetype first and there is an epic under an epic, reparent first and there is a
 * feature at the root, move the tasks first and they hang under something still shaped as a feature
 * — so if atomicity ever regresses, that case is where it shows.
 *
 * <p>It is a {@code @QuarkusTest} on {@link EntitiesTestSupport} and adds <b>no {@code @TestProfile}</b>
 * of its own: a profile is a whole Quarkus application at roughly 125 MB of retained metaspace
 * inside a 4 GB CI step, and nothing here needs a different configuration.
 */
@QuarkusTest
class EntityTransitionServiceTest extends EntitiesTestSupport {

  private static final String PROJECT = "proj-transition";
  private static final String WHO = "tester";

  @Inject EntityTransitionService transitions;
  @Inject EpicService epicService;
  @Inject FeatureService featureService;
  @Inject TaskService taskService;
  @Inject TicketService ticketService;
  @Inject AuditService auditService;
  @Inject WorkEntityRepository entities;
  @Inject EntityMembershipRepository memberships;
  @Inject RecordingTransitionAnnouncer announcer;

  @BeforeEach
  void forgetAnnouncements() {
    announcer.clear();
  }

  // --- the one that matters most -------------------------------------------

  /**
   * <b>A feature becomes an epic while its tasks are rescoped, in ONE request.</b>
   *
   * <p>Two tasks hang under a feature that hangs under an epic. Afterwards the feature is an epic of
   * its own at the root, one task has stayed with it, and the other has moved across to the original
   * epic. Every one of those three facts is illegal in isolation against the shape that precedes it,
   * and the whole post-state is a legal tree.
   */
  @Test
  void aFeatureBecomesAnEpicWhileItsTasksAreRescopedInOneRequest() {
    WorkEntity epic = epicService.create(PROJECT, "The plan", null, WHO);
    Nested feature = featureService.create(epic.id, "The part", "a part of the plan", null, WHO);
    Nested staying = taskService.create(feature.entity().id, "repo-1", "Stays", null, null, WHO);
    Nested moving = taskService.create(feature.entity().id, "repo-2", "Moves", null, null, WHO);

    Map<String, TransitionedEntity> after =
        transitions.transition(
            stated(
                feature.entity().id,
                epicEntry("The part", "a part of the plan", null, EpicStatus.REFINING),
                staying.entity().id,
                taskEntry(feature.entity().id, 0, "Stays", "repo-1"),
                moving.entity().id,
                taskEntry(epic.id, 0, "Moves", "repo-2")),
            WHO);

    assertEquals(3, after.size(), "one answer per stated entity, keyed as the request was");
    assertEquals(Archetype.EPIC, after.get(feature.entity().id).archetype());
    assertNull(after.get(feature.entity().id).parent(), "the promoted feature stands at the root");
    assertEquals(
        EpicStatus.REFINING.name(), after.get(feature.entity().id).status(), "an epic must have a phase");
    assertEquals(feature.entity().id, after.get(staying.entity().id).parent());
    assertEquals(epic.id, after.get(moving.entity().id).parent());

    inFreshTx(
        () -> {
          WorkEntity promoted = entities.findById(feature.entity().id);
          assertEquals(Archetype.EPIC, promoted.archetype, "the row itself is an epic now");
          assertEquals(
              PROJECT, promoted.slugScope, "a root's slug scope is its project, recomputed here");
          assertEquals(
              feature.entity().slug,
              promoted.slug,
              "a move never re-mints a slug — branches are cut on it");
          assertTrue(memberships.membershipOf(feature.entity().id).isEmpty(), "a root has no edge");

          assertEquals(List.of(staying.entity().id), childIdsOf(feature.entity().id));
          assertEquals(List.of(moving.entity().id), childIdsOf(epic.id));
          assertEquals(List.of(0), positionsUnder(feature.entity().id));
          assertEquals(
              List.of(0), positionsUnder(epic.id), "the old parent ends dense and zero-based too");
          assertEquals(
              feature.entity().id,
              entities.findById(staying.entity().id).slugScope,
              "a task's slug scope is its parent, and the parent is the promoted row");
          assertEquals(epic.id, entities.findById(moving.entity().id).slugScope);
        });
  }

  // --- atomicity ------------------------------------------------------------

  /**
   * <b>Nothing is applied when any part is refused.</b> The valid half of the request is a promotion
   * that would otherwise be written; the invalid half is a task with no repository id. Every row is
   * re-read afterwards, and the whole tree is exactly as it was.
   */
  @Test
  void nothingIsAppliedWhenAnyPartIsRefused() {
    WorkEntity epic = epicService.create(PROJECT, "The plan", null, WHO);
    Nested feature = featureService.create(epic.id, "The part", null, null, WHO);
    Nested task = taskService.create(feature.entity().id, "repo-1", "The work", null, null, WHO);

    BadRequestException refusal =
        assertThrows(
            BadRequestException.class,
            () ->
                transitions.transition(
                    stated(
                        feature.entity().id,
                        epicEntry("The part", null, null, EpicStatus.REFINING),
                        task.entity().id,
                        new EntityTransition(
                            Archetype.TASK,
                            new EntityTransition.Membership(feature.entity().id, 0),
                            "The work",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null, // no repository id, which a TASK requires
                            null,
                            null)),
                    WHO));
    assertTrue(refusal.getMessage().contains("repository id"), refusal.getMessage());

    inFreshTx(
        () -> {
          assertEquals(
              Archetype.FEATURE,
              entities.findById(feature.entity().id).archetype,
              "the valid half of a refused request is not written either");
          assertEquals(epic.id, memberships.membershipOf(feature.entity().id).orElseThrow().parentId);
          assertEquals(feature.entity().id, memberships.membershipOf(task.entity().id).orElseThrow().parentId);
          assertEquals("repo-1", entities.findById(task.entity().id).repositoryId);
          assertEquals(epic.id, entities.findById(feature.entity().id).slugScope);
        });
    assertEquals(List.of(), announcer.batches(), "a refused post-state is announced to nobody");
  }

  // --- the same trap, N times over ------------------------------------------

  /**
   * Exploding an epic's features into tickets: three promotions to a root archetype at once, each
   * one illegal on its own until the others have happened.
   */
  @Test
  void anEpicIsExplodedIntoSeveralTickets() {
    WorkEntity epic = epicService.create(PROJECT, "Too much at once", null, WHO);
    Nested one = featureService.create(epic.id, "First", null, null, WHO);
    Nested two = featureService.create(epic.id, "Second", null, null, WHO);
    Nested three = featureService.create(epic.id, "Third", null, null, WHO);

    Map<String, TransitionedEntity> after =
        transitions.transition(
            stated(
                one.entity().id, ticketEntry("First", "it came up"),
                two.entity().id, ticketEntry("Second", "it came up"),
                three.entity().id, ticketEntry("Third", "it came up")),
            WHO);

    for (String id : List.of(one.entity().id, two.entity().id, three.entity().id)) {
      assertEquals(Archetype.TICKET, after.get(id).archetype());
      assertNull(after.get(id).parent(), "a ticket is a root");
      assertEquals(TicketStatus.REPORTED.name(), after.get(id).status());
      assertEquals(PROJECT, after.get(id).slugScope());
    }
    inFreshTx(
        () -> {
          assertEquals(List.of(), childIdsOf(epic.id), "the epic keeps nothing");
          for (WorkEntity ticket : ticketService.listByProject(PROJECT)) {
            assertEquals(TicketType.BUG, ticket.ticketType);
          }
        });
  }

  // --- the ordinary write, as a map of one ---------------------------------

  /** A plain single-entity edit expressed as a map of one — the ordinary write path now. */
  @Test
  void aPlainEditIsAMapOfOne() {
    WorkEntity epic = epicService.create(PROJECT, "Before", "the old body", WHO);

    Map<String, TransitionedEntity> after =
        transitions.transition(
            stated(epic.id, epicEntry("After", "the new body", null, EpicStatus.REFINING)), WHO);

    assertEquals("After", after.get(epic.id).title());
    assertEquals("the new body", after.get(epic.id).description());
    assertEquals(epic.slug, after.get(epic.id).slug(), "a retitle never moves the slug");
    inFreshTx(() -> assertEquals("After", entities.findById(epic.id).title));
  }

  // --- resolution of a parent inside the map -------------------------------

  /**
   * A parent named only within the map: the feature's new parent is the entity being promoted in
   * the same request, which does not exist as an epic anywhere until the request commits.
   */
  @Test
  void aParentMayBeNamedOnlyWithinTheMap() {
    WorkEntity epic = epicService.create(PROJECT, "The plan", null, WHO);
    Nested promoted = featureService.create(epic.id, "Becomes an epic", null, null, WHO);
    Nested child = featureService.create(epic.id, "Goes under it", null, null, WHO);

    transitions.transition(
        stated(
            promoted.entity().id,
            epicEntry("Becomes an epic", null, null, EpicStatus.REFINING),
            child.entity().id,
            featureEntry(promoted.entity().id, null, "Goes under it")),
        WHO);

    inFreshTx(
        () -> {
          assertEquals(Archetype.EPIC, entities.findById(promoted.entity().id).archetype);
          assertEquals(promoted.entity().id, memberships.membershipOf(child.entity().id).orElseThrow().parentId);
          assertEquals(List.of(), childIdsOf(epic.id));
        });
  }

  /** A parent that is in neither the map nor the store is a refusal and never a create. */
  @Test
  void aParentThatIsInNeitherTheMapNorTheStoreIsRefused() {
    WorkEntity epic = epicService.create(PROJECT, "The plan", null, WHO);
    Nested feature = featureService.create(epic.id, "The part", null, null, WHO);

    BadRequestException refusal =
        assertThrows(
            BadRequestException.class,
            () ->
                transitions.transition(
                    stated(feature.entity().id, featureEntry("no-such-entity", null, "The part")), WHO));
    assertTrue(refusal.getMessage().contains("no-such-entity"), refusal.getMessage());

    inFreshTx(
        () -> {
          assertEquals(epic.id, memberships.membershipOf(feature.entity().id).orElseThrow().parentId);
          assertNull(entities.findById("no-such-entity"), "nothing was created for the unknown id");
        });
  }

  /** An id in the map that names no row is a violation collected with the rest, not a 404. */
  @Test
  void anIdInTheMapThatNamesNothingIsCollectedAsAViolation() {
    WorkEntity epic = epicService.create(PROJECT, "The plan", null, WHO);

    BadRequestException refusal =
        assertThrows(
            BadRequestException.class,
            () ->
                transitions.transition(
                    stated(
                        epic.id,
                        epicEntry("The plan", null, null, EpicStatus.REFINING),
                        "ghost-id",
                        epicEntry("A ghost", null, null, EpicStatus.REFINING)),
                    WHO));
    assertEquals(400, refusal.statusCode(), "a caller fixes every id in one round trip");
    assertTrue(refusal.getMessage().contains("there is no ghost-id"), refusal.getMessage());
  }

  // --- the PUT rule ---------------------------------------------------------

  /**
   * <b>A demotion that drops a property clears it in the database.</b> A ticket becoming a feature
   * loses its impetus, its assignee, its ticket type, its status and its {@code createdBy} — the
   * last of which is server-owned and is cleared rather than refused, because the caller could not
   * have written it.
   */
  @Test
  void aDemotionActuallyClearsTheDroppedProperties() {
    WorkEntity epic = epicService.create(PROJECT, "The plan", null, WHO);
    WorkEntity ticket =
        ticketService.create(PROJECT, "Turns out to be scope", "it came up", null, "BUG", "me", WHO);

    transitions.transition(stated(ticket.id, featureEntry(epic.id, null, "Turns out to be scope")), WHO);

    inFreshTx(
        () -> {
          WorkEntity row = entities.findById(ticket.id);
          assertEquals(Archetype.FEATURE, row.archetype);
          assertNull(row.impetus, "a FEATURE has no impetus");
          assertNull(row.assignee, "a FEATURE has no assignee");
          assertNull(row.ticketType, "a FEATURE has no ticket type");
          assertNull(row.status, "a FEATURE has no lifecycle at all");
          assertNull(row.createdBy, "server-owned, and cleared because the target has no slot");
          assertEquals(ticket.slug, row.slug, "the slug is the one thing a demotion never touches");
          assertEquals(epic.id, row.slugScope);
        });
  }

  /** A demotion carrying a property the target has no slot for is refused, never silently dropped. */
  @Test
  void aDemotionCarryingAForeignPropertyIsRefused() {
    WorkEntity epic = epicService.create(PROJECT, "The plan", null, WHO);
    WorkEntity ticket =
        ticketService.create(PROJECT, "Turns out to be scope", "it came up", null, "BUG", null, WHO);

    BadRequestException refusal =
        assertThrows(
            BadRequestException.class,
            () ->
                transitions.transition(
                    stated(
                        ticket.id,
                        new EntityTransition(
                            Archetype.FEATURE,
                            new EntityTransition.Membership(epic.id, null),
                            "Turns out to be scope",
                            null,
                            null,
                            null,
                            "it came up", // an impetus, on a kind that has no slot for one
                            null,
                            null,
                            null,
                            null,
                            null)),
                    WHO));
    assertTrue(refusal.getMessage().contains("has no impetus"), refusal.getMessage());
    inFreshTx(() -> assertEquals(Archetype.TICKET, entities.findById(ticket.id).archetype));
  }

  /**
   * <b>A reshape leaves {@code blocked} exactly where it was, and that is what keeps it OUT of the
   * registry.</b>
   *
   * <p>This operation is a full-state PUT over the <em>declared</em> properties: {@link
   * EntityTransition}'s rule is that an omitted property is cleared, and the test above is that rule
   * being kept. So a flag declared as an {@link EntityProperty} would be cleared by every reshape
   * that did not restate it — and no caller restates a property it does not know exists. A tree
   * being rescoped would silently unblock every blocked ticket it touched: a write nobody asked for,
   * arriving through a door about something else, reported nowhere. That is the reason {@code
   * WorkEntity.blocked} is a real column the registry is indifferent to, the way {@code createdAt}
   * and {@code updatedAt} are, and this test is what makes that indifference a fact rather than an
   * omission somebody tidies up later.
   *
   * <p>The reshape here genuinely goes through the write path, which is the whole of what makes the
   * assertion mean anything: the title moves and the assignee is dropped, so the PUT's clear is
   * demonstrably exercised in the same call — a reshape restating the row unchanged would pass while
   * proving nothing. The failure this prevents is therefore precise: add {@code BLOCKED} to {@link
   * EntityProperty} and thread it through {@link EntityTransition} and {@link TransitionedEntity},
   * and the assignee still clears, the title still moves, and the block is gone.
   */
  @Test
  void aReshapeLeavesABlockedTicketBlocked() {
    WorkEntity ticket =
        ticketService.create(PROJECT, "The gate times out", "it came up", null, "BUG", "me", WHO);
    ticketService.setBlocked(ticket.id, true, WHO);

    Map<String, TransitionedEntity> after =
        transitions.transition(
            stated(
                ticket.id,
                new EntityTransition(
                    Archetype.TICKET,
                    null,
                    "The gate times out on a cold cache", // restated, and changed
                    null,
                    TicketStatus.REPORTED.name(),
                    TicketType.BUG,
                    "it came up",
                    null, // the assignee is not restated, so the PUT clears it
                    null,
                    null,
                    null,
                    null)),
            WHO);

    assertEquals("The gate times out on a cold cache", after.get(ticket.id).title());
    assertNull(
        after.get(ticket.id).assignee(),
        "the omitted property really was cleared — the write path ran");

    inFreshTx(
        () -> {
          WorkEntity row = entities.findById(ticket.id);
          assertEquals("The gate times out on a cold cache", row.title);
          assertNull(row.assignee, "and the clear reached the row, not merely the answer");
          assertTrue(
              row.blocked,
              "a reshape says nothing about whether somebody is stuck, so it may not unblock");
        });
  }

  /** Both validation layers report together, in one refusal, so a caller fixes everything once. */
  @Test
  void violationsFromBothLayersComeBackTogether() {
    WorkEntity epic = epicService.create(PROJECT, "The plan", null, WHO);
    Nested feature = featureService.create(epic.id, "The part", null, null, WHO);

    BadRequestException refusal =
        assertThrows(
            BadRequestException.class,
            () ->
                transitions.transition(
                    stated(
                        feature.entity().id,
                        new EntityTransition(
                            Archetype.FEATURE,
                            new EntityTransition.Membership(epic.id, null),
                            "The part",
                            null,
                            "REFINING", // layer one: a FEATURE has no status
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                        epic.id,
                        // layer two: an epic cannot be part of a feature
                        epicEntry("The plan", null, feature.entity().id, EpicStatus.REFINING)),
                    WHO));

    assertTrue(refusal.getMessage().contains("has no status"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("cannot be part of"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("; "), "every complaint in one answer: " + refusal.getMessage());
  }

  /**
   * <b>The slug-scope trap.</b> A slug is immutable and a move changes what it is unique within, so
   * a feature moving to an epic that already holds its slug is a validation refusal naming the slug
   * and the new parent — never a constraint violation arriving as a 500, and never a re-mint.
   */
  @Test
  void aMoveIntoAScopeThatAlreadyHoldsTheSlugIsRefusedByName() {
    WorkEntity here = epicService.create(PROJECT, "Here", null, WHO);
    WorkEntity there = epicService.create(PROJECT, "There", null, WHO);
    Nested moving = featureService.create(here.id, "Shared name", null, null, WHO);
    Nested resident = featureService.create(there.id, "Shared name", null, null, WHO);
    assertEquals(moving.entity().slug, resident.entity().slug, "two epics may each hold this slug — that is the trap");

    BadRequestException refusal =
        assertThrows(
            BadRequestException.class,
            () ->
                transitions.transition(
                    stated(moving.entity().id, featureEntry(there.id, null, "Shared name")), WHO));

    assertTrue(refusal.getMessage().contains(moving.entity().slug), refusal.getMessage());
    assertTrue(refusal.getMessage().contains(there.id), refusal.getMessage());
    inFreshTx(
        () -> {
          assertEquals(here.id, memberships.membershipOf(moving.entity().id).orElseThrow().parentId);
          assertEquals(moving.entity().slug, entities.findById(moving.entity().id).slug, "and never re-minted");
        });
  }

  // --- the impetus settlement ----------------------------------------------

  /**
   * <b>A promotion to TICKET with no impetus is accepted.</b> A transition re-archetypes a row that
   * already exists, which makes it an update and not an intake — so every entry is judged {@link
   * Demand#ON_UPDATE}, and {@code IMPETUS} is declared {@link ArchetypeSpec#requiredAtCreate} rather
   * than {@link ArchetypeSpec#required}. The acceptance is the registry's own answer; it used to be
   * a named predicate discarding a violation the registry had raised.
   */
  @Test
  void aPromotionToTicketWithNoImpetusIsAccepted() {
    WorkEntity epic = epicService.create(PROJECT, "The plan", null, WHO);
    Nested feature = featureService.create(epic.id, "Really a bug", null, null, WHO);

    Map<String, TransitionedEntity> after =
        transitions.transition(
            stated(
                feature.entity().id,
                new EntityTransition(
                    Archetype.TICKET,
                    null,
                    "Really a bug",
                    null,
                    TicketStatus.REPORTED.name(),
                    TicketType.BUG,
                    null, // no impetus: the column allows it and an update may not demand it
                    null,
                    null,
                    null,
                    null,
                    null)),
            WHO);

    assertEquals(Archetype.TICKET, after.get(feature.entity().id).archetype());
    assertNull(after.get(feature.entity().id).impetus());
    inFreshTx(
        () -> {
          WorkEntity row = entities.findById(feature.entity().id);
          assertEquals(Archetype.TICKET, row.archetype);
          assertNull(row.impetus);
          assertEquals(TicketStatus.REPORTED.name(), row.status);
        });
  }

  /** Everything else about a promotion to TICKET is refused as ever — the concession is narrow. */
  @Test
  void aPromotionToTicketStillNeedsItsTypeAndAStatusFromItsOwnLifecycle() {
    WorkEntity epic = epicService.create(PROJECT, "The plan", null, WHO);
    Nested feature = featureService.create(epic.id, "Really a bug", null, null, WHO);

    BadRequestException refusal =
        assertThrows(
            BadRequestException.class,
            () ->
                transitions.transition(
                    stated(
                        feature.entity().id,
                        new EntityTransition(
                            Archetype.TICKET,
                            null,
                            "Really a bug",
                            null,
                            EpicStatus.REFINING.name(), // the other lifecycle's word
                            null, // and no ticket type
                            null,
                            null,
                            null,
                            null,
                            null,
                            null)),
                    WHO));
    assertTrue(refusal.getMessage().contains("ticket type"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("different lifecycle"), refusal.getMessage());
  }

  /**
   * An epic with no status stated is refused. The registry only <em>permits</em> {@code STATUS} on
   * an epic — because a create mints it — and a transition mints nothing, so the rule is this
   * operation's own and is stated here rather than left to clear the column.
   */
  @Test
  void anEntryWhoseTargetHasALifecycleMustStateAStatus() {
    WorkEntity epic = epicService.create(PROJECT, "The plan", null, WHO);

    BadRequestException refusal =
        assertThrows(
            BadRequestException.class,
            () -> transitions.transition(stated(epic.id, epicEntry("The plan", null, null, null)), WHO));
    assertTrue(refusal.getMessage().contains("requires status"), refusal.getMessage());
    inFreshTx(() -> assertEquals(EpicStatus.REFINING.name(), entities.findById(epic.id).status));
  }

  // --- position -------------------------------------------------------------

  /** A stated position lands the entity at that index; a stated position past the end is clamped. */
  @Test
  void aStatedPositionLandsAtThatIndexAndIsClampedRatherThanRefused() {
    WorkEntity epic = epicService.create(PROJECT, "The plan", null, WHO);
    Nested a = featureService.create(epic.id, "A", null, null, WHO);
    Nested b = featureService.create(epic.id, "B", null, null, WHO);
    Nested c = featureService.create(epic.id, "C", null, null, WHO);

    transitions.transition(stated(c.entity().id, featureEntry(epic.id, 0, "C")), WHO);
    inFreshTx(
        () -> {
          assertEquals(List.of(c.entity().id, a.entity().id, b.entity().id), childIdsOf(epic.id));
          assertEquals(List.of(0, 1, 2), positionsUnder(epic.id));
        });

    transitions.transition(stated(c.entity().id, featureEntry(epic.id, 99, "C")), WHO);
    inFreshTx(
        () -> {
          assertEquals(List.of(a.entity().id, b.entity().id, c.entity().id), childIdsOf(epic.id));
          assertEquals(List.of(0, 1, 2), positionsUnder(epic.id));
        });
  }

  // --- the announcement -----------------------------------------------------

  /** <b>One event for the batch, never one per entity.</b> */
  @Test
  void oneEventIsAnnouncedForTheWholeBatch() {
    WorkEntity epic = epicService.create(PROJECT, "The plan", null, WHO);
    Nested one = featureService.create(epic.id, "First", null, null, WHO);
    Nested two = featureService.create(epic.id, "Second", null, null, WHO);

    Instant before = Instant.now();
    transitions.transition(
        stated(
            one.entity().id, ticketEntry("First", "it came up"),
            two.entity().id, ticketEntry("Second", "it came up")),
        WHO);

    assertEquals(1, announcer.batches().size(), "one call for the batch, not one per entity");
    RecordingTransitionAnnouncer.Batch batch = announcer.batches().get(0);
    assertEquals(
        List.of(one.entity().id, two.entity().id),
        batch.entities().stream().map(TransitionedEntity::id).toList(),
        "every entity of the batch, in the order the caller stated them");
    assertFalse(batch.transitionedAt().isBefore(before));
    assertTrue(
        batch.entities().stream().allMatch(entity -> entity.archetype() == Archetype.TICKET),
        "the announcement carries the POST-state, not what the rows used to be");
  }

  // --- the audit log --------------------------------------------------------

  /**
   * One UPDATE row per entity, with the target archetype's audit word and the post-state subtree
   * root as its key — the epic's id for an epic tree, the ticket's own id for a ticket.
   */
  @Test
  void everyTransitionedEntityGetsAnUpdateAuditRowUnderItsNewSubtree() {
    WorkEntity epic = epicService.create(PROJECT, "The plan", null, WHO);
    Nested staying = featureService.create(epic.id, "Stays", null, null, WHO);
    Nested leaving = featureService.create(epic.id, "Leaves", null, null, WHO);

    transitions.transition(
        stated(
            staying.entity().id, featureEntry(epic.id, 0, "Stays"),
            leaving.entity().id, ticketEntry("Leaves", "it came up")),
        WHO);

    List<AuditEntry> featureLog = auditService.listForEntity(AuditEntityType.FEATURE, staying.entity().id);
    assertEquals(AuditOperation.UPDATE, featureLog.get(0).operation);
    assertEquals(epic.id, featureLog.get(0).epicId, "the subtree key is the post-state root");

    List<AuditEntry> ticketLog = auditService.listForEntity(AuditEntityType.TICKET, leaving.entity().id);
    assertEquals(AuditOperation.UPDATE, ticketLog.get(0).operation);
    assertEquals(
        leaving.entity().id, ticketLog.get(0).epicId, "a ticket carries its own id as the subtree key");
    assertNotNull(ticketLog.get(0).snapshot);
  }

  // --- fixtures -------------------------------------------------------------

  private static Map<String, EntityTransition> stated(Object... idsAndEntries) {
    Map<String, EntityTransition> stated = new LinkedHashMap<>();
    for (int i = 0; i < idsAndEntries.length; i += 2) {
      stated.put((String) idsAndEntries[i], (EntityTransition) idsAndEntries[i + 1]);
    }
    return stated;
  }

  private static EntityTransition epicEntry(
      String title, String description, String parent, EpicStatus status) {
    return new EntityTransition(
        Archetype.EPIC,
        new EntityTransition.Membership(parent, null),
        title,
        description,
        status == null ? null : status.name(),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static EntityTransition ticketEntry(String title, String impetus) {
    return new EntityTransition(
        Archetype.TICKET,
        null,
        title,
        null,
        TicketStatus.REPORTED.name(),
        TicketType.BUG,
        impetus,
        null,
        null,
        null,
        null,
        null);
  }

  private static EntityTransition featureEntry(String parent, Integer position, String title) {
    return new EntityTransition(
        Archetype.FEATURE,
        new EntityTransition.Membership(parent, position),
        title,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static EntityTransition taskEntry(
      String parent, Integer position, String title, String repositoryId) {
    return new EntityTransition(
        Archetype.TASK,
        new EntityTransition.Membership(parent, position),
        title,
        null,
        null,
        null,
        null,
        null,
        null,
        repositoryId,
        null,
        null);
  }

  private List<String> childIdsOf(String parentId) {
    return memberships.childrenOf(parentId).stream().map(edge -> edge.childId).toList();
  }

  private List<Integer> positionsUnder(String parentId) {
    return memberships.childrenOf(parentId).stream().map(edge -> edge.position).toList();
  }
}
