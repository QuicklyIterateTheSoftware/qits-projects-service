package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.EventFrame;
import java.time.Instant;
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
   * This source's section of a closing window's message: a heading line, then one line per event,
   * no trailing newline. Given this source's events only, never empty, in admission order.
   */
  String render(String projectSlug, List<DeskTriggerEventRow> events);
}
