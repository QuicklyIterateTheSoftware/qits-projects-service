package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.ApprovalRequirementChanged;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <b>A release request waits on an approval the desk may give</b> (epic qits-1604).
 *
 * <p>Reads this service's own {@link ApprovalRequirementChanged}. It qualifies when the requirement
 * is {@code OPEN} and its {@code admits} names {@code desk}: that list already reflects the
 * project's {@code front_desk.delivery.approves} ({@code ApprovalPolicy.admitDesk}), so there is no
 * second policy to consult here. One line per (request, requirement) — two open requirements of one
 * fold are two lines, because the label names the requirement.
 *
 * <p>The label is the request's summary, read from its row now (left out when there is none), then
 * what it waits on. The cause is {@code fold <sha8>}: a fold has no actor, and the phrase can never
 * equal a desk's subject.
 */
@ApplicationScoped
public class ApprovalNeededSource implements DeskTriggerSource {

  /** Storage — see {@link DeskTriggerSource#id()}. */
  static final String ID = "approval-needed";

  private static final String OPEN = "OPEN";

  /** {@code ApprovalRole.DESK_WORD}, as {@code admits} renders the desk. */
  private static final String DESK = "desk";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public int order() {
    return 20;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(ApprovalRequirementChanged.class.getSimpleName());
  }

  @Override
  public List<DeskTrigger> qualify(EventFrame frame) {
    ApprovalRequirementChanged changed =
        DeskTriggerFrames.decode(frame, ApprovalRequirementChanged.class);
    if (!OPEN.equals(changed.state())
        || changed.projectId() == null
        || !changed.admits().contains(DESK)
        || changed.releaseRequestQualifiedId() == null) {
      return List.of();
    }
    Instant occurredAt = changed.changedAt() == null ? frame.occurredAt() : changed.changedAt();
    return List.of(
        new DeskTrigger(
            changed.projectId(),
            changed.releaseRequestQualifiedId(),
            changed.releaseRequestId(),
            "fold " + sha8(changed.mergedSha()),
            label(changed),
            DeskTriggerFrames.eventIdOf(frame),
            occurredAt));
  }

  @Override
  public String render(String projectSlug, List<DeskTriggerEventRow> events) {
    return "Needs your approval:\n"
        + events.stream()
            .map(event -> event.line() + (event.cause() == null ? "" : ", " + event.cause()))
            .collect(Collectors.joining("\n"));
  }

  /** {@code <summary>; waits on <requirement>: <reason>}, each part left out when absent. */
  private static String label(ApprovalRequirementChanged changed) {
    String waits =
        "waits on "
            + changed.requirementId()
            + (isBlank(changed.reason()) ? "" : ": " + changed.reason());
    String summary = summaryOf(changed.releaseRequestId());
    return isBlank(summary) ? waits : summary + "; " + waits;
  }

  private static String summaryOf(String releaseRequestId) {
    if (releaseRequestId == null) {
      return null;
    }
    ReleaseRequest request = ReleaseRequest.findById(releaseRequestId);
    return request == null ? null : request.summary;
  }

  private static String sha8(String sha) {
    if (sha == null) {
      return "unknown";
    }
    return sha.length() <= 8 ? sha : sha.substring(0, 8);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
