package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.api.RunningItems;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.Project;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <b>An agent left, and the project now runs fewer items than it may</b> (qits-1689). The desk
 * dispatches only on its passes, while running is below {@code front_desk.delivery.max_running};
 * work-verified fires while the agent still holds its slot, so without this a freed slot waits for
 * some unrelated trigger.
 *
 * <p>Reads qits-workspaces' {@code AgentObsolete} (the item closed and its work released, or the
 * item was dropped: the agent stops being ACTIVE, which is when it leaves the running count) and
 * {@code AgentRemoved} (also a discard, or an agent its daemon lost, which need not pass through
 * OBSOLETE). Both carry the agent's work item, which names the project. It qualifies when the
 * project's running items ({@link RunningItems}, the reading the dispatch check makes) are then
 * fewer than its {@code max_running}. The listener drops it for a project with delivery off. A
 * count that cannot be read (qits-workspaces away) reads as nothing running, so the desk gets a
 * pass it may not need rather than an idle slot. {@code WorkspaceRemoved} is not read: a workspace
 * goes only after its last agent did, which already fired, and the event names no work item.
 *
 * <p><b>One line per agent</b>, ever: the row's {@code item_id} is the agent's id, and an agent some
 * window already holds a line for is not reported again. So {@code AgentObsolete} and the later
 * {@code AgentRemoved} of one agent are one line, and a replay adds nothing.
 *
 * <p>Neither event names who caused it, so there is no principal and nothing for the desk-caused
 * drop to match. The cause is {@code agent <id8>}. The payload is a local record ({@link
 * WaitClosedSource}'s reasoning), registered for native-image in {@code bus/EventWireReflection}.
 */
@ApplicationScoped
public class CapacityOpenedSource implements DeskTriggerSource {

  /** Storage — see {@link DeskTriggerSource#id()}. */
  static final String ID = "capacity-opened";

  static final String OBSOLETE = "AgentObsolete";

  static final String REMOVED = "AgentRemoved";

  /**
   * The fields this source reads of qits-workspaces' {@code AgentObsolete} and {@code
   * AgentRemoved}; {@code reason} is absent on the first. Public so {@code bus/EventWireReflection}
   * can name it.
   */
  public record AgentLeftPayload(
      String agentId, String workId, String reason, Instant occurredAt) {}

  @Inject RunningItems running;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public int order() {
    return 60;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(OBSOLETE, REMOVED);
  }

  @Override
  public List<DeskTrigger> qualify(EventFrame frame) {
    AgentLeftPayload left = DeskTriggerFrames.decode(frame, AgentLeftPayload.class);
    if (isBlank(left.agentId()) || isBlank(left.workId())) {
      return List.of();
    }
    if (DeskTriggerEvent.count("source = ?1 and itemId = ?2", ID, left.agentId()) > 0) {
      return List.of();
    }
    // Work items live in the epics datasource: read them in a transaction of their own, never
    // in the listener's projects one (one non-XA resource per transaction).
    WorkEntity item =
        QuarkusTransaction.requiringNew().call(() -> WorkEntity.findById(left.workId()));
    if (item == null || item.projectId == null) {
      return List.of();
    }
    Project project = Project.findById(item.projectId);
    if (project == null || !project.deliveryEnabled) {
      return List.of();
    }
    String projectId = project.id;
    int now = QuarkusTransaction.requiringNew().call(() -> running.of(projectId, null).size());
    if (now >= project.deliveryMaxRunning) {
      return List.of();
    }
    String itemRef =
        project.slug == null || item.number < 1
            ? item.id
            : QualifiedEntityIds.render(project.slug, item.number);
    String how =
        OBSOLETE.equals(frame.name())
            ? "its agent is done"
            : "its agent was removed" + (isBlank(left.reason()) ? "" : " (" + left.reason() + ")");
    return List.of(
        new DeskTrigger(
            project.id,
            itemRef,
            left.agentId(),
            "agent " + left.agentId().substring(0, Math.min(8, left.agentId().length())),
            how + "; running " + now + " of " + project.deliveryMaxRunning,
            DeskTriggerFrames.eventIdOf(frame),
            left.occurredAt() == null ? frame.occurredAt() : left.occurredAt(),
            null,
            null));
  }

  @Override
  public String render(String projectSlug, List<DeskTriggerEventRow> events) {
    return "A slot opened; dispatch the next item:\n"
        + events.stream().map(DeskTriggerEventRow::line).collect(Collectors.joining("\n"));
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
