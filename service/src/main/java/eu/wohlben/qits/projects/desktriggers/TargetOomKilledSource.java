package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <b>A target's node lost processes to an out-of-memory killer</b> (qits-1818), so the desk hears
 * of an OOM kill without host access: a red build or a stuck agent on that target may be its
 * victim.
 *
 * <p>Reads qits-system's {@code TargetOomKilled}, published when the counter a target's runner
 * reports rose — {@code nodeOomKills}, the node's cgroup tree's own, else {@code kernelOomKills},
 * the physical host's — carrying by how much ({@code delta}) and the projects whose leases occupied
 * the target then ({@code projectIds}). It qualifies one trigger per named project; the listener's
 * shared rules drop a project with delivery off. Nothing the desk did causes an OOM kill, so there
 * is no principal, and the cause {@code oom <target id8>} can never equal a desk subject.
 *
 * <p>The line reads {@code - target ws-proof: the out-of-memory killer killed 3 processes (node
 * counter 292 → 295)}: {@code item_ref} is {@code target <name>} (the target's id when it has no
 * name), {@code item_id} the target's id, the label the rest. The previous value is the counter
 * less {@code delta}; the bracket names the {@code kernel counter} when the node reports none, and
 * is left out when neither is on the event.
 *
 * <p><b>Once per event and project.</b> One {@code TargetOomKilled} fans out to several projects
 * under one bus event id, and V65's {@code (source, event_id, item_ref)} is unique across every
 * window and project — so each trigger's event id is {@link #oomKey(UUID, String)}, derived from
 * the bus event's and the project's. A redelivered frame derives the same keys, and the window
 * store's {@code on conflict do nothing} absorbs it in any window: a replay adds nothing.
 *
 * <p>The payload is a local record bound by {@link CanonicalJson} ({@link WaitClosedSource}'s
 * reasoning: qits-system's event type is not on this classpath), registered for native-image in
 * {@code bus/EventWireReflection}. Ids are read as strings, so an id that is not a UUID is carried
 * as written rather than failing the frame.
 */
@ApplicationScoped
public class TargetOomKilledSource implements DeskTriggerSource {

  /** Storage — see {@link DeskTriggerSource#id()}. */
  static final String ID = "target-oom-killed";

  /** The wire name qits-system publishes under. */
  static final String SIGNATURE = "TargetOomKilled";

  /**
   * The fields this source reads of qits-system's {@code TargetOomKilled}. Unknown fields are
   * ignored by the library's mapper; either counter may be absent. Public so {@code
   * bus/EventWireReflection} can name it.
   */
  public record TargetOomKilledPayload(
      String targetId,
      String targetName,
      Long nodeOomKills,
      Long kernelOomKills,
      Long delta,
      List<String> projectIds,
      Instant occurredAt) {}

  @Override
  public String id() {
    return ID;
  }

  /** Between a failed request (40) and a stalled one (50): a likely cause of either. */
  @Override
  public int order() {
    return 45;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  @Override
  public List<DeskTrigger> qualify(EventFrame frame) {
    TargetOomKilledPayload killed = DeskTriggerFrames.decode(frame, TargetOomKilledPayload.class);
    if (killed.projectIds() == null
        || killed.projectIds().isEmpty()
        || (isBlank(killed.targetId()) && isBlank(killed.targetName()))
        || killed.delta() == null
        || killed.delta() < 1) {
      return List.of();
    }
    UUID eventId = DeskTriggerFrames.eventIdOf(frame);
    String itemRef =
        "target " + (isBlank(killed.targetName()) ? killed.targetId() : killed.targetName());
    String label = label(killed);
    String cause =
        "oom " + id8(isBlank(killed.targetId()) ? killed.targetName() : killed.targetId());
    Instant occurredAt = killed.occurredAt() == null ? frame.occurredAt() : killed.occurredAt();
    return killed.projectIds().stream()
        .filter(Objects::nonNull)
        .filter(projectId -> !projectId.isBlank())
        .distinct()
        .map(
            projectId ->
                new DeskTrigger(
                    projectId,
                    itemRef,
                    killed.targetId(),
                    cause,
                    label,
                    oomKey(eventId, projectId),
                    occurredAt,
                    null,
                    null))
        .toList();
  }

  /** The line's event id: one per bus event and project, so a fan-out is one line per project. */
  static UUID oomKey(UUID eventId, String projectId) {
    return UUID.nameUUIDFromBytes(
        (ID + "|" + eventId + "|" + projectId).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * {@code the out-of-memory killer killed 3 processes (node counter 292 → 295)}, the bracket on
   * the kernel counter when the node reports none and left out when neither is known.
   */
  static String label(TargetOomKilledPayload killed) {
    long delta = killed.delta();
    String what =
        "the out-of-memory killer killed " + delta + (delta == 1 ? " process" : " processes");
    Long counter = killed.nodeOomKills() != null ? killed.nodeOomKills() : killed.kernelOomKills();
    if (counter == null) {
      return what;
    }
    String which = killed.nodeOomKills() != null ? "node counter" : "kernel counter";
    return what + " (" + which + " " + (counter - delta) + " → " + counter + ")";
  }

  /** {@code - target <name>: <label>}: the label is a sentence, not a bracketed note. */
  @Override
  public String render(String projectSlug, List<DeskTriggerEventRow> events) {
    return "Out-of-memory kills on targets running your work:\n"
        + events.stream()
            .map(
                event ->
                    "- " + event.itemRef() + (isBlank(event.label()) ? "" : ": " + event.label()))
            .collect(Collectors.joining("\n"));
  }

  private static String id8(String value) {
    return value.length() <= 8 ? value : value.substring(0, 8);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
