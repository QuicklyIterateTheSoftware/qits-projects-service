package eu.wohlben.qits.entities.entity;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * <b>A tag a work entity may carry</b> (qits-634): a closed vocabulary of facts about the work that
 * are neither its kind ({@link Archetype}, {@link TicketType}) nor where it stands ({@link
 * EntityStatus}, {@link WorkEntity#blocked}). Stored as the enum name in {@code entity_tag} (epics
 * V36), held as {@link WorkEntity#tags}.
 *
 * <p><b>Closed on purpose.</b> A tag is something the platform acts on — the dispatch refuses one,
 * the phase advance skips one — so a word nobody wrote code for would be a label that silently does
 * nothing. Adding a tag is therefore a code change: a constant here, the meaning in its javadoc, the
 * {@code ck_entity_tag_tag} check widened by a migration, and whatever reads it.
 *
 * <p><b>Not a status and not a block.</b> A transition never clears a tag (it clears the block), so
 * a tag outlives every move; and a tag is editable at every status, outside every scope freeze,
 * because it says who may do the work, never what the work is.
 */
public enum WorkTag {

  /**
   * <b>The work cannot be completed by an agent: a person has to do it.</b> Why — a credential only
   * a person holds, a decision only the owner can make, hardware nobody wired up — goes in the
   * item's description, not in the tag. No agent is dispatched onto it (the press and the delivery desk
   * are both refused with a 409 naming the tag), and a status move
   * delivers it no phase turn; the person doing the work moves its status by hand. Clearing the tag
   * lets it be dispatched again.
   */
  RESERVED_FOR_HUMAN,

  /**
   * <b>A condition other work waits for, not work of its own</b> (qits-1903) — "bootstrap test
   * node", say. Other items name it in their {@code needs} like any other item; what closes it goes
   * in its description. No agent is dispatched onto it (every press is a 409 naming the tag) and a
   * status move delivers it no phase turn: a person or an agent moves its status, and the items
   * that need it see the move. Widened into {@code ck_entity_tag_tag} by epics V40.
   */
  CONDITION;

  /** How a person reads the tag: {@code reserved for human}. */
  public String display() {
    return name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  /** The words, in declaration order — for a refusal that lists what would have been legal. */
  public static List<String> words() {
    return Arrays.stream(values()).map(Enum::name).toList();
  }

  /**
   * The tag a word names, case-insensitively and in its kebab form too ({@code reserved-for-human},
   * the CLI's spelling); empty for a blank or unknown word.
   */
  public static Optional<WorkTag> parse(String word) {
    if (word == null || word.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(valueOf(word.trim().replace('-', '_').toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException unknown) {
      return Optional.empty();
    }
  }

  /** {@code tags} in declaration order — the order every answer lists them in. */
  public static List<WorkTag> ordered(Collection<WorkTag> tags) {
    return tags == null
        ? List.of()
        : tags.stream().distinct().sorted(Comparator.naturalOrder()).toList();
  }
}
