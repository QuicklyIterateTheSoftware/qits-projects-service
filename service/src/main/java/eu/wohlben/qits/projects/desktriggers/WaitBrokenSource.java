package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.entity.Project;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <b>An agent's wait broke</b> (qits-1961), the new terminal state qits-workspaces-service gives a
 * {@code qits wait}: not matched, not timed out and not cancelled, but ended because it could never
 * fire. The desk hears of it as news, so a run stuck behind a wait that will never resolve is not
 * silently abandoned.
 *
 * <p>Reads qits-workspaces' {@code AgentWaitClosed}, published whenever any agent's wait closes —
 * {@link #BROKEN} is the one state this source acts on; {@code MATCHED}, {@code TIMED_OUT} and
 * {@code CANCELLED} are ordinary closes and are ignored here (none of them is this ticket's
 * concern, and nothing else in this repository reads this event). Unlike {@link WaitClosedSource},
 * which answers the DESK's own {@code qits wait}, this is an AGENT's wait — the desk did not declare
 * it and did not cause it breaking, so there is no principal.
 *
 * <p>The line's {@code item_ref} is the agent's work item, qualified, read the way {@link
 * AgentIdleSource} reads one — the payload's {@code workId} resolved against this service's own
 * {@link WorkEntity} and the item's project slug — when that resolves; otherwise {@code item_ref}
 * falls back to {@code agent <id8>}. {@code item_id} is the wait id, so a reader of the row can find
 * it again. The label is {@code broken wait "<label>": <reason>}, both cut to {@link
 * RequestFailedSource#DETAIL_MAX} characters, one line.
 *
 * <p><b>Once per event.</b> {@code item_ref} varies with what resolves, but {@code waitId} does
 * not, and nothing fans one {@code AgentWaitClosed} out to more than one project — so the frame's
 * own event id, paired with {@code item_ref} and this source's {@link #ID}, is already what V65's
 * {@code desk_trigger_event_once} index keys on ({@code (source, event_id, item_ref)}): a
 * redelivered frame resolves the same {@code item_ref} and adds nothing, in any window.
 *
 * <p>The payload is a local record bound by {@link CanonicalJson} ({@link WaitClosedSource}'s
 * reasoning: qits-workspaces' event type is not on this classpath), registered for native-image in
 * {@code bus/EventWireReflection}. Ids are read as strings, so an id that is not a UUID is carried
 * as written rather than failing the frame.
 */
@ApplicationScoped
public class WaitBrokenSource implements DeskTriggerSource {

  /** Storage — see {@link DeskTriggerSource#id()}. */
  static final String ID = "wait-broken";

  /** The wire name qits-workspaces publishes under. */
  static final String SIGNATURE = "AgentWaitClosed";

  /** The one state this source acts on; {@code MATCHED}/{@code TIMED_OUT}/{@code CANCELLED} are
   * ignored. */
  static final String BROKEN = "BROKEN";

  /**
   * The fields this source reads of qits-workspaces' {@code AgentWaitClosed}. Unknown fields
   * ({@code workspaceId}, {@code repositoryId}, {@code matchedEventId}) are ignored by the
   * library's mapper. Public so {@code bus/EventWireReflection} can name it.
   */
  public record AgentWaitClosedPayload(
      String agentId,
      String workId,
      String projectId,
      String waitId,
      String state,
      String label,
      String reason,
      Instant occurredAt) {}

  /** The agent's item, read out of the epics transaction: {@code id}, {@code projectId}, {@code
   * number}. */
  private record WorkItem(String id, String projectId, long number) {}

  @Override
  public String id() {
    return ID;
  }

  /** Near {@link AgentIdleSource} (55): both say an agent needs a look. */
  @Override
  public int order() {
    return 56;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  @Override
  public List<DeskTrigger> qualify(EventFrame frame) {
    AgentWaitClosedPayload closed = DeskTriggerFrames.decode(frame, AgentWaitClosedPayload.class);
    if (!BROKEN.equals(closed.state())
        || isBlank(closed.projectId())
        || isBlank(closed.waitId())
        || isBlank(closed.agentId())) {
      return List.of();
    }
    String itemRef = itemRef(closed);
    String cause = "agent " + id8(closed.agentId());
    Instant occurredAt = closed.occurredAt() == null ? frame.occurredAt() : closed.occurredAt();
    return List.of(
        new DeskTrigger(
            closed.projectId(),
            itemRef,
            closed.waitId(),
            cause,
            label(closed),
            DeskTriggerFrames.eventIdOf(frame),
            occurredAt,
            null,
            null));
  }

  /** {@code - <item_ref>: broken wait "<label>": <reason>}. */
  @Override
  public String render(String projectSlug, List<DeskTriggerEventRow> events) {
    return "Agents' waits that broke:\n"
        + events.stream()
            .map(
                event ->
                    "- " + event.itemRef() + (isBlank(event.label()) ? "" : ": " + event.label()))
            .collect(Collectors.joining("\n"));
  }

  /**
   * The agent's work item, qualified, when {@code workId} resolves to a row of this service's own
   * with a known project slug ({@link AgentIdleSource}'s reading); otherwise {@code agent <id8>},
   * which can never collide with a qualified id.
   */
  private static String itemRef(AgentWaitClosedPayload closed) {
    if (!isBlank(closed.workId())) {
      WorkItem item = workItem(closed.workId());
      if (item != null && item.number() >= 1) {
        Project project = Project.findById(item.projectId());
        if (project != null && !isBlank(project.slug)) {
          return QualifiedEntityIds.render(project.slug, item.number());
        }
      }
    }
    return "agent " + id8(closed.agentId());
  }

  /** {@code broken wait "<label>": <reason>}; no reason leaves off the colon. */
  static String label(AgentWaitClosedPayload closed) {
    String waitLabel =
        closed.label() == null ? "" : RequestFailedSource.cut(closed.label().strip());
    String text = "broken wait \"" + waitLabel + "\"";
    return isBlank(closed.reason())
        ? text
        : text + ": " + RequestFailedSource.cut(closed.reason().strip());
  }

  /**
   * {@code workId}'s row, read in a transaction of its own: work items live in the epics
   * datasource, and the listener's own {@code projects} transaction may not enlist a second
   * non-XA resource ({@code ARJUNA016045}).
   */
  private static WorkItem workItem(String workId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkEntity row = WorkEntity.findById(workId);
              return row == null ? null : new WorkItem(row.id, row.projectId, row.number);
            });
  }

  private static String id8(String value) {
    return value.length() <= 8 ? value : value.substring(0, 8);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
