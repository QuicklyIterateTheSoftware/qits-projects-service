package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditEntry;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.AuditRepository;
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
 * {@code AgentRemoved}. Both carry the agent's work item, which names the project. It qualifies when
 * the project's running items ({@link RunningItems}, the reading the dispatch check makes) are then
 * fewer than its {@code max_running}. The listener drops it for a project with delivery off. A
 * count that cannot be read (qits-workspaces away) reads as nothing running, so the desk gets a
 * pass it may not need rather than an idle slot. {@code WorkspaceRemoved} is not read: a workspace
 * goes only after its last agent did, which already fired, and the event names no work item.
 *
 * <p><b>Only a leave that freed a slot</b> (qits-1694). Two kinds of event free none, and are
 * skipped before anything is counted:
 *
 * <ul>
 *   <li>{@code AgentRemoved} for any reason but {@code DISCARDED} or {@code UNKNOWN_TO_DAEMON}, the
 *       only removals of an agent that may still have been ACTIVE. The {@code OBSOLETE_*}, {@code
 *       RELEASED_IDLE_CLEAN} and {@code DROPPED_IDLE_CLEAN} removals follow an {@code
 *       AgentObsolete}: that agent left the count at OBSOLETE, and a project full then and with room
 *       now would otherwise be told of a slot that opened earlier. A removal with no reason is
 *       skipped with them.
 *   <li>An item that is explicitly {@code blocked} or has a blank {@code assignee}: {@link
 *       RunningItems} never counted it, so its agent leaving changes nothing.
 * </ul>
 *
 * <p><b>One line per agent</b>, ever: the row's {@code item_id} is the agent's id, and an agent some
 * window already holds a line for is not reported again; V65's partial unique index on {@code
 * (source, item_id)} makes that the schema's rule too, so two admitters racing still store one. A
 * replay adds nothing.
 *
 * <p><b>The principal</b> (qits-1694) is who made the agent leave, so the listener's desk-caused drop
 * applies to the desk's own moves:
 *
 * <ul>
 *   <li>A drop: an {@code AgentObsolete} whose item is now DROPPED carries no actor, so the principal
 *       is the {@code changed_by} of the item's newest audit row — the write that dropped it.
 *   <li>A discard: {@code AgentRemoved}'s {@code discardedBy}, the discarding caller's token subject
 *       (the desk holds {@code qits:desk} on the discard door). Absent from older publishers, which
 *       reads as no principal.
 * </ul>
 *
 * <p>Otherwise — an item closed and released, an agent its daemon lost — nothing caused it and there
 * is no principal. The cause is the principal when there is one, else {@code agent <id8>}. The
 * payload is a local record ({@link WaitClosedSource}'s reasoning), registered for native-image in
 * {@code bus/EventWireReflection}.
 */
@ApplicationScoped
public class CapacityOpenedSource implements DeskTriggerSource {

  /** Storage — see {@link DeskTriggerSource#id()}. */
  static final String ID = "capacity-opened";

  static final String OBSOLETE = "AgentObsolete";

  static final String REMOVED = "AgentRemoved";

  /** The {@code AgentRemoved} reasons of an agent that may still have been ACTIVE. */
  static final Set<String> FREEING_REMOVALS = Set.of("DISCARDED", "UNKNOWN_TO_DAEMON");

  /**
   * The fields this source reads of qits-workspaces' {@code AgentObsolete} and {@code
   * AgentRemoved}; {@code reason} and {@code discardedBy} are absent on the first, and {@code
   * discardedBy} on an {@code AgentRemoved} that is not a discard or that predates the field. Public
   * so {@code bus/EventWireReflection} can name it.
   */
  public record AgentLeftPayload(
      String agentId, String workId, String reason, Instant occurredAt, String discardedBy) {}

  /** The leaving agent's item as this source reads it, with who dropped it if it was dropped. */
  private record LeftItem(WorkEntity item, String droppedBy) {}

  @Inject RunningItems running;

  @Inject AuditRepository audit;

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
    boolean removed = REMOVED.equals(frame.name());
    if (removed && !FREEING_REMOVALS.contains(left.reason())) {
      return List.of();
    }
    if (DeskTriggerEvent.count("source = ?1 and itemId = ?2", ID, left.agentId()) > 0) {
      return List.of();
    }
    // Work items and their audit live in the epics datasource: read them in a transaction of
    // their own, never in the listener's projects one (one non-XA resource per transaction).
    LeftItem read =
        QuarkusTransaction.requiringNew().call(() -> readItem(left.workId(), !removed));
    WorkEntity item = read == null ? null : read.item();
    if (item == null || item.projectId == null || item.blocked || isBlank(item.assignee)) {
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
        removed
            ? "its agent was removed" + (isBlank(left.reason()) ? "" : " (" + left.reason() + ")")
            : "its agent is done";
    String principal = removed ? blankToNull(left.discardedBy()) : read.droppedBy();
    return List.of(
        new DeskTrigger(
            project.id,
            itemRef,
            left.agentId(),
            principal != null
                ? principal
                : "agent " + left.agentId().substring(0, Math.min(8, left.agentId().length())),
            how + "; running " + now + " of " + project.deliveryMaxRunning,
            DeskTriggerFrames.eventIdOf(frame),
            left.occurredAt() == null ? frame.occurredAt() : left.occurredAt(),
            null,
            principal));
  }

  /**
   * The item, and — when {@code askWhoDropped} and it is DROPPED — the {@code changed_by} of its
   * newest audit row, the write that dropped it. Inside the caller's epics transaction.
   */
  private LeftItem readItem(String workId, boolean askWhoDropped) {
    WorkEntity item = WorkEntity.findById(workId);
    if (item == null) {
      return null;
    }
    if (!askWhoDropped
        || item.archetype == null
        || !EntityStatus.DROPPED.name().equals(item.status)) {
      return new LeftItem(item, null);
    }
    List<AuditEntry> history = audit.listForEntity(AuditEntityType.of(item.archetype), item.id);
    return new LeftItem(item, history.isEmpty() ? null : blankToNull(history.get(0).changedBy));
  }

  private static String blankToNull(String value) {
    return isBlank(value) ? null : value;
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
