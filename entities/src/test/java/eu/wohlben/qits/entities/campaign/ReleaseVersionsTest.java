package eu.wohlben.qits.entities.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The one version order: every row is a case plain string order gets wrong, or an edge. Plain JUnit. */
class ReleaseVersionsTest {

  /** {a, b, expected sign}. */
  private static final Object[][] ROWS = {
    {"2026.930.9", "2026.930.10000", -1},
    {"2026.1001.1", "2026.930.235959", 1},
    {"2026.908.55857", "2026.908.55857", 0},
    {"2026.908", "2026.908.1", -1},
    {null, "2026.1.1", -1},
    {"2026.930.x1", "2026.930.x2", -1},
    {"", null, 0},
    {" ", "2026.1.1", -1},
  };

  @Test
  void theOrder() {
    for (Object[] row : ROWS) {
      String a = (String) row[0];
      String b = (String) row[1];
      int expected = (Integer) row[2];
      assertEquals(expected, Integer.signum(ReleaseVersions.compare(a, b)), a + " vs " + b);
      assertEquals(
          -expected, Integer.signum(ReleaseVersions.compare(b, a)), b + " vs " + a + " (antisymmetric)");
    }
  }

  @Test
  void aFloorIsMetAtOrAboveItAndNeverByABlankVersion() {
    assertTrue(ReleaseVersions.atLeast("2026.908.55857", "2026.908.55857"), "equal meets the floor");
    assertTrue(ReleaseVersions.atLeast("2026.930.10000", "2026.930.9"));
    assertFalse(ReleaseVersions.atLeast("2026.930.9", "2026.930.10"), "one below the floor");
    assertTrue(ReleaseVersions.atLeast(null, null), "no floor is always met");
    assertTrue(ReleaseVersions.atLeast("", " "), "a blank floor is no floor");
    assertFalse(ReleaseVersions.atLeast(null, "2026.1.1"));
    assertFalse(ReleaseVersions.atLeast(" ", "0"), "a blank version never meets a floor");
  }
}
