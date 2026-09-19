package eu.wohlben.qits.epics.entity;

/**
 * Which of the four planning kinds a {@link WorkEntity} is. Stored as the enum name (V9) behind
 * {@code ck_entity_archetype}, because it is a closed vocabulary every rule in the module is written
 * against rather than an open taxonomy — the archetype decides which properties a row may carry,
 * which statuses are legal on it, and what may contain it.
 *
 * <p><b>The enum is the discriminator and nothing else.</b> There is no subclassing behind these
 * four words and no per-archetype table: what each one means is <em>declared</em> in {@code
 * control/Archetypes}, as data, so adding a fifth is one declaration plus one migration rather than
 * a class, a repository and a service. That is the whole reason the four tables became one.
 *
 * <p><b>Order here means nothing.</b> The nesting rule reads a <em>declared</em> depth from the
 * registry and never {@link #ordinal()}: a kind that sits above {@link #EPIC} — the campaign this
 * model is being merged in order to allow — would have to be inserted at position zero for an
 * ordinal-derived depth to work, and inserting a constant into an enum whose names are in a check
 * constraint and in every row of a table is exactly the change nobody should have to make.
 */
public enum Archetype {

  /**
   * A plan: a scope that is committed to, frozen, superseded and declared shipped, with work
   * beneath it. Carries {@link EpicStatus}, and is a root.
   */
  EPIC,

  /**
   * A small-scoped bug or improvement that a plan would be overhead for. Carries {@link
   * TicketStatus} and the intake properties an epic has no use for — an impetus, a type, a
   * reporter — and is a root, beside {@link #EPIC} rather than under it.
   */
  TICKET,

  /** A coherent piece of an epic's scope. Nests under a root; carries no status of its own. */
  FEATURE,

  /**
   * Work in one concrete repository. The only archetype that names a repository, and the deepest
   * of the four.
   */
  TASK
}
