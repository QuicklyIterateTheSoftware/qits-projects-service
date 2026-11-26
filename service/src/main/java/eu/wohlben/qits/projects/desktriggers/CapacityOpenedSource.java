package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditEntry;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.AuditRepository;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.api.DeskSlots;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.api.RunningItems;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.targethost.SystemLeases;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>A slot opened: a workspace lease can be granted now, and none of the project's workspaces
 * waits</b> (qits-1689, qits-2573). The desk dispatches only on its passes, while N &lt; M and K is 0
 * ({@link DeskSlots}: N running, M = N + F with F the workspace leases the targets can grant, K the
 * items waiting for a workspace); work-verified fires while the agent still holds its slot, so
 * without this a freed slot waits for some unrelated trigger. {@code front_desk.delivery.max_running}
 * is not read.
 *
 * <p><b>The event path.</b> Reads qits-workspaces' {@code AgentObsolete} (the item closed and its
 * work released, or the item was dropped: the agent stops being ACTIVE, which is when it leaves the
 * running count) and {@code AgentRemoved}. Both carry the agent's work item, which names the
 * project. It qualifies only when the project's slots ({@link DeskSlots#read}, the reading the
 * dispatch check makes) are then open: F &gt; 0 and K = 0. A capacity that cannot be read qualifies
 * nothing (AC3: a slot is announced only when a lease can actually be granted). The listener drops
 * it for a project with delivery off. {@code WorkspaceRemoved} is not read: a workspace goes only
 * after its last agent did, which already fired, and the event names no work item.
 *
 * <p><b>The scan</b> ({@link #scan}, qits-2573) covers capacity that opens with no agent leaving: a
 * CI lease ends, a target reconnects, an owner raises slots. It reads the capacity once per sweep
 * pass, estate-wide, and for each delivery-enabled project raises one line only when (a) its slots
 * are open on this pass and the previous one (in memory, per project: a restart costs one pass), (b)
 * no line of this source of that project was admitted within {@code qits.projects.desk-triggers.window},
 * and (c) its key {@link #slotKey} — the project and the sorted ids of its running agents — is not
 * stored yet; it is the row's {@code item_id} and {@code event_id}, which V65's one-per-{@code
 * (source, item_id)} index keeps to one line per running set. So a CI lease flapping does not repeat
 * it. The line is {@code - qits (running 2 of 3; the targets can grant 1 more workspace lease:
 * ws-proof 1)}, cause {@code slots}, with no principal.
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
 *
 * <p><b>Still holds</b> (qits-2501, {@link #resolved}) while the project's slots are open at the
 * time of the check — the reading the Slots section makes ({@link VerifiedSlotHolders#read}): F = 0
 * or K &gt; 0 drops every line of the window, so a lease that goes and comes back inside the window
 * is not announced. A reading that cannot be made (the port absent or throwing, the capacity
 * unknown, the project gone) resolves nothing.
 */
@ApplicationScoped
public class CapacityOpenedSource implements DeskTriggerSource {

  private static final Logger LOG = Logger.getLogger(CapacityOpenedSource.class);

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

  @Inject DeskSlots deskSlots;

  @Inject RunningItems running;

  @Inject AuditRepository audit;

  @ConfigProperty(name = "qits.projects.desk-triggers.window", defaultValue = "PT15M")
  Duration window;

  /**
   * Whether each delivery-enabled project's slots were open on the previous scan, by project id: the
   * scan's two-pass debounce. In memory; a restart costs one pass.
   */
  private final Map<String, Boolean> openLastPass = new ConcurrentHashMap<>();

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
    DeskSlots.Reading slots = deskSlots.read(project.id, null);
    if (!slots.open()) {
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
            how + "; " + slots.runningText(),
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

  @Inject VerifiedSlotHolders slots;

  /** Every line of the window at once: one reading of the project's slots. */
  @Override
  public Set<DeskTriggerEventRow> resolved(
      String projectId, List<DeskTriggerEventRow> lines, Instant now) {
    try {
      Project project =
          QuarkusTransaction.requiringNew().call(() -> Project.<Project>findById(projectId));
      if (project == null) {
        return Set.of();
      }
      Optional<VerifiedSlotHolders.Reading> reading = slots.read(project);
      if (reading.isEmpty() || reading.get().free() == null || reading.get().open()) {
        return Set.of();
      }
      return new HashSet<>(lines);
    } catch (RuntimeException e) {
      return Set.of();
    }
  }

  /**
   * Capacity that opened with no agent leaving (qits-2573): see the class javadoc's "The scan". One
   * capacity read per pass for every project; a capacity that cannot be read scans nothing and
   * forgets the previous pass, so the next known pass starts the debounce again.
   */
  @Override
  public List<DeskTrigger> scan(Instant now) {
    List<Project> projects = Project.<Project>list("deliveryEnabled = true");
    if (projects.isEmpty()) {
      openLastPass.clear();
      return List.of();
    }
    Optional<SystemLeases.Capacity> capacity = deskSlots.capacity();
    if (capacity.isEmpty()) {
      openLastPass.clear();
      return List.of();
    }
    Set<String> seen = new HashSet<>();
    List<DeskTrigger> triggers = new ArrayList<>();
    for (Project project : projects) {
      String projectId = project.id;
      seen.add(projectId);
      DeskSlots.Reading slots;
      try {
        RunningItems.Slots held =
            QuarkusTransaction.requiringNew().call(() -> running.read(projectId, null));
        slots = DeskSlots.of(held, capacity);
      } catch (RuntimeException e) {
        LOG.warnf(
            "The slots of project %s could not be read (%s); looked at again next pass",
            projectId, e.toString());
        openLastPass.remove(projectId);
        continue;
      }
      boolean open = slots.open();
      Boolean before = openLastPass.put(projectId, open);
      if (!open || !Boolean.TRUE.equals(before)) {
        continue;
      }
      if (recentLine(projectId, now)) {
        continue;
      }
      UUID key = slotKey(projectId, slots.runningAgentIds());
      if (DeskTriggerEvent.count("source = ?1 and itemId = ?2", ID, key.toString()) > 0) {
        continue;
      }
      int free = slots.free();
      triggers.add(
          new DeskTrigger(
              projectId,
              project.slug == null ? projectId : project.slug,
              key.toString(),
              "slots",
              slots.runningText()
                  + "; the targets can grant "
                  + free
                  + " more workspace "
                  + (free == 1 ? "lease" : "leases")
                  + (slots.grantingTargets().isEmpty() ? "" : ": " + slots.grantingTargets()),
              key,
              now,
              null,
              null));
    }
    openLastPass.keySet().retainAll(seen);
    return triggers;
  }

  /** Whether a line of this source of {@code projectId} was admitted within the window of {@code now}. */
  private boolean recentLine(String projectId, Instant now) {
    return DeskTriggerEvent.count(
            "source = ?1 and admittedAt > ?2 and windowId in"
                + " (select w.id from DeskTriggerWindow w where w.projectId = ?3)",
            ID,
            now.minus(window),
            projectId)
        > 0;
  }

  /** The scan's key: the project and the sorted ids of its running agents (qits-2573). */
  static UUID slotKey(String projectId, List<String> runningAgentIds) {
    List<String> sorted = new ArrayList<>(runningAgentIds);
    Collections.sort(sorted);
    return UUID.nameUUIDFromBytes(
        (ID + "|" + projectId + "|" + String.join(",", sorted)).getBytes(StandardCharsets.UTF_8));
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
