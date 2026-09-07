package eu.wohlben.qits.epics.entity;

/**
 * The state a {@link Ticket} is in. Stored as the enum name (V4), and moved only through {@code
 * TicketService.transition} — see {@code TicketLifecycle} for the two legal moves.
 *
 * <p>Deliberately two values where {@link EpicStatus} has five, and the asymmetry is the point: an
 * epic carries a scope that has to be frozen, superseded and declared shipped, while a ticket is
 * one small thing that is either still outstanding or not. There is no terminal status — a ticket
 * closed by mistake reopens, because the alternative is a second row saying the same thing.
 */
public enum TicketStatus {

  /** Outstanding. New tickets start here. */
  OPEN,

  /** Dealt with. Reversible: {@link #OPEN} is reachable again, and nothing here freezes. */
  RESOLVED
}
