package eu.wohlben.qits.epics.migration;

/**
 * One row the comparison has something to say about — a discrepancy, an expected difference or a
 * piece of census evidence, depending on the {@link VerificationCategory} it hangs under.
 *
 * <p><b>It is one shape for all three kinds, deliberately.</b> A record per category would put the
 * reader in front of a union type whose arms differ by one field name, and the consumer of this
 * document is a person reading a JSON body before pressing "drop the tables" — not a client with a
 * generated model to switch on. So the shape is the question every finding answers: which row, what
 * about it, and the two values that disagree.
 *
 * @param id the entity id the finding is about, or the key of whatever else it names (an archetype
 *     for a census row, a {@code dossier_page} id for a dangling reference). Never null.
 * @param archetype the archetype in play, as far as the comparison can tell — the <em>old</em> row's
 *     kind where the two disagree, since the old tables are the yardstick here
 * @param detail what is being compared, in the vocabulary of the thing compared: a property name
 *     ({@code slug}, {@code impetus}), a column reference ({@code dossier_page.epic_id}), a
 *     parent/position sentence, or a plain sentence for a category with nothing finer to say
 * @param oldValue the value read off the old table, rendered as text; null where the old side has
 *     none (an absent column, or a category whose findings are one-sided)
 * @param newValue the value read off {@code entity} / {@code entity_membership}, rendered as text;
 *     null on the same terms. <b>Null and the string {@code "null"} are different answers</b> and
 *     neither is substituted for the other: a column holding SQL NULL renders as null here, and a
 *     column holding the four characters is a value.
 */
public record VerificationFinding(
    String id, String archetype, String detail, String oldValue, String newValue) {}
