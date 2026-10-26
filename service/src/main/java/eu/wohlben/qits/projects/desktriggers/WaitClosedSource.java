package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <b>A {@code qits wait} the desk declared has closed</b> (qits-1618): the desk ended its turn to
 * wait for something, and this is the turn that wakes it.
 *
 * <p>Reads qits-workspaces' {@code DeskWaitClosed}, published when a desk wait matched its event or
 * timed out. The far side has already written the whole turn — {@code Your wait "<label>" matched:
 * <event> at <when> (event <id>).} and a data block — so this source adds nothing to it: the turn
 * is stored as the line's {@code label} and rendered verbatim. The line's {@code item_ref} is the
 * wait id, its {@code cause} {@code wait "<label>" <state>}, which says in the history which wait
 * it was and can never equal a desk subject.
 *
 * <p><b>It answers the desk</b> ({@link #requestedByDesk()}): admitted with delivery off and though
 * the desk caused the event it waited on, and its window closes at once.
 *
 * <p><b>A wait closes once.</b> The window store's {@code on conflict do nothing} makes a repeat
 * one line within one window; a wait's window closes within a sweep interval, so a redelivery that
 * arrives after it would open a second window and wake the desk twice. {@link #qualify} therefore
 * answers nothing for a wait id any window of any project already holds a {@code wait} line for.
 *
 * <p>The payload is a local record bound by {@link CanonicalJson} ({@code
 * bus/DeploymentActiveListener}'s reasoning: qits-workspaces' event type is not on this classpath),
 * registered for native-image in {@code bus/EventWireReflection}.
 */
@ApplicationScoped
public class WaitClosedSource implements DeskTriggerSource {

  /** Storage — see {@link DeskTriggerSource#id()}. */
  static final String ID = "wait";

  /** The wire name qits-workspaces publishes under. */
  static final String SIGNATURE = "DeskWaitClosed";

  /**
   * The fields this source reads of qits-workspaces' {@code DeskWaitClosed}. Unknown fields are
   * ignored by the library's mapper. Public so {@code bus/EventWireReflection} can name it.
   *
   * @param state {@code MATCHED} or {@code TIMED_OUT}
   * @param turn the whole turn the desk is to read, written by the far side
   */
  public record DeskWaitClosedPayload(
      String waitId,
      String projectId,
      String label,
      String state,
      String eventName,
      String matchedEventId,
      String turn,
      Instant closedAt) {}

  @Override
  public String id() {
    return ID;
  }

  /** First: what the desk asked for heads the message. */
  @Override
  public int order() {
    return 0;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  @Override
  public boolean requestedByDesk() {
    return true;
  }

  @Override
  public List<DeskTrigger> qualify(EventFrame frame) {
    DeskWaitClosedPayload closed = DeskTriggerFrames.decode(frame, DeskWaitClosedPayload.class);
    if (isBlank(closed.waitId()) || isBlank(closed.projectId())) {
      return List.of();
    }
    if (DeskTriggerEvent.count("source = ?1 and itemRef = ?2", ID, closed.waitId()) > 0) {
      return List.of();
    }
    Instant occurredAt = closed.closedAt() == null ? frame.occurredAt() : closed.closedAt();
    return List.of(
        new DeskTrigger(
            closed.projectId(),
            closed.waitId(),
            closed.waitId(),
            "wait \"" + nullToEmpty(closed.label()) + "\" " + nullToEmpty(closed.state()),
            turnOf(closed),
            DeskTriggerFrames.eventIdOf(frame),
            occurredAt));
  }

  /** {@code Waits you declared:}, then each turn verbatim, one blank line between turns. */
  @Override
  public String render(String projectSlug, List<DeskTriggerEventRow> events) {
    return "Waits you declared:\n"
        + events.stream().map(DeskTriggerEventRow::label).collect(Collectors.joining("\n\n"));
  }

  /** The far side's turn; a payload without one still names the wait and how it closed. */
  private static String turnOf(DeskWaitClosedPayload closed) {
    if (!isBlank(closed.turn())) {
      return closed.turn();
    }
    String how = "TIMED_OUT".equals(closed.state()) ? "timed out" : "matched";
    return "Your wait \""
        + nullToEmpty(closed.label())
        + "\" "
        + how
        + (isBlank(closed.eventName()) ? "" : ": " + closed.eventName())
        + ".";
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
