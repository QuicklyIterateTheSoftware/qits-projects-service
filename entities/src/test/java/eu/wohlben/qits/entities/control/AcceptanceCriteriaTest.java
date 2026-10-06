package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.error.BadRequestException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The item rules of an acceptance criterion (qits-887, qits-934), each at its boundary, and the
 * served pattern agreeing with them on every one of those boundaries.
 */
class AcceptanceCriteriaTest {

  private static final Pattern SERVED = Pattern.compile(AcceptanceCriteria.PATTERN);

  /** {@code words} words joined by single spaces: {@code words - 1} whitespace characters. */
  private static String words(int words) {
    String[] each = new String[words];
    Arrays.fill(each, "w");
    return String.join(" ", each);
  }

  @Test
  void nineteenWhitespaceCharactersPassAndTwentyAreRefused() {
    String nineteen = words(20);
    String twenty = words(21);
    assertEquals(19, nineteen.chars().filter(Character::isWhitespace).count());
    assertTrue(AcceptanceCriteria.refusal(nineteen).isEmpty());
    assertTrue(
        AcceptanceCriteria.refusal(twenty).orElseThrow().contains("20 whitespace characters"));
    // A tab counts as a space does.
    assertFalse(AcceptanceCriteria.refusal(words(20) + "\tw").isEmpty());
  }

  @Test
  void oneDotPassesAndTwoAreRefused() {
    assertTrue(AcceptanceCriteria.refusal("The release names the epic.").isEmpty());
    assertTrue(AcceptanceCriteria.refusal("No dot at all").isEmpty());
    assertTrue(
        AcceptanceCriteria.refusal("The file is a.b.").orElseThrow().contains("2 '.' characters"));
  }

  @Test
  void aLineBreakOfEitherKindIsRefused() {
    assertTrue(AcceptanceCriteria.refusal("first\nsecond").orElseThrow().contains("line break"));
    assertTrue(AcceptanceCriteria.refusal("first\rsecond").orElseThrow().contains("line break"));
  }

  @Test
  void aBlankOrMissingItemIsRefused() {
    assertEquals("is blank", AcceptanceCriteria.refusal("").orElseThrow());
    assertEquals("is blank", AcceptanceCriteria.refusal("   ").orElseThrow());
    assertEquals("is blank", AcceptanceCriteria.refusal(null).orElseThrow());
  }

  @Test
  void refusalsNameEveryBrokenItemByIndexAndRequireThrowsThemAllAtOnce() {
    List<String> items = Arrays.asList("Fine.", "a.b.c", "Fine too", "x\ny");
    assertEquals(
        List.of(
            "acceptanceCriteria[1] has 2 '.' characters; at most one, so one criterion is one"
                + " sentence",
            "acceptanceCriteria[3] contains a line break; one criterion is one line"),
        AcceptanceCriteria.refusals("acceptanceCriteria", items));
    BadRequestException refused =
        assertThrows(BadRequestException.class, () -> AcceptanceCriteria.require(items));
    assertTrue(refused.getMessage().contains("acceptanceCriteria[1]"));
    assertTrue(refused.getMessage().contains("acceptanceCriteria[3]"));
  }

  @Test
  void anEmptyListIsLegalAndNullMeansNothingStated() {
    assertEquals(List.of(), AcceptanceCriteria.require(List.of()));
    assertNull(AcceptanceCriteria.require(null));
    assertEquals(List.of("A.", "B"), AcceptanceCriteria.require(List.of("A.", "B")));
  }

  @Test
  void theServedPatternAgreesWithTheRulesAtEveryBoundary() {
    for (String item :
        List.of(
            words(20),
            words(21),
            "One.",
            "a.b.",
            "first\nsecond",
            "first\rsecond",
            "",
            "   ",
            "No dot",
            "Back-ticked `code` and **bold** stay one line.")) {
      assertEquals(
          AcceptanceCriteria.refusal(item).isEmpty(),
          SERVED.matcher(item).matches(),
          "the pattern and the rules disagree on: " + item.replace("\n", "\\n"));
    }
  }
}
