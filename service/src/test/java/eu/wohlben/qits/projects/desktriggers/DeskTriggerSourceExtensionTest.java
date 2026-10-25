package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A third source is one more {@link DeskTriggerSource} and nothing else</b> (qits-1609's
 * extensibility criterion). A source this suite owns, reading a signature no real source reads, is
 * handed to a listener beside the two real ones; its frame lands in the project's window through the
 * unchanged listener and the unchanged window store, under its own source id.
 *
 * <p>The listener is constructed here rather than the source being registered as a bean: a bean
 * under {@code src/test} would join every other {@code @QuarkusTest}'s application and change the
 * real listener's signatures there. Its collaborators are the container's own.
 */
@QuarkusTest
class DeskTriggerSourceExtensionTest {

  /** A source no production code knows about. */
  static final class ProbeSource implements DeskTriggerSource {

    static final String SIGNATURE = "DeskTriggerProbe";

    @Override
    public String id() {
      return "test-probe";
    }

    @Override
    public int order() {
      return 30;
    }

    @Override
    public Set<String> signatures() {
      return Set.of(SIGNATURE);
    }

    @Override
    public List<DeskTrigger> qualify(EventFrame frame) {
      try {
        JsonNode payload = new ObjectMapper().readTree(frame.payload());
        return List.of(
            new DeskTrigger(
                payload.path("projectId").asText(),
                payload.path("ref").asText(),
                null,
                "probe",
                "probed",
                UUID.fromString(frame.id()),
                frame.occurredAt()));
      } catch (Exception e) {
        throw new IllegalArgumentException(e);
      }
    }

    @Override
    public String render(String projectSlug, List<DeskTriggerEventRow> events) {
      return "Probed:\n"
          + events.stream().map(DeskTriggerEventRow::line).collect(Collectors.joining("\n"));
    }
  }

  @Inject DeskTriggerWindows windows;

  @Inject WorkVerifiedSource workVerified;

  @Inject ApprovalNeededSource approvalNeeded;

  private final List<String> projects = new ArrayList<>();

  @AfterEach
  void drop() {
    DeskTriggerFixtures.drop(projects);
    projects.clear();
  }

  @Test
  void aThirdSourcesTriggersAreAdmittedWithNoChangeToListenerOrWindows() {
    String projectId = DeskTriggerFixtures.project(true);
    projects.add(projectId);
    DeskTriggerListener listener =
        new DeskTriggerListener(List.of(new ProbeSource(), workVerified, approvalNeeded), windows);

    assertEquals(
        Set.of("EntityTransitioned", "ApprovalRequirementChanged", ProbeSource.SIGNATURE),
        listener.signatures());
    EventFrame probe =
        new EventFrame(
            UUID.randomUUID().toString(),
            ProbeSource.SIGNATURE,
            Instant.now(),
            "{\"projectId\":\"" + projectId + "\",\"ref\":\"probe-1\"}",
            null,
            null,
            null);
    assertTrue(listener.selects(probe));

    listener.onFrame(probe);

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size());
    assertEquals("test-probe", lines.get(0).source);
    assertEquals("probe-1", lines.get(0).itemRef);
  }

  @Test
  void theRealListenerDoesNotSeeTheProbe() {
    // The other half of the javadoc's claim: nothing in src/test registered it.
    DeskTriggerListener real = Arc.container().instance(DeskTriggerListener.class).get();
    assertTrue(!real.signatures().contains(ProbeSource.SIGNATURE));
  }
}
