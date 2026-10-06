package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.error.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <b>The item rules of an acceptance criterion</b> (qits-887, qits-934), the one place they are
 * written. An acceptance criterion is one short statement the work is accepted against, and the
 * rules keep it one: a list of criteria that grows paragraphs stops being something a person can
 * check off.
 *
 * <ul>
 *   <li><b>Not blank.</b>
 *   <li><b>No line break</b> — neither {@code \n} nor {@code \r}: one statement is one line.
 *   <li><b>At most one {@code .}</b> — one sentence, so a second claim is a second item.
 *   <li><b>Fewer than 20 whitespace characters</b> ({@link Character#isWhitespace}) — the hard length
 *       limit, counted in gaps rather than characters so a long identifier is not penalised.
 * </ul>
 *
 * <p>An empty list is legal: it is what an entity without criteria holds, and what the {@code
 * ACCEPTANCE_CRITERIA} gate refuses. The writers ({@code WorkEntityService}, {@code
 * EntityTransitionService}) call {@link #require}; the wire doors call {@link #refusals} so their
 * 400 lists every complaint about a body at once, and the served schema carries {@link #PATTERN}.
 */
public final class AcceptanceCriteria {

  /** Whitespace characters an item must stay <em>below</em>. */
  public static final int WHITESPACE_LIMIT = 20;

  /**
   * The item rules as one ECMA-262 regular expression, for the served JSON Schema: non-blank, no
   * two dots, fewer than 20 whitespace characters, no line break. {@link #refusal} is what the
   * server enforces and names; this is the same rule for a client that validates up front ({@code
   * \s} there is slightly wider than {@link Character#isWhitespace} on exotic spaces).
   */
  public static final String PATTERN =
      "^(?=.*\\S)(?![^.]*\\.[^.]*\\.)(?!(?:\\S*\\s){20})[^\\n\\r]*$";

  /** The rules in one sentence, for a schema description and a tool argument. */
  public static final String RULES =
      "Each item is one line of Markdown: not blank, no line break, at most one '.', and fewer than"
          + " 20 whitespace characters.";

  private AcceptanceCriteria() {}

  /** What is wrong with one item, or empty when it obeys every rule. */
  public static Optional<String> refusal(String item) {
    if (item == null || item.isBlank()) {
      return Optional.of("is blank");
    }
    if (item.indexOf('\n') >= 0 || item.indexOf('\r') >= 0) {
      return Optional.of("contains a line break; one criterion is one line");
    }
    long dots = item.chars().filter(c -> c == '.').count();
    if (dots > 1) {
      return Optional.of("has " + dots + " '.' characters; at most one, so one criterion is one sentence");
    }
    long whitespace = item.chars().filter(Character::isWhitespace).count();
    if (whitespace >= WHITESPACE_LIMIT) {
      return Optional.of(
          "has "
              + whitespace
              + " whitespace characters; fewer than "
              + WHITESPACE_LIMIT
              + " keeps it short");
    }
    return Optional.empty();
  }

  /**
   * Every complaint about {@code items}, each naming the item's index, in order — empty when the
   * list may be written. {@code field} is how the caller spells the property ({@code
   * acceptanceCriteria}).
   */
  public static List<String> refusals(String field, List<String> items) {
    List<String> refused = new ArrayList<>();
    if (items == null) {
      return refused;
    }
    for (int index = 0; index < items.size(); index++) {
      int at = index;
      refusal(items.get(index)).ifPresent(why -> refused.add(field + "[" + at + "] " + why));
    }
    return refused;
  }

  /** {@code items}, copied, or a 400 naming every item that breaks a rule. Null stays null. */
  public static List<String> require(List<String> items) {
    if (items == null) {
      return null;
    }
    List<String> refused = refusals("acceptanceCriteria", items);
    if (!refused.isEmpty()) {
      throw new BadRequestException(String.join("; ", refused));
    }
    return List.copyOf(items);
  }
}
