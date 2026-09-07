package eu.wohlben.qits.epics.entity;

/**
 * What kind of work a {@link Ticket} is. Stored as the enum name (V4) behind a check constraint,
 * because it is a closed vocabulary the board filters on rather than an open taxonomy.
 *
 * <p>Two words and no third: a ticket is small-scoped by definition, so "something is wrong" and
 * "something could be better" is the whole of the distinction it has to carry. Anything needing a
 * plan is an {@link Epic}, which is why there is no FEATURE here.
 */
public enum TicketType {

  /** Something behaves other than it should. */
  BUG,

  /** Something works and could work better. */
  IMPROVEMENT
}
