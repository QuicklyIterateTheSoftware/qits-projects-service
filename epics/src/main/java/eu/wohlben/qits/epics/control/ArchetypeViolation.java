package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.Archetype;

/**
 * <b>One thing wrong with a candidate row</b>, structured rather than a sentence.
 *
 * <p>The structure is what the type is for. A caller of the registry is a write path or the
 * multi-entity transition, and both of them have a caller of their own that wants the complaint
 * attached to the field it is about — the impetus box, the repository picker. A string blob makes
 * that impossible and turns every consumer into a parser of English, so {@link #property} is the
 * field's name in the vocabulary and {@link #reason} is the machine-readable kind. {@link
 * #message()} is derived from the two and is there so a log line and a plain API error stay
 * readable without a second rendering.
 *
 * @param archetype the kind the candidate claims to be — part of the finding, because "impetus is
 *     required" is only true of a ticket and a reader seeing it about an epic has found a different
 *     bug
 * @param property the field at fault, always named
 * @param reason what is wrong with it
 * @param detail the offending value where naming it helps (the illegal status word), else null.
 *     <b>Never a value that could be secret</b>: the fields this can quote are enum words
 */
public record ArchetypeViolation(
    Archetype archetype, EntityProperty property, Reason reason, String detail) {

  /** What kind of thing is wrong. Three, and there is deliberately no catch-all fourth. */
  public enum Reason {

    /** The archetype requires this property and the candidate has not got it. */
    MISSING_REQUIRED,

    /**
     * The archetype has no slot for this property and the candidate carries it. <b>A refusal and
     * never a silent drop</b> — that is the whole point of the gate: a value arriving on a kind
     * that cannot hold it means the caller and the model disagree about what is being written, and
     * dropping it would lose both the value and the disagreement.
     */
    NOT_PERMITTED,

    /**
     * The status word is outside the vocabulary this archetype's lifecycle is written in — an
     * {@code EpicStatus} on a ticket, a {@code TicketStatus} on an epic, or a word neither enum
     * spells. The database cannot catch this: {@code ck_entity_status} is the union of both enums,
     * because a check constraint has no way to say "these five when the archetype is EPIC" without
     * becoming a second place the vocabulary is written down.
     */
    ILLEGAL_STATUS
  }

  /** A readable sentence for a log line or a plain error body; the structure above is the contract. */
  public String message() {
    return switch (reason) {
      case MISSING_REQUIRED ->
          "a " + archetype + " requires " + name(property) + ", and none was given";
      case NOT_PERMITTED -> "a " + archetype + " has no " + name(property);
      case ILLEGAL_STATUS ->
          "a " + archetype + " has no status " + detail + " — its statuses are a different lifecycle";
    };
  }

  /** The property as a caller spells it: the vocabulary word, lower-cased and hyphen-free. */
  private static String name(EntityProperty property) {
    return property.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
  }
}
