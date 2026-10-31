package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import eu.wohlben.qits.projectsdaemon.protocol.DeskBrief;
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
 * You are the delivery desk of project &lt;slug&gt;. &lt;DeskBrief.pointer(hash)&gt; Then act on what
 * happened below. (desk trigger &lt;window id&gt;, &lt;opened&gt; to &lt;closes&gt;)
 *
 * Moved to VERIFIED:
 * - qits-1580 (Title of 1580)
 *
 * Needs your approval:
 * - qits-qits-rr-201 (Release qits-1597 wrapper; waits on manual-review: …), fold 412911eb
 *
 * Slots: running 5 of 5, 2 VERIFIED waiting to ship
 * - qits-1774 (VERIFIED 1h 5m; qits-qits-rr-264 PENDING)
 * - qits-1769 (VERIFIED 43m; no open release request)
 * </pre>
 *
 * <p>The preamble is one line. Then each source with events in the window renders its own section
 * ({@link DeskTriggerSource#render}), in {@link DeskTriggerSource#order()}, one blank line between
 * sections; a source with nothing in the window has no section. The bounds are ISO-8601 instants
 * to the second.
 *
 * <p><b>The Slots section is last</b> (qits-1778), after one more blank line, and nothing follows
 * it. It is not a source's: {@link DeskTriggerSweep} reads it when the window closes ({@link
 * VerifiedSlotHolders#section}), so it is current when it is sent rather than when the first line
 * was admitted, and hands it in. It says how many items run against {@code max_running} and which
 * of them are finished work waiting for a release — without it the desk could not tell "full of
 * work in progress" from "full of VERIFIED work waiting for one build". It is added only under the
 * delivery preamble: the sweep hands in none for a project with delivery off or a reading that
 * failed, and a window of waits only (below) never shows one.
 *
 * <p><b>The pointer</b> (qits-1734) is {@link DeskBrief#pointer}, composed from the protocol jar
 * rather than written out here: it sends the desk to {@code qits help guide} and states the desk
 * variant's guide-hash at the moment the window closed, so the desk re-reads its guide only when
 * that hash differs from the one it last read. The sentence itself lives only in {@link DeskBrief}.
 *
 * <p>The opening sentence is what the desk's brief keys on: "a message that starts {@code You are
 * the delivery desk}" is a desk trigger. Changing it is a change to the brief too.
 *
 * <p><b>A window holding only what the desk asked for</b> (qits-1618: every line from a source that
 * {@linkplain DeskTriggerSource#requestedByDesk() answers the desk}, today only {@code qits wait})
 * opens with a shorter preamble instead:
 *
 * <pre>
 * A wait you declared has closed. &lt;DeskBrief.pointer(hash)&gt; (desk trigger &lt;window id&gt;,
 * &lt;opened&gt; to &lt;closes&gt;)
 *
 * Waits you declared:
 * Your wait "approve rr-264" matched: ReleaseRequestStateChanged at … (event …).
 * …
 * </pre>
 *
 * <p>Such a window is the common case with delivery off, where the desk is not the delivery desk and
 * must not be called the delivery desk; the turn's label says what the desk meant to do. It still
 * carries the same pointer, so a desk whose guide moved re-reads it before acting. A window that
 * mixes both keeps the delivery preamble: its other lines are delivery news.
 */
public final class DeskTriggerMessage {

  private DeskTriggerMessage() {}

  /**
   * The message of {@code window}, from its events grouped by the source that admitted them. A
   * source with an empty list renders nothing. {@code guideHash} is the desk variant's guide-hash at
   * close, stated in the pointer.
   */
  public static String render(
      String projectSlug,
      String guideHash,
      DeskTriggerWindow window,
      Map<DeskTriggerSource, List<DeskTriggerEventRow>> eventsBySource) {
    return render(projectSlug, guideHash, window, eventsBySource, null);
  }

  /**
   * {@link #render(String, String, DeskTriggerWindow, Map)} ending with {@code slots}, the Slots
   * section ({@link VerifiedSlotHolders#section}), when the message has the delivery preamble.
   * Null leaves it out.
   */
  public static String render(
      String projectSlug,
      String guideHash,
      DeskTriggerWindow window,
      Map<DeskTriggerSource, List<DeskTriggerEventRow>> eventsBySource,
      String slots) {
    boolean onlyRequested =
        eventsBySource.entrySet().stream()
            .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
            .allMatch(entry -> entry.getKey().requestedByDesk());
    boolean anyLine =
        eventsBySource.values().stream().anyMatch(events -> events != null && !events.isEmpty());
    boolean waitsOnly = anyLine && onlyRequested;
    StringBuilder message = new StringBuilder();
    if (waitsOnly) {
      message.append("A wait you declared has closed. ").append(DeskBrief.pointer(guideHash));
    } else {
      message
          .append("You are the delivery desk of project ")
          .append(projectSlug)
          .append(". ")
          .append(DeskBrief.pointer(guideHash))
          .append(" Then act on what happened below.");
    }
    message
        .append(" (desk trigger ")
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
    if (!waitsOnly && slots != null && !slots.isBlank()) {
      message.append("\n\n").append(slots);
    }
    return message.toString();
  }

  private static String iso(Instant instant) {
    return instant.truncatedTo(ChronoUnit.SECONDS).toString();
  }
}
