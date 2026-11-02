package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The listing's description lead (qits-1821): the first prose paragraph, its inline Markdown
 * stripped, cut at a word past {@value DescriptionExcerpt#LIMIT} characters — or null when the body
 * has no prose at all.
 *
 * <p><b>Plain JUnit and no Quarkus application</b> — {@link DescriptionExcerpt} is a pure function
 * of the Markdown.
 */
class DescriptionExcerptTest {

  @Test
  void noBodyAndABlankOneAnswerNull() {
    assertNull(DescriptionExcerpt.of(null));
    assertNull(DescriptionExcerpt.of(""));
    assertNull(DescriptionExcerpt.of("  \n\t\n"));
  }

  @Test
  void aHeadingIsSkippedAndTheFirstParagraphIsTheLead() {
    assertEquals(
        "The listing answers no description, so a card shows only the title.",
        DescriptionExcerpt.of(
            """
            ## Root cause

            The listing answers no description,
            so a card shows only the title.

            ## Fix
            Answer an excerpt.
            """));
  }

  @Test
  void aBodyOfOnlyATableAnswersNull() {
    assertNull(
        DescriptionExcerpt.of(
            """
            | column | meaning |
            |--------|---------|
            | id     | the row |
            """));
  }

  @Test
  void aFencedCodeBlockIsSkippedWholeBacktickOrTilde() {
    assertEquals(
        "The export is slow.",
        DescriptionExcerpt.of(
            """
            ```
            GET /invoices/export

            looks like prose, is not
            ```
            The export is slow.
            """));
    assertEquals(
        "After the tildes.",
        DescriptionExcerpt.of("~~~java\nint x = 1;\n~~~\n\nAfter the tildes.\n"));
  }

  @Test
  void horizontalRulesAreSkippedAndABlockquoteKeepsItsText() {
    assertEquals(
        "The accountants asked for it twice.",
        DescriptionExcerpt.of("---\n***\n___\n\n> The accountants\n> asked for it twice.\n"));
  }

  @Test
  void aListItemLosesItsMarker() {
    assertEquals(
        "a CSV export of a date range a PDF export of one invoice",
        DescriptionExcerpt.of(
            "## What\n- a **CSV export** of a date range\n1. a *PDF export* of one invoice\n"));
  }

  @Test
  void linksBackticksAndEmphasisAreStrippedButSnakeCaseKeepsItsUnderscores() {
    assertEquals(
        "See the guide: run qits work list and keep snake_case_name as it is, in bold and italic.",
        DescriptionExcerpt.of(
            "See ![diagram](flow.png)[the guide](https://example.org/guide): run `qits work list`"
                + " and keep snake_case_name as it is, in **bold** and _italic_."));
    assertEquals(
        "a *star* stays in code and 2 * 3 stays in prose",
        DescriptionExcerpt.of("a `*star*` stays in code and 2 * 3 stays in prose"));
  }

  @Test
  void aLongParagraphIsCutAtAWordAndEndsInAnEllipsis() {
    String sentence = "Accounting copies every invoice into their tool by hand. ";
    String excerpt = DescriptionExcerpt.of(sentence.repeat(10));

    assertTrue(excerpt.length() <= DescriptionExcerpt.LIMIT + 1, excerpt);
    assertTrue(excerpt.endsWith("…"), excerpt);
    String kept = excerpt.substring(0, excerpt.length() - 1);
    assertTrue(sentence.repeat(10).startsWith(kept), "a prefix of the text: " + kept);
    assertTrue(
        sentence.repeat(10).charAt(kept.length()) == ' ', "cut at a word boundary: " + kept);
  }

  @Test
  void aLeadThatFitsGetsNoEllipsis() {
    String fits = "x".repeat(DescriptionExcerpt.LIMIT);
    assertEquals(fits, DescriptionExcerpt.of(fits));
  }

  @Test
  void aBodyOfOnlyHeadingsAndCodeAnswersNull() {
    assertNull(DescriptionExcerpt.of("# Title\n\n```\ncode\n```\n## Another\n"));
  }

  /** Found live (qits-369, qits-591): bold around a code span left its delimiters behind. */
  @Test
  void emphasisAroundACodeSpanIsStrippedToo() {
    assertEquals(
        "a DROPPED status (the impetus) and one more step, tags.sweep. It is ng test --watch=false,"
            + " truncated.",
        DescriptionExcerpt.of(
            "a **`DROPPED` status** (the impetus) and one more step, **`tags.sweep`**. It is"
                + " **`ng test --watch=false`, truncated**."));
    assertEquals(
        "keep **bold** and __init__ inside code",
        DescriptionExcerpt.of("keep `**bold**` and `__init__` inside code"));
  }
}
