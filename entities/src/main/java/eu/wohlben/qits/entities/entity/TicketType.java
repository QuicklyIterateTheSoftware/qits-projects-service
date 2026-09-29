package eu.wohlben.qits.entities.entity;

/**
 * What kind of work a {@link Archetype#TICKET} entity is. Stored as the enum name in {@link
 * WorkEntity#ticketType}, because it is a closed vocabulary the board filters on rather than an
 * open taxonomy.
 *
 * <p><b>This enum is the whole of the vocabulary's enforcement.</b> The old ticket table closed the
 * column with a check constraint; the unified {@code entity} table (epics V9) declares {@code
 * ticket_type} as a plain {@code varchar(32)} with none, so what refuses a word nothing has
 * declared is {@code TicketType.valueOf} behind {@code WorkEntityService.parseType} — and the served
 * schema's enum is read off this class too. Adding a word here is therefore the entire change; no
 * migration has to widen anything.
 *
 * <p>Two words a person files and one only the platform does. A ticket is small-scoped by
 * definition, so "something is wrong" and "something could be better" is the whole of the
 * distinction a reporter has to make; anything needing a plan is an {@link Archetype#EPIC}, which is
 * why there is no FEATURE here. {@link #MAINTENANCE} is not a third opinion about the work — it is a
 * BUG the platform filed about itself, marked so that the platform may also be the one to close it.
 */
public enum TicketType {

  /** Something behaves other than it should. */
  BUG,

  /** Something works and could work better. */
  IMPROVEMENT,

  /**
   * <b>A machine-filed maintenance failure</b>: the gate of a release request nobody is watching —
   * one the platform's maintenance robot asked for — came back red, and qits-projects filed this
   * about it. It behaves like a {@link #BUG} in every respect a person sees (the lifecycle, the
   * board, the phases), and differs in exactly one: <b>the platform closes it</b>, moving it to
   * DROPPED when the release request it was filed for ends (FINALIZED, WITHDRAWN or OBSOLETE), since
   * at that point there is no longer a stuck request for it to be about.
   *
   * <p>The word is the consent. A person who retypes the ticket to BUG or IMPROVEMENT has taken it
   * over, and the platform then only says on the thread that the request ended and leaves the ticket
   * where it is.
   */
  MAINTENANCE
}
