package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApprovalNotice;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * <b>A release request has stopped moving</b> (qits-1689). No event says "time passed", so this
 * source reads no bus event: {@link #scan} runs on every {@link DeskTriggerSweep} pass and reads
 * the open requests.
 *
 * <ul>
 *   <li><b>RELEASED, not FINALIZED</b> for longer than {@code
 *       qits.projects.desk-triggers.stall.released} (1h), counted from the tag ({@code
 *       released_tag_pending_merge.released_at}). The label says what is open: the publish run,
 *       the DEPLOYMENT gate (deployment not active), or the merge into {@code main}.
 *   <li><b>PENDING</b> on its fold for longer than {@code qits.projects.desk-triggers.stall.pending}
 *       (2h), counted from {@code armed_at}: it waits for automations or CI. The label names the
 *       fold, so a new fold in the same window is its own line. A request waiting on an
 *       open approval requirement is left out (approval-needed tells the desk of those), and so is
 *       one queued behind another request.
 * </ul>
 *
 * <p>A stall older than {@code qits.projects.desk-triggers.stall.max-age} (7d) is not reported: it
 * has been seen. <b>Once per stall:</b> a line's event id is a name-based UUID of (request, state,
 * fold) ({@link #stallKey}), and a stall some window already holds is not found again. A new fold or
 * a new state is a new stall. Nothing causes a stall, so no principal: the desk's own actions do not
 * apply here.
 */
@ApplicationScoped
public class RequestStalledSource implements DeskTriggerSource {

  /** Storage — see {@link DeskTriggerSource#id()}. */
  static final String ID = "request-stalled";

  @ConfigProperty(name = "qits.projects.desk-triggers.stall.released", defaultValue = "PT1H")
  Duration released;

  @ConfigProperty(name = "qits.projects.desk-triggers.stall.pending", defaultValue = "PT2H")
  Duration pending;

  @ConfigProperty(name = "qits.projects.desk-triggers.stall.max-age", defaultValue = "P7D")
  Duration maxAge;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public int order() {
    return 50;
  }

  /** None: a stall is found by {@link #scan}. */
  @Override
  public Set<String> signatures() {
    return Set.of();
  }

  @Override
  public List<DeskTrigger> qualify(EventFrame frame) {
    return List.of();
  }

  @Override
  public List<DeskTrigger> scan(Instant now) {
    List<DeskTrigger> triggers = new ArrayList<>();
    for (ReleaseRequest row :
        ReleaseRequest.<ReleaseRequest>list(
            "state in ?1 and projectId is not null",
            List.of(ReleaseRequest.State.RELEASED, ReleaseRequest.State.PENDING))) {
      DeskTrigger trigger =
          row.state == ReleaseRequest.State.RELEASED ? releasedStall(row, now) : pendingStall(row, now);
      if (trigger != null) {
        triggers.add(trigger);
      }
    }
    return triggers;
  }

  @Override
  public String render(String projectSlug, List<DeskTriggerEventRow> events) {
    return "Release requests that stopped moving:\n"
        + events.stream().map(DeskTriggerEventRow::line).collect(Collectors.joining("\n"));
  }

  /** The synthetic event id of one stall. */
  static UUID stallKey(String requestId, String state, String sha) {
    return UUID.nameUUIDFromBytes(
        (ID + "|" + requestId + "|" + state + "|" + sha).getBytes(StandardCharsets.UTF_8));
  }

  private DeskTrigger releasedStall(ReleaseRequest row, Instant now) {
    ReleasedTagPendingMerge tag =
        ReleasedTagPendingMerge.<ReleasedTagPendingMerge>find(
                "releaseRequestId = ?1 order by releasedAt desc", row.id)
            .firstResult();
    Instant since = tag != null ? tag.releasedAt : row.updatedAt;
    if (!stalled(since, released, now)) {
      return null;
    }
    String open;
    if (tag == null) {
      open = "no released tag recorded";
    } else if (tag.publishState == ReleasedTagPendingMerge.PublishState.PENDING) {
      open = "publish run PENDING";
    } else if (tag.publishState == ReleasedTagPendingMerge.PublishState.FAILED) {
      open = "publish run FAILED";
    } else if (tag.deploymentActiveAt == null) {
      open = "DEPLOYMENT gate PENDING";
    } else {
      open =
          "deployed, not merged into main"
              + (tag.mergeDetail == null || tag.mergeDetail.isBlank()
                  ? ""
                  : ": " + RequestFailedSource.cut(tag.mergeDetail.strip()));
    }
    String label =
        "RELEASED"
            + (row.version == null ? "" : " " + row.version)
            + " for "
            + human(Duration.between(since, now))
            + ", not FINALIZED; "
            + open;
    return trigger(row, "RELEASED", label, now);
  }

  private DeskTrigger pendingStall(ReleaseRequest row, Instant now) {
    if (row.queuedBehind != null || !stalled(row.armedAt, pending, now)) {
      return null;
    }
    if (waitsOnApproval(row)) {
      return null;
    }
    String label =
        "PENDING on fold "
            + sha8(row.mergedSha)
            + " for "
            + human(Duration.between(row.armedAt, now))
            + (row.detail == null || row.detail.isBlank() ? "" : "; " + RequestFailedSource.cut(row.detail.strip()));
    return trigger(row, "PENDING", label, now);
  }

  private DeskTrigger trigger(ReleaseRequest row, String state, String label, Instant now) {
    UUID key = stallKey(row.id, state, row.mergedSha);
    if (DeskTriggerEvent.count("source = ?1 and eventId = ?2", ID, key) > 0) {
      return null;
    }
    return new DeskTrigger(
        row.projectId,
        row.qualifiedId,
        row.id,
        "fold " + sha8(row.mergedSha),
        label,
        key,
        now,
        null,
        null);
  }

  private boolean stalled(Instant since, Duration threshold, Instant now) {
    if (since == null) {
      return false;
    }
    return since.plus(threshold).isBefore(now) && since.plus(maxAge).isAfter(now);
  }

  /** An approval requirement announced OPEN at this fold and not settled since. */
  private static boolean waitsOnApproval(ReleaseRequest row) {
    if (row.mergedSha == null) {
      return false;
    }
    List<ReleaseRequestApprovalNotice> notices =
        ReleaseRequestApprovalNotice.list(
            "requestId = ?1 and mergedSha = ?2", row.id, row.mergedSha);
    Set<String> settled = new HashSet<>();
    for (ReleaseRequestApprovalNotice notice : notices) {
      if (!ReleaseRequestApprovalNotice.State.OPEN.name().equals(notice.state)) {
        settled.add(notice.requirementId);
      }
    }
    return notices.stream()
        .anyMatch(
            notice ->
                ReleaseRequestApprovalNotice.State.OPEN.name().equals(notice.state)
                    && !settled.contains(notice.requirementId));
  }

  /** {@code 3h 10m}, or {@code 45m}. */
  static String human(Duration duration) {
    long minutes = Math.max(0, duration.toMinutes());
    long hours = minutes / 60;
    return hours == 0 ? minutes + "m" : hours + "h " + (minutes % 60) + "m";
  }

  private static String sha8(String sha) {
    if (sha == null) {
      return "unknown";
    }
    return sha.length() <= 8 ? sha : sha.substring(0, 8);
  }
}
