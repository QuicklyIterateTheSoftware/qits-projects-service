package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import java.util.UUID;

/** What every source does first with a frame: read its payload, and its id. */
final class DeskTriggerFrames {

  private DeskTriggerFrames() {}

  /** The payload as {@code type}; throws on anything that will not read as one. */
  static <T> T decode(EventFrame frame, Class<T> type) {
    return CanonicalJson.payloadTo(frame.payload(), type);
  }

  /**
   * The frame's own id, which is the bus event's. A payload's {@code eventId} is not on the wire
   * ({@code CanonicalJson} hides it), so this is the only id the event has here; one that is not a
   * UUID throws, which makes the frame poison for the source asking.
   */
  static UUID eventIdOf(EventFrame frame) {
    return UUID.fromString(frame.id());
  }
}
