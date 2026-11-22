package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.EventFrame;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>One kind of thing the delivery desk is told about</b> (epic qits-1604) — the extension point.
 * A source names the bus events it reads, turns one into zero or more {@link DeskTrigger}s, and
 * renders its own section of the message a window closes into. Everything else — subscribing,
 * dropping what the desk caused itself or what a project with delivery off would be told, the
 * windows, the sweep, the retries — is shared and knows no source by name.
 *
 * <p><b>Adding one is one {@code @ApplicationScoped} bean implementing this, plus its tests.</b>
 * {@link DeskTriggerListener} and {@link DeskTriggerSweep} find every bean through {@code @Any
 * Instance<DeskTriggerSource>}: the listener subscribes to the union of their {@link #signatures()},
 * the sweep renders each window's lines through the source that admitted them. A signature no other
 * source reads also moves the consumer pact ({@code EventstreamPactTest}), because the catch-up
 * reads name the signatures — that file is the one place outside the bean that changes.
 *
 * <p><b>Most sources report; one kind answers.</b> A source's triggers are by default news the desk
 * did not ask for, and the shared drops and the collection window apply to them. A source whose
 * triggers answer something the desk itself asked for — a {@code qits wait} it declared ({@link
 * WaitClosedSource}) — says so through {@link #requestedByDesk()}, and that one answer is the whole
 * admission policy: no source is named anywhere else.
 */
public interface DeskTriggerSource {

  /**
   * Stored in {@code desk_trigger_event.source}, and how the sweep finds the source again to render
   * the lines it admitted. Storage, not a label: never change it.
   */
  String id();

  /** Where this source's section sits in the message, ascending. */
  int order();

  /** The simple names of the bus events this source reads. */
  Set<String> signatures();

  /**
   * The triggers {@code frame} carries, as this source reads it; empty when it carries none. Runs in
   * the listener's {@code projects} transaction, so it may read this service's rows (a project's
   * slug, a release request's summary). A payload it cannot read is thrown as a {@link
   * RuntimeException}: the listener warns and the frame contributes nothing from this source.
   */
  List<DeskTrigger> qualify(EventFrame frame);

  /**
   * Whether this source's triggers answer something the desk asked for itself (qits-1618), rather
   * than report something it did not. False by default; true changes three things, and only these:
   *
   * <ul>
   *   <li><b>Admitted with delivery off.</b> {@code project.delivery_enabled} gates news for the
   *       delivery loop; a desk that asked to be woken is owed its answer whether or not the loop is
   *       on. The prompt goes to the same kept front desk session either way — there is one desk
   *       per project, and delivery only decides which rights it holds.
   *   <li><b>Admitted whoever caused it.</b> The desk-caused drop exists so the desk is not told
   *       what it did; the answer to its own request is by definition about something it did.
   *   <li><b>The window closes at once.</b> The admit brings the open window's {@code closes_at}
   *       forward to the admission (never back), so the next sweep — {@code
   *       qits.projects.desk-triggers.sweep-interval} — delivers it with whatever else the window
   *       held, rather than up to {@code qits.projects.desk-triggers.window} later. A desk that
   *       ended its turn to wait is idle until then.
   * </ul>
   *
   * <p>A window whose every line is from such a source also opens without the delivery desk's
   * preamble ({@link DeskTriggerMessage}): the desk asked, so it need not be told to read the
   * delivery brief.
   */
  default boolean requestedByDesk() {
    return false;
  }

  /**
   * The triggers that time passing has made due, found by reading rows rather than an event
   * (qits-1689: a release request that stalls emits nothing). {@link DeskTriggerSweep} asks every
   * source at the start of each pass, in a {@code projects} transaction of the source's own, and
   * admits what it answers under the listener's drops. A source must make its own answer
   * idempotent across passes and windows: the window store only folds a repeat inside one window.
   * Empty by default.
   */
  default List<DeskTrigger> scan(Instant now) {
    return List.of();
  }

  /**
   * Whether a stored {@code line} of this source still holds at {@code now} (qits-2501): a window
   * collects for 15 minutes and is retried for hours, so what a line reported may be over before
   * the desk reads it. {@link DeskTriggerSweep} asks on each pass over an OPEN window, at the close
   * and before every delivery attempt, and a line that no longer holds is dropped from the message
   * ({@code dropped_reason = resolved}), never deleted.
   *
   * <p>Called <b>outside any transaction</b>: a source that reads this service's rows opens one of
   * its own, and a hop to another service holds none. A lookup that fails or cannot be made must
   * answer {@code true} (or throw, which the sweep reads as {@code true}): a line is dropped only
   * on evidence that it is over, never for want of an answer. True by default — news that stays
   * news (an item moved to VERIFIED, a comment, an OOM kill, a wait's answer) always holds.
   */
  default boolean holds(DeskTriggerEventRow line, Instant now) {
    return true;
  }

  /**
   * The lines among {@code lines} — this source's live lines of one window of {@code projectId} —
   * that no longer hold: {@link #holds} per line by default, a line whose check throws holding. A
   * source whose answer comes from one lookup for the whole window (the active agents, the slots)
   * overrides this to make it once. Same rules as {@link #holds}: outside any transaction, and a
   * failed lookup resolves nothing.
   */
  default Set<DeskTriggerEventRow> resolved(
      String projectId, List<DeskTriggerEventRow> lines, Instant now) {
    Set<DeskTriggerEventRow> resolved = new HashSet<>();
    for (DeskTriggerEventRow line : lines) {
      try {
        if (!holds(line, now)) {
          resolved.add(line);
        }
      } catch (RuntimeException e) {
        // Fails open: a line is dropped only on evidence that it is over.
      }
    }
    return resolved;
  }

  /**
   * Whether, inside one window, a newer live line of this source about the same item ({@code
   * item_id}, the release request) supersedes the older ones (qits-2501): the request is named
   * once per source, at what it says last. The older lines are dropped with {@code dropped_reason
   * = superseded}. False by default.
   */
  default boolean supersedesOlderLinesOfItsItem() {
    return false;
  }

  /**
   * The ids of the sources whose live line about the same item ({@code item_id}) in a window
   * supersedes every line of this source about it (qits-2501): a request waiting on the desk's
   * approval is not also reported failed. Empty by default.
   */
  default Set<String> supersededBy() {
    return Set.of();
  }

  /**
   * This source's section of a closing window's message: a heading line, then one line per event,
   * no trailing newline. Given this source's events only, never empty, in admission order.
   */
  String render(String projectSlug, List<DeskTriggerEventRow> events);
}
