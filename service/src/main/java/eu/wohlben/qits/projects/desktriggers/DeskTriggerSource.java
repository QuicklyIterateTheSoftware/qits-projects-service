package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.EventFrame;
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
   * This source's section of a closing window's message: a heading line, then one line per event,
   * no trailing newline. Given this source's events only, never empty, in admission order.
   */
  String render(String projectSlug, List<DeskTriggerEventRow> events);
}
