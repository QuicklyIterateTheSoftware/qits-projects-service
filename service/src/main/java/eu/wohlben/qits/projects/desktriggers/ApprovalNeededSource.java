package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.ApprovalRequirementChanged;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApprovalNotice;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
 * <p>The label is what the request is about — its items' text, derived from its sources now
 * ({@link ReleaseRequests#textOf}; left out for a request that is gone) — then
 * what it waits on. The cause is {@code fold <sha8>}: a fold has no actor, and the phrase can never
 * equal a desk's subject.
 *
 * <p><b>Still holds</b> (qits-2501, {@link #holds}) while the request is PENDING — the one state a
 * request held by the approval gate is in — its current {@code merged_sha} is still the line's
 * fold, and the requirement the label names has not been settled at that fold (no SATISFIED,
 * DECLINED or VOID in {@code release_request_approval_notice}, the ledger {@code
 * RequestStalledSource} reads for the same question). It reads only this service's rows. A newer
 * line of the same request in a window supersedes the older ones, and supersedes the request's
 * request-failed lines too ({@link RequestFailedSource#supersededBy}).
 */
@ApplicationScoped
public class ApprovalNeededSource implements DeskTriggerSource {

  /** Storage — see {@link DeskTriggerSource#id()}. */
  static final String ID = "approval-needed";

  private static final String OPEN = "OPEN";

  /** {@code ApprovalRole.DESK_WORD}, as {@code admits} renders the desk. */
  private static final String DESK = "desk";

  /** Derives what a request is about; null on an instance built outside CDI, which leaves it out. */
  @Inject ReleaseRequests requests;

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

  /** A newer approval line of the same request names it at its current fold. */
  @Override
  public boolean supersedesOlderLinesOfItsItem() {
    return true;
  }

  /**
   * The request still waits at the line's fold on the requirement it names. A request this service
   * no longer has holds: there is nothing to read the answer from.
   */
  @Override
  public boolean holds(DeskTriggerEventRow line, Instant now) {
    if (line.itemId() == null) {
      return true;
    }
    return QuarkusTransaction.requiringNew().call(() -> holdsNow(line));
  }

  private static boolean holdsNow(DeskTriggerEventRow line) {
    ReleaseRequest row = ReleaseRequest.findById(line.itemId());
    if (row == null) {
      return true;
    }
    if (row.state != ReleaseRequest.State.PENDING) {
      return false;
    }
    String sha8 = foldOf(line.cause());
    if (sha8 != null && (row.mergedSha == null || !row.mergedSha.startsWith(sha8))) {
      return false;
    }
    if (row.mergedSha == null) {
      return true;
    }
    List<ReleaseRequestApprovalNotice> notices =
        ReleaseRequestApprovalNotice.list(
            "requestId = ?1 and mergedSha = ?2", row.id, row.mergedSha);
    String requirement = requirementOf(line.label());
    if (requirement != null) {
      return notices.stream()
          .noneMatch(
              notice ->
                  requirement.equals(notice.requirementId)
                      && !ReleaseRequestApprovalNotice.State.OPEN.name().equals(notice.state));
    }
    // No requirement to read off the label: any requirement still open and unsettled at the fold.
    if (notices.isEmpty()) {
      return true;
    }
    Set<String> settled =
        notices.stream()
            .filter(n -> !ReleaseRequestApprovalNotice.State.OPEN.name().equals(n.state))
            .map(n -> n.requirementId)
            .collect(Collectors.toSet());
    return notices.stream()
        .anyMatch(
            n ->
                ReleaseRequestApprovalNotice.State.OPEN.name().equals(n.state)
                    && !settled.contains(n.requirementId));
  }

  /** The sha8 of {@code fold <sha8>}; null for any other cause, or an unknown fold. */
  static String foldOf(String cause) {
    if (cause == null || !cause.startsWith("fold ")) {
      return null;
    }
    String sha8 = cause.substring("fold ".length()).strip();
    return sha8.isEmpty() || "unknown".equals(sha8) ? null : sha8;
  }

  /**
   * The requirement id of {@link #label}'s {@code waits on <requirement>[: <reason>]}, the last
   * such clause; null when the label carries none.
   */
  static String requirementOf(String label) {
    if (label == null) {
      return null;
    }
    int at = label.lastIndexOf("waits on ");
    if (at < 0 || (at > 0 && !label.startsWith("; ", at - 2))) {
      return null;
    }
    String rest = label.substring(at + "waits on ".length());
    int colon = rest.indexOf(": ");
    String requirement = (colon < 0 ? rest : rest.substring(0, colon)).strip();
    return requirement.isEmpty() ? null : requirement;
  }

  @Override
  public String render(String projectSlug, List<DeskTriggerEventRow> events) {
    return "Needs your approval:\n"
        + events.stream()
            .map(event -> event.line() + (event.cause() == null ? "" : ", " + event.cause()))
            .collect(Collectors.joining("\n"));
  }

  /** {@code <summary>; waits on <requirement>: <reason>}, each part left out when absent. */
  private String label(ApprovalRequirementChanged changed) {
    String waits =
        "waits on "
            + changed.requirementId()
            + (isBlank(changed.reason()) ? "" : ": " + changed.reason());
    String summary = summaryOf(changed.releaseRequestId());
    return isBlank(summary) ? waits : summary + "; " + waits;
  }

  /**
   * What the request is about: its items' labels joined (qits-1673), derived from the sources it
   * carries now, so the line names the work, not a typed sentence.
   */
  private String summaryOf(String releaseRequestId) {
    if (releaseRequestId == null || requests == null) {
      return null;
    }
    return requests.textOf(releaseRequestId);
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
