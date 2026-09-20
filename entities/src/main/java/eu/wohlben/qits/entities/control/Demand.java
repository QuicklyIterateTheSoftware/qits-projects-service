package eu.wohlben.qits.entities.control;

/**
 * <b>Which of the registry's two required-property sets a candidate is judged against.</b>
 *
 * <p>A property can be demanded at two different moments and the two are genuinely different
 * questions. {@link ArchetypeSpec#required} is what a row of a kind must carry <em>at every
 * moment</em> — it is the invariant, and an update is judged against it. {@link
 * ArchetypeSpec#requiredAtCreate} is what intake demands of a row <em>being born</em>, which may be
 * more: a property a writer must be given once and that the row is not obliged to carry for ever.
 *
 * <p><b>The one property that makes the axis necessary is {@code IMPETUS} on a {@code TICKET}.</b>
 * Intake demands it — a REPORTED ticket is an impetus and nothing else — while {@code
 * entity.impetus} is nullable and clearing one is asserted product behaviour. A registry that could
 * only say "required" or "permitted" had to be wrong about one of those two, and was: it said
 * required, and every update path carried a named concession that tolerated the violation. The
 * concession is gone and this enum is what replaced it.
 *
 * <p><b>It is a parameter and not a default, deliberately.</b> {@code Archetypes.validate} takes one
 * of these at every call site, so a path has to say which moment it is — a create that forgot would
 * otherwise silently stop enforcing intake, which is exactly the failure this settlement exists to
 * make impossible rather than merely unlikely.
 *
 * @see ArchetypeSpec#requiredFor(Demand)
 */
public enum Demand {

  /** A row being born: {@link ArchetypeSpec#requiredAtCreate}, the intake set. */
  AT_CREATE,

  /**
   * A row that already exists: {@link ArchetypeSpec#required}, the invariant. Every update path is
   * this — including the multi-entity transition, which re-archetypes rows rather than creating
   * them.
   */
  ON_UPDATE
}
