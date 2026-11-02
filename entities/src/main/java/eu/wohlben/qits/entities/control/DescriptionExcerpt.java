package eu.wohlben.qits.entities.control;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A short plain-text lead of a description</b> (qits-1821): what a card on the desk shows under
 * a ticket's or an epic's title, so a listing that never answers the Markdown body ({@link
 * EntitySummary#excerpt}) can still say what the item is about. A pure function of the Markdown — no
 * database, no CDI — so every case is a plain unit test ({@code DescriptionExcerptTest}).
 *
 * <h2>The rule</h2>
 *
 * <p><b>The first paragraph that is prose.</b> Fenced code blocks ({@code ```} and {@code ~~~}),
 * heading lines, table lines, horizontal rules and blank lines are skipped; a blockquote's {@code >}
 * markers are stripped and its text kept. What is left is read as paragraphs — runs of consecutive
 * lines — and the first one is the lead. A list item is a paragraph line with its marker ({@code -},
 * {@code *}, {@code +}, {@code 1.}) stripped, so a body that opens with a list leads with the list.
 *
 * <p><b>The inline Markdown goes, the words stay.</b> Emphasis delimiters are dropped only where
 * they open or close a word, so {@code snake_case} keeps its underscore; a code span keeps its text
 * and loses its backticks, and nothing inside it is read as Markdown; a link answers its text and an
 * image nothing at all — its alt text describes a picture the card does not show.
 *
 * <p><b>At most {@value #LIMIT} characters</b>, whitespace collapsed: a longer lead is cut at the
 * last word boundary and ends in {@code …}, so the answer is never longer than {@value #LIMIT} + 1.
 * A lead that fits is answered as it is, with no ellipsis.
 *
 * <p><b>Null when there is nothing to show</b>: no description, a blank one, and one that is all
 * headings, tables and code.
 */
public final class DescriptionExcerpt {

  /** The longest excerpt before the ellipsis. */
  public static final int LIMIT = 240;

  private static final String ELLIPSIS = "…";

  private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})");
  private static final Pattern HEADING = Pattern.compile("^#{1,6}(\\s|$)");
  private static final Pattern RULE = Pattern.compile("^([-*_])(\\s*\\1){2,}$");
  private static final Pattern LIST_ITEM = Pattern.compile("^(?:[-*+]|\\d+[.)])\\s+(.*)$");
  private static final Pattern CODE_SPAN = Pattern.compile("(`+)(.+?)\\1");
  private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
  private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
  private static final Pattern EMPHASIS_OPEN =
      Pattern.compile("(?U)(?<![\\w*_])(\\*{1,3}|_{1,3})(?=[^\\s*_])");
  private static final Pattern EMPHASIS_CLOSE =
      Pattern.compile("(?U)(?<=[^\\s*_])(\\*{1,3}|_{1,3})(?![\\w*_])");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** A code span parked while the prose rules run: private-use characters no body holds. */
  private static final char PARK_OPEN = '\uE000';

  private static final char PARK_CLOSE = '\uE001';
  private static final Pattern PARKED = Pattern.compile(PARK_OPEN + "(\\d+)" + PARK_CLOSE);

  private DescriptionExcerpt() {}

  /** The lead of {@code markdown} by the class rule, or null when nothing is left to show. */
  public static String of(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return null;
    }
    String text = WHITESPACE.matcher(inline(firstParagraph(markdown))).replaceAll(" ").strip();
    return text.isEmpty() ? null : cut(text);
  }

  /** The first paragraph's lines, joined by a space; empty when the body has none. */
  private static String firstParagraph(String markdown) {
    List<String> paragraph = new ArrayList<>();
    String fence = null;
    for (String raw : markdown.split("\\R")) {
      String line = raw.strip();
      if (fence != null) {
        if (line.startsWith(fence)) {
          fence = null;
        }
        continue;
      }
      Matcher opening = FENCE.matcher(line);
      if (opening.find()) {
        if (!paragraph.isEmpty()) {
          break;
        }
        fence = opening.group(1);
        continue;
      }
      while (line.startsWith(">")) {
        line = line.substring(1).strip();
      }
      if (line.isEmpty()
          || HEADING.matcher(line).find()
          || line.startsWith("|")
          || RULE.matcher(line).matches()) {
        if (!paragraph.isEmpty()) {
          break;
        }
        continue;
      }
      Matcher item = LIST_ITEM.matcher(line);
      paragraph.add(item.matches() ? item.group(1) : line);
    }
    return String.join(" ", paragraph);
  }

  /**
   * The text with its inline Markdown stripped; a code span's content is kept as written.
   *
   * <p>Each code span is parked behind a placeholder while the prose rules run over the whole line,
   * then put back. The emphasis rules have to see the span as a word, or a bold span around code
   * ({@code **`tags.sweep`**}) leaves its delimiters behind (qits-1821, found live).
   */
  private static String inline(String text) {
    List<String> spans = new ArrayList<>();
    StringBuilder parked = new StringBuilder(text.length());
    Matcher code = CODE_SPAN.matcher(text);
    int at = 0;
    while (code.find()) {
      parked.append(text, at, code.start());
      parked.append(PARK_OPEN).append(spans.size()).append(PARK_CLOSE);
      spans.add(code.group(2));
      at = code.end();
    }
    String prose = prose(parked.append(text.substring(at)).toString());
    Matcher back = PARKED.matcher(prose);
    StringBuilder out = new StringBuilder(prose.length());
    while (back.find()) {
      String span = spans.get(Integer.parseInt(back.group(1)));
      back.appendReplacement(out, Matcher.quoteReplacement(span));
    }
    return back.appendTail(out).toString();
  }

  /** Images dropped, links answered by their text, emphasis delimiters dropped. */
  private static String prose(String text) {
    String linked = LINK.matcher(IMAGE.matcher(text).replaceAll("")).replaceAll("$1");
    return EMPHASIS_CLOSE.matcher(EMPHASIS_OPEN.matcher(linked).replaceAll("")).replaceAll("");
  }

  /** {@code text} if it fits; else its longest prefix ending at a word boundary, plus the ellipsis. */
  private static String cut(String text) {
    if (text.length() <= LIMIT) {
      return text;
    }
    int end = text.lastIndexOf(' ', LIMIT);
    if (end <= 0) {
      end = Character.isHighSurrogate(text.charAt(LIMIT - 1)) ? LIMIT - 1 : LIMIT;
    }
    return text.substring(0, end).stripTrailing() + ELLIPSIS;
  }
}
