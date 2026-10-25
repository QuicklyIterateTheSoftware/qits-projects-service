package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * <b>The prompt a closed window becomes</b> (epic qits-1604) — exactly:
 *
 * <pre>
 * You are the delivery desk of project &lt;slug&gt;. Read /tmp/qits-delivery-brief.md and follow it,
 * then act on what happened below. (desk trigger &lt;window id&gt;, &lt;opened&gt; to &lt;closes&gt;)
 *
 * Moved to VERIFIED:
 * - qits-1580 (Title of 1580)
 *
 * Needs your approval:
 * - qits-qits-rr-201 (Release qits-1597 wrapper; waits on manual-review: …), fold 412911eb
 * </pre>
 *
 * <p>The preamble is one line. Then each source with events in the window renders its own section
 * ({@link DeskTriggerSource#render}), in {@link DeskTriggerSource#order()}, one blank line between
 * sections; a source with nothing in the window has no section, and nothing follows the last. The
 * bounds are ISO-8601 instants to the second.
 *
 * <p>The opening sentence is what the desk's brief keys on: "a message that starts {@code You are
 * the delivery desk}" is a desk trigger. Changing it is a change to the brief too.
 */
public final class DeskTriggerMessage {

  /**
   * Where the desk's daemon writes the delivery brief. A copy of qits-projects-daemon's {@code
   * DeliveryKeeper.BRIEF_PATH} — that repository is not a dependency of this one, and the path is
   * part of the daemon's contract with the desk session, so a move there is a move here.
   */
  public static final String BRIEF_PATH = "/tmp/qits-delivery-brief.md";

  private DeskTriggerMessage() {}

  /**
   * The message of {@code window}, from its events grouped by the source that admitted them. A
   * source with an empty list renders nothing.
   */
  public static String render(
      String projectSlug,
      DeskTriggerWindow window,
      Map<DeskTriggerSource, List<DeskTriggerEventRow>> eventsBySource) {
    StringBuilder message =
        new StringBuilder()
            .append("You are the delivery desk of project ")
            .append(projectSlug)
            .append(". Read ")
            .append(BRIEF_PATH)
            .append(" and follow it, then act on what happened below. (desk trigger ")
            .append(window.id)
            .append(", ")
            .append(iso(window.openedAt))
            .append(" to ")
            .append(iso(window.closesAt))
            .append(")");
    List<DeskTriggerSource> sources = new ArrayList<>(eventsBySource.keySet());
    sources.sort(
        Comparator.comparingInt(DeskTriggerSource::order).thenComparing(DeskTriggerSource::id));
    for (DeskTriggerSource source : sources) {
      List<DeskTriggerEventRow> events = eventsBySource.get(source);
      if (events == null || events.isEmpty()) {
        continue;
      }
      message.append("\n\n").append(source.render(projectSlug, events));
    }
    return message.toString();
  }

  private static String iso(Instant instant) {
    return instant.truncatedTo(ChronoUnit.SECONDS).toString();
  }
}
