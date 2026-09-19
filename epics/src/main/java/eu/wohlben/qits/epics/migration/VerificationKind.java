package eu.wohlben.qits.epics.migration;

/**
 * What a finding in a {@link VerificationCategory} means — and the reason the three are not one list
 * with a severity field: they are answers to three different questions, and only the first one
 * blocks V13.
 *
 * <p>It is a type of its own rather than an enum nested in {@code VerificationCategory} for one
 * concrete reason: a nested enum lands in {@code docs/openapi.yml} under its own simple name, and
 * a schema called {@code Kind} in the largest published surface of the six services is a name that
 * says nothing and that the next nested enum collides with.
 */
public enum VerificationKind {
  /**
   * The migration is wrong about this row. Any finding here fails the run, and the run is what V13
   * waits for.
   */
  DISCREPANCY,

  /**
   * A difference the migration <em>meant</em> to produce, or one the model has produced legitimately
   * since. It is reported rather than suppressed, with both values, because "we looked and decided
   * it was fine" and "we never looked" have to be distinguishable — and because a de-collided slug
   * means a branch name somebody has work on no longer derives.
   */
  EXPECTED,

  /**
   * Neither a defect nor a difference: the census a reader needs in order to believe the rest.
   * Counts per archetype, and the rows created since the cutover that have no old row to compare
   * against at all.
   */
  INFORMATIONAL
}
