package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The golden-master coverage report read back, and the sentence a rejection appends (qits-1940). */
class UncoveredOperationsTest {

  @Test
  void theCountLineAndTheNamesAreReadInOrder() {
    assertEquals(
        new UncoveredOperations(2, List.of("getRun", "POST /ci/api/runs/rerun")),
        UncoveredOperations.of(
            List.of(
                "uncovered operations: 2",
                "uncovered: getRun",
                "uncovered: POST /ci/api/runs/rerun")));
  }

  @Test
  void aTruncatedNameIsKeptAsWritten() {
    String cut = "GET /projects/api/" + "x".repeat(61) + "…";
    assertEquals(
        new UncoveredOperations(1, List.of(cut)),
        UncoveredOperations.of(List.of("uncovered operations: 1", "uncovered: " + cut)));
  }

  @Test
  void zeroIsAReportWithNothingUncovered() {
    assertEquals(
        new UncoveredOperations(0, List.of()),
        UncoveredOperations.of(List.of("uncovered operations: 0")));
  }

  @Test
  void linesMatchingNeitherPatternAreSkipped() {
    assertEquals(
        new UncoveredOperations(1, List.of("createWork")),
        UncoveredOperations.of(
            Arrays.asList(
                "312 operations",
                null,
                "uncovered operations: 1",
                "uncovered: ",
                "uncovered: createWork")));
  }

  @Test
  void noReadableCountIsNoReport() {
    assertNull(UncoveredOperations.of(null));
    assertNull(UncoveredOperations.of(List.of()));
    assertNull(UncoveredOperations.of(List.of("uncovered: getRun")));
    assertNull(UncoveredOperations.of(List.of("uncovered operations: many", "uncovered: getRun")));
    assertNull(UncoveredOperations.of(List.of("uncovered operations: -1")));
    assertNull(UncoveredOperations.of(List.of("uncovered operations: 99999999999")));
  }

  @Test
  void theNoteNamesTheListedOperationsAndCountsTheRest() {
    assertEquals(
        "uncovered operations: 12 — getRun, POST /ci/api/runs/rerun and 10 more — add a"
            + " golden-master state for each",
        UncoveredOperations.note(
            new UncoveredOperations(12, List.of("getRun", "POST /ci/api/runs/rerun"))));
  }

  @Test
  void everyOperationListedNeedsNoRemainder() {
    assertEquals(
        "uncovered operations: 2 — getRun, createWork — add a golden-master state for each",
        UncoveredOperations.note(new UncoveredOperations(2, List.of("getRun", "createWork"))));
  }

  @Test
  void aCountWithoutNamesIsTheCountAlone() {
    assertEquals(
        "uncovered operations: 3 — add a golden-master state for each",
        UncoveredOperations.note(new UncoveredOperations(3, List.of())));
  }

  @Test
  void nothingUncoveredOrNoReportIsNoNote() {
    assertNull(UncoveredOperations.note(new UncoveredOperations(0, List.of())));
    assertNull(UncoveredOperations.note(null));
  }
}
