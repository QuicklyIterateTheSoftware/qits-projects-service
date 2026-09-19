package eu.wohlben.qits.epics.control;

/**
 * <b>The one archetype violation an UPDATE path tolerates, in one place, with its reason.</b>
 *
 * <p>{@code Archetypes} declares {@link EntityProperty#IMPETUS} <em>required</em> of a {@code
 * TICKET} and it is right about intake: a REPORTED ticket is an impetus and nothing else, and {@code
 * TicketService.create} enforces it before anything is written. It is <em>not</em> right about the
 * column. V7 made {@code entity.impetus} nullable on purpose — rows that predate it have none,
 * triage may write one onto them, and <em>clearing</em> one is asserted behaviour ({@code
 * TicketServiceTest.theClearFlagsAreWhatEmptyTheNullableFields}, {@code
 * TicketApiTest.theClearFlagsAreWhatEmptyTheNullableFields}).
 *
 * <p><b>The concession is a property of every UPDATE path, not of the ticket path.</b> That is the
 * settlement this class exists to record, and it is the reason the predicate moved out of {@code
 * TicketService}. An update mints no row, so it cannot demand of an existing row what intake demands
 * of a row being born; and a multi-entity transition that re-archetypes an existing row <em>into</em>
 * a {@code TICKET} is an update by that test — it moves a row that already exists rather than
 * creating one. So a promotion to {@code TICKET} with no impetus is accepted, on exactly the terms
 * {@code TicketService.update} is accepted on, and for exactly the same reason.
 *
 * <p><b>It is narrow and stays narrow.</b> This exact property, with this exact reason. A foreign
 * property, an illegal status word, a missing title, a missing ticket type and every violation on
 * every create are refused with no exception anywhere.
 *
 * <p><b>What this does NOT settle</b> is the registry against the column. Two answers exist and
 * neither is reachable without moving an existing test's assertions — making the column {@code not
 * null} turns an accepted write into a refusal ({@code TicketServiceTest} and {@code
 * TicketApiTest.theClearFlagsAreWhatEmptyTheNullableFields}), and demoting {@code IMPETUS} to merely
 * <em>permitted</em> changes {@code ArchetypesTest}, which asserts the required set and the
 * missing-required violation outright. That is a contract change and needs a person. See {@code
 * docs/unified-entity-model.md}.
 *
 * <p>A named class rather than a static on {@code Archetypes}, deliberately: {@code Archetypes} is
 * the registry and must go on saying that a ticket requires an impetus. A concession that lived
 * inside it would read as the registry disagreeing with itself, and the next reader would not be
 * able to tell the rule from the exception. Here the two are separate things, and the exception has
 * somewhere to explain itself.
 */
public final class ImpetusConcession {

  private ImpetusConcession() {}

  /**
   * Whether {@code violation} is the one an update path may ignore: {@link EntityProperty#IMPETUS}
   * missing where the registry requires it.
   *
   * @param violation one finding from {@code Archetypes.validate}
   * @return true only for the concession above — never for a foreign property, an illegal status, or
   *     any other missing required property
   */
  public static boolean theImpetusTheColumnStillAllowsToBeAbsent(ArchetypeViolation violation) {
    return violation.property() == EntityProperty.IMPETUS
        && violation.reason() == ArchetypeViolation.Reason.MISSING_REQUIRED;
  }
}
