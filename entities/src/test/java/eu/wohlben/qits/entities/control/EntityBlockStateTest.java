package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The effective block (qits-895): the explicit flag OR an agent wait that has stood for the
 * debounce, and what each combination answers as its source, reason and actor.
 *
 * <p><b>Plain JUnit and no Quarkus application</b> — a pure function of a row and a clock.
 */
class EntityBlockStateTest {

  private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

  private static final Duration DEBOUNCE = Duration.ofSeconds(30);

  private static WorkEntity row(boolean blocked, Instant waitingSince) {
    WorkEntity row = new WorkEntity();
    row.archetype = Archetype.TICKET;
    row.blocked = blocked;
    if (blocked) {
      row.blockedBy = "dana";
      row.blockedReason = "the owner has to choose";
    }
    row.agentWaitingSince = waitingSince;
    return row;
  }

  @Test
  void neitherSourceIsNotBlockedAndSaysNothingElse() {
    EntityBlockState state = EntityBlockState.of(row(false, null), NOW, DEBOUNCE);
    assertFalse(state.blocked());
    assertNull(state.source());
    assertNull(state.reason());
    assertNull(state.blockedBy());
  }

  @Test
  void aWaitInsideTheDebounceIsNotABlock() {
    EntityBlockState state =
        EntityBlockState.of(row(false, NOW.minusSeconds(29)), NOW, DEBOUNCE);
    assertFalse(state.blocked());
    assertNull(state.source());
  }

  @Test
  void aWaitAtTheDebounceIsABlockWithTheFixedSentenceAndNoActor() {
    EntityBlockState state =
        EntityBlockState.of(row(false, NOW.minusSeconds(30)), NOW, DEBOUNCE);
    assertTrue(state.blocked());
    assertEquals(EntityBlockState.AGENT_WAITING, state.source());
    assertEquals(EntityBlockState.AGENT_WAITING_REASON, state.reason());
    assertNull(state.blockedBy());
  }

  @Test
  void anExplicitBlockCarriesItsOwnReasonAndActor() {
    EntityBlockState state = EntityBlockState.of(row(true, null), NOW, DEBOUNCE);
    assertTrue(state.blocked());
    assertEquals(EntityBlockState.EXPLICIT, state.source());
    assertEquals("the owner has to choose", state.reason());
    assertEquals("dana", state.blockedBy());
  }

  @Test
  void bothSourcesAnswerBothWithTheExplicitReason() {
    EntityBlockState state =
        EntityBlockState.of(row(true, NOW.minusSeconds(60)), NOW, DEBOUNCE);
    assertTrue(state.blocked());
    assertEquals(EntityBlockState.BOTH, state.source());
    assertEquals("the owner has to choose", state.reason());
    assertEquals("dana", state.blockedBy());
  }

  @Test
  void anExplicitBlockWithAWaitInsideTheDebounceIsExplicitAlone() {
    EntityBlockState state =
        EntityBlockState.of(row(true, NOW.minusSeconds(5)), NOW, DEBOUNCE);
    assertEquals(EntityBlockState.EXPLICIT, state.source());
  }

  /** A session waiting on a declared wait is not waiting for a person: no block (qits-1153). */
  @Test
  void aWaitOnADeclaredWaitIsNoBlockAndSaysWhatItWaitsOn() {
    WorkEntity row = row(false, NOW.minusSeconds(300));
    row.agentWaitingOn = "release of qits-ci-service";
    EntityBlockState state = EntityBlockState.of(row, NOW, DEBOUNCE);
    assertFalse(state.blocked());
    assertNull(state.source());
    assertEquals("release of qits-ci-service", state.waitingOn());
    assertFalse(EntityBlockState.agentWaitingEffective(row, NOW, DEBOUNCE));
  }

  /** An explicit block stands beside a declared wait; the derived one does not. */
  @Test
  void anExplicitBlockStandsBesideADeclaredWait() {
    WorkEntity row = row(true, NOW.minusSeconds(300));
    row.agentWaitingOn = "ci run 42";
    EntityBlockState state = EntityBlockState.of(row, NOW, DEBOUNCE);
    assertEquals(EntityBlockState.EXPLICIT, state.source());
    assertEquals("ci run 42", state.waitingOn());
  }

  /**
   * The label is read regardless of {@code agentWaitingSince}, which can be null while the agent is
   * still busy with the turn that declared it (qits-1460). The derived block's meaning does not
   * change: it still needs {@code since != null} and stands at zero here either way.
   */
  @Test
  void aLabelWithNoOpenWaitForAPersonIsStillRead() {
    WorkEntity row = row(false, null);
    row.agentWaitingOn = "release of qits-ci-service";
    EntityBlockState state = EntityBlockState.of(row, NOW, DEBOUNCE);
    assertEquals("release of qits-ci-service", state.waitingOn());
    assertFalse(state.blocked());
    assertNull(state.source());
    assertFalse(EntityBlockState.agentWaitingEffective(row, NOW, DEBOUNCE));
  }

  @Test
  void theShippedDebounceIsSixtySeconds() {
    assertEquals(Duration.ofSeconds(60), EntityBlockState.debounce());
  }
}
