package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.SiblingPriorities.Assigned;
import eu.wohlben.qits.entities.control.SiblingPriorities.Placement;
import eu.wohlben.qits.entities.control.SiblingPriorities.Row;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The order rule and the drop (qits-753): priority descending with nulls last, then number, then
 * id; a drop renumbers the moved item and everything above it, strictly descending over the
 * highest priority below, and touches nothing below.
 *
 * <p><b>Plain JUnit and no Quarkus application</b> — {@link SiblingPriorities} is a pure function
 * of rows. Each case also applies the answer and reads the order back, because the claim worth
 * making is that the order afterwards is the order the drop drew.
 */
class SiblingPrioritiesTest {

  private static Row row(String id, long number, Integer priority) {
    return new Row(id, number, priority);
  }

  /** The ids of {@code rows} after {@code assigned} is written onto them, in the order rule. */
  private static List<String> orderAfter(List<Row> rows, List<Assigned> assigned) {
    Map<String, Integer> written = new HashMap<>();
    for (Assigned a : assigned) {
      written.put(a.id(), a.priority());
    }
    List<Row> after = new ArrayList<>();
    for (Row r : rows) {
      after.add(
          written.containsKey(r.id()) ? row(r.id(), r.number(), written.get(r.id())) : r);
    }
    return SiblingPriorities.sorted(after).stream().map(Row::id).toList();
  }

  private static Row find(List<Row> rows, String id) {
    return rows.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
  }

  @Test
  void theOrderIsPriorityDescendingNullsLastThenNumberThenId() {
    List<Row> rows =
        List.of(
            row("n4", 4, null),
            row("p1", 9, 1),
            row("n2", 2, null),
            row("p5", 7, 5),
            row("p1b", 3, 1),
            row("tie-b", 6, null),
            row("tie-a", 6, null));

    assertEquals(
        List.of("p5", "p1b", "p1", "n2", "n4", "tie-a", "tie-b"),
        SiblingPriorities.sorted(rows).stream().map(Row::id).toList());
  }

  @Test
  void aDropToTheTopTakesOneAboveTheHighestPriority() {
    List<Row> rows = List.of(row("a", 1, 5), row("b", 2, 3), row("c", 3, null));

    List<Assigned> assigned =
        SiblingPriorities.drop(rows, find(rows, "c"), "a", Placement.ABOVE);

    assertEquals(List.of(new Assigned("c", 6)), assigned);
    assertEquals(List.of("c", "a", "b"), orderAfter(rows, assigned));
  }

  @Test
  void aDropToTheBottomAmongNullsRanksEverythingAboveItFromOne() {
    List<Row> rows =
        List.of(row("a", 1, 5), row("b", 2, 3), row("c", 3, null), row("d", 4, null));

    List<Assigned> assigned =
        SiblingPriorities.drop(rows, find(rows, "a"), "d", Placement.BELOW);

    assertEquals(
        List.of(
            new Assigned("b", 4), new Assigned("c", 3), new Assigned("d", 2), new Assigned("a", 1)),
        assigned);
    assertEquals(List.of("b", "c", "d", "a"), orderAfter(rows, assigned));
  }

  @Test
  void aDropBetweenTwoNullsRanksItAndWhatIsAboveAndLeavesTheTailUnranked() {
    List<Row> rows =
        List.of(row("a", 1, 2), row("b", 2, null), row("c", 3, null), row("d", 4, null));

    List<Assigned> assigned =
        SiblingPriorities.drop(rows, find(rows, "d"), "b", Placement.BELOW);

    assertEquals(
        List.of(new Assigned("a", 3), new Assigned("b", 2), new Assigned("d", 1)), assigned);
    assertEquals(List.of("a", "b", "d", "c"), orderAfter(rows, assigned));
    assertFalse(assigned.stream().anyMatch(x -> x.id().equals("c")), "nothing below is written");
  }

  @Test
  void aDropBetweenEqualPrioritiesSitsAboveTheTiesBelowIt() {
    List<Row> rows =
        List.of(row("a", 1, 2), row("b", 2, 2), row("c", 3, 2), row("x", 4, null));

    List<Assigned> assigned =
        SiblingPriorities.drop(rows, find(rows, "x"), "b", Placement.ABOVE);

    assertEquals(List.of(new Assigned("a", 4), new Assigned("x", 3)), assigned);
    assertEquals(List.of("a", "x", "b", "c"), orderAfter(rows, assigned));
  }

  @Test
  void aDropWhereTheItemAlreadyStoodChangesNoOrderAndStillAnswersEveryRowAboveIt() {
    List<Row> rows = List.of(row("a", 1, 3), row("b", 2, 2), row("c", 3, 1));

    List<Assigned> assigned =
        SiblingPriorities.drop(rows, find(rows, "b"), "a", Placement.BELOW);

    assertEquals(List.of(new Assigned("a", 3), new Assigned("b", 2)), assigned);
    assertEquals(List.of("a", "b", "c"), orderAfter(rows, assigned));
  }

  @Test
  void aboveAndBelowTheSameAnchorLandOnEitherSideOfIt() {
    List<Row> rows = List.of(row("a", 1, null), row("b", 2, null), row("c", 3, null));

    List<Assigned> above = SiblingPriorities.drop(rows, find(rows, "c"), "b", Placement.ABOVE);
    assertEquals(List.of(new Assigned("a", 2), new Assigned("c", 1)), above);
    assertEquals(List.of("a", "c", "b"), orderAfter(rows, above));

    List<Assigned> below = SiblingPriorities.drop(rows, find(rows, "a"), "b", Placement.BELOW);
    assertEquals(List.of(new Assigned("b", 2), new Assigned("a", 1)), below);
    assertEquals(List.of("b", "a", "c"), orderAfter(rows, below));
  }

  @Test
  void theFloorIsTheHighestPriorityBelowEvenWhenItIsNegative() {
    List<Row> rows = List.of(row("a", 1, null), row("b", 2, -3), row("c", 3, -5));

    List<Assigned> assigned =
        SiblingPriorities.drop(rows, find(rows, "a"), "b", Placement.ABOVE);

    assertEquals(List.of(new Assigned("a", -2)), assigned);
    assertEquals(List.of("a", "b", "c"), orderAfter(rows, assigned));
  }

  @Test
  void theItemNeedNotBeAmongTheSiblingsItIsDroppedInto() {
    List<Row> rows = List.of(row("a", 1, 2), row("b", 2, 1));

    List<Assigned> assigned =
        SiblingPriorities.drop(rows, row("n", 9, null), "b", Placement.ABOVE);

    assertEquals(List.of(new Assigned("a", 3), new Assigned("n", 2)), assigned);
  }

  @Test
  void anAnchorThatIsTheItemOrNoSiblingIsRefused() {
    List<Row> rows = List.of(row("a", 1, null), row("b", 2, null));

    assertThrows(
        IllegalArgumentException.class,
        () -> SiblingPriorities.drop(rows, find(rows, "a"), "a", Placement.ABOVE));
    assertThrows(
        IllegalArgumentException.class,
        () -> SiblingPriorities.drop(rows, find(rows, "a"), "z", Placement.BELOW));
  }

  @Test
  void onlyDoneAndDroppedAreOutOfTheOrder() {
    assertFalse(SiblingPriorities.isRankable("DONE"));
    assertFalse(SiblingPriorities.isRankable("DROPPED"));
    for (String status :
        List.of("REPORTED", "REFINED", "READY_FOR_DEV", "IMPLEMENTING", "VERIFIED")) {
      assertTrue(SiblingPriorities.isRankable(status), status);
    }
  }
}
