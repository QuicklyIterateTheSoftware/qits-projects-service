package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The stale-outputs sentence a red run's rejection appends (qits-1920). */
class StaleOutputsTest {

  @Test
  void aHighlightNamesOneKindAndNothingElse() {
    assertEquals("cli-tapes", StaleOutputs.kindOf("stale outputs: cli-tapes"));
    assertNull(StaleOutputs.kindOf("stale outputs: cli-tapes — regenerate with: qits"));
    assertNull(StaleOutputs.kindOf("stale outputs: "));
    assertNull(StaleOutputs.kindOf("128 tests"));
    assertNull(StaleOutputs.kindOf(null));
  }

  @Test
  void eachKindIsNamedWithItsCommandJoinedBySemicolons() {
    assertEquals(
        "stale outputs: cli-tapes — regenerate with: qits release-request regenerate --project p-1"
            + " --repository qits-landing-app --kind cli-tapes; stale outputs: screenshot-baselines"
            + " — regenerate with: qits release-request regenerate --project p-1 --repository"
            + " qits-landing-app --kind screenshot-baselines",
        StaleOutputs.note(
            "p-1", "qits-landing-app", List.of("cli-tapes", "screenshot-baselines", "cli-tapes")));
  }

  @Test
  void aRequestWithNoAddressNamesTheKindAlone() {
    assertEquals(
        "stale outputs: cli-tapes — regenerate with: qits release-request regenerate --kind"
            + " cli-tapes",
        StaleOutputs.note("p-1", null, List.of("cli-tapes")));
  }

  @Test
  void noKindIsNoNote() {
    assertNull(StaleOutputs.note("p-1", "r", List.of()));
    assertNull(StaleOutputs.note("p-1", "r", null));
  }
}
