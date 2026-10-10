package eu.wohlben.qits.projects.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.eventstream.control.EventsPublisher;
import eu.wohlben.qits.eventstream.control.EventsQuery;
import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * <b>What qits-projects asks qits-events</b> (ticket qits-1149), through the shared
 * qits-eventstream library: the outbox's publish ({@link EventsPublisher}) and the catch-up
 * sweeper's two reads of the log ({@link EventsQuery}). The live stream ({@code /events/stream}) is
 * a WebSocket and has no row.
 *
 * <p>The reads run as {@code DeploymentActiveListener} does: one name, {@code DeploymentActive},
 * which the provider state "a few recent events" holds. That state was recorded for a different
 * query ({@code ?limit=20}); the row sends its own query and binds only the fields the library
 * decodes, so the recording is the example and the verifier asks the provider what it answers to
 * this request.
 */
final class EventsContract {

  static final String PROVIDER = "qits-events-service";
  static final String APP = "qits-events";

  private static final String NAME = "DeploymentActive";

  private static final List<String> FRAME =
      List.of(
          "$.events[*].id",
          "$.events[*].name",
          "$.events[*].occurredAt",
          "$.events[*].payload",
          "$.events[*].description",
          "$.events[*].parentId",
          "$.events[*].environment");

  private static final EventEnvelope ENVELOPE =
      new EventEnvelope(
          "ReleaseRequestChanged",
          Instant.parse("2026-01-01T00:00:00Z"),
          "{\"state\":\"PENDING\"}",
          "A release request is waiting for its checks",
          null,
          null);

  private EventsContract() {}

  private static EventsQuery query(String base) {
    return Fields.with(new EventsQuery(), "eventsUrl", base);
  }

  /** {@code EventsQuery.after} and {@code newest} are package-private: the library's own seam. */
  @SuppressWarnings("unchecked")
  private static List<EventFrame> after(String base) throws Exception {
    Object page =
        Fields.call(
            query(base),
            "after",
            new Class<?>[] {Collection.class, String.class, int.class},
            List.of(NAME),
            null,
            200);
    Method events = page.getClass().getDeclaredMethod("events");
    events.setAccessible(true);
    return (List<EventFrame>) events.invoke(page);
  }

  private static EventFrame newest(String base) throws Exception {
    return (EventFrame)
        Fields.call(query(base), "newest", new Class<?>[] {Collection.class}, List.of(NAME));
  }

  static final List<ConsumerRow> ROWS =
      List.of(
          new ConsumerRow(
              PROVIDER,
              APP,
              "listEvents",
              "a few recent events",
              "GET",
              "/events/api/events",
              Map.of("order", "asc", "limit", "200", "name", NAME),
              null,
              FRAME,
              200,
              Trigger.schedule("CatchupSweeper.catchUp"),
              (base, p) -> {
                List<EventFrame> frames = after(base);
                assertFalse(frames.isEmpty());
                assertEquals(NAME, frames.get(0).name());
                assertNotNull(frames.get(0).occurredAt());
              },
              "GET /events/api/events?order=asc&limit=200&name=DeploymentActive answering 200 with"
                  + " events[] of the seeded DeploymentActive event"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "listEvents",
              "a few recent events",
              "GET",
              "/events/api/events",
              Map.of("limit", "1", "name", NAME),
              null,
              FRAME,
              200,
              Trigger.schedule("CatchupSweeper.initialize"),
              (base, p) -> assertEquals(NAME, newest(base).name()),
              "GET /events/api/events?limit=1&name=DeploymentActive answering 200 with the newest"
                  + " DeploymentActive event"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "publishEvent",
              "no event with the given id",
              "PUT",
              "/events/api/events/{eventId}",
              Map.of(),
              ConsumerRow.json(CanonicalJson.envelope(ENVELOPE)),
              List.of(),
              201,
              Trigger.schedule("OutboxSweeper.sweep"),
              (base, p) ->
                  assertTrue(
                      Fields.with(
                              new EventsPublisher(),
                              "eventsUrl", base,
                              "publishTimeout", Duration.ofSeconds(5))
                          .put(p.get("eventId"), ENVELOPE)
                          .delivered()),
              "PUT /events/api/events/{eventId} answering 201 for an id the log does not hold"));
}
