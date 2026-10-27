package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.ReleaseRequestStateChanged;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleaseRequestFailureNotice;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <b>A release request failed, and no active agent was told</b> (qits-1689).
 *
 * <p>Reads this service's own {@link ReleaseRequestStateChanged} at {@code REJECTED}, {@code
 * CONFLICTED} or {@code FAILED}. The desk is the overseer of what nobody else owns, so it is told
 * only when no active agent was: qits-1672 tells the agent that filed or joined a request when a
 * build fails it ({@code release_request_failure_notice}), and that agent can raise an {@code @desk}
 * comment itself. So the source reads those notices for the request's fold:
 *
 * <ul>
 *   <li><b>None:</b> no agent will be told (a maintenance bump, a request made off-platform, a
 *       person's, or a conflict, which qits-1672 does not report). The desk is told now.
 *   <li><b>One told</b> ({@code DELIVERED}, {@code LAUNCHED}, {@code RESUMED}): the agent owns it.
 *       Nothing here.
 *   <li><b>Still owed:</b> not decided yet. {@link #scan} decides on a later sweep, once every
 *       notice of the fold is settled.
 *   <li><b>All settled, none told</b> ({@code NOT_ACTIVE}, {@code NO_AGENT}, {@code GAVE_UP}): the
 *       desk is told.
 * </ul>
 *
 * <p>The notices are written in the transaction that rejects the fold, and the event is published
 * after it commits, so the event never arrives before them.
 *
 * <p><b>Once.</b> A line decided from notices carries a synthetic event id, a name-based UUID of
 * (request, fold) ({@link #foldKey}), so the event path and the sweep can never both admit it. A
 * line with no notices carries the bus event's id. Either way the source answers nothing for an
 * event id some window already holds, so a replay adds nothing.
 *
 * <p>The label is {@code <state>: <detail>; <why no agent>}. The principal is the person or desk
 * whose decline rejected the fold, so the desk's own decline is dropped; a build, an automation or a
 * conflict has none. The cause is that principal, else {@code fold <sha8>}.
 */
@ApplicationScoped
public class RequestFailedSource implements DeskTriggerSource {

  /** Storage — see {@link DeskTriggerSource#id()}. */
  static final String ID = "request-failed";

  /** The states this source reports. */
  static final Set<String> FAILED_STATES = Set.of("REJECTED", "CONFLICTED", "FAILED");

  /** qits-1672's outcomes that mean an agent was told. */
  static final Set<String> TOLD = Set.of("DELIVERED", "LAUNCHED", "RESUMED");

  /** How old a notice the sweep still looks at: qits-1672 gives up on older ones anyway. */
  static final Duration NOTICE_HORIZON = Duration.ofDays(2);

  /** A detail is cut here, so a long log line stays one line. */
  static final int DETAIL_MAX = 300;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public int order() {
    return 40;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(ReleaseRequestStateChanged.class.getSimpleName());
  }

  @Override
  public List<DeskTrigger> qualify(EventFrame frame) {
    ReleaseRequestStateChanged changed =
        DeskTriggerFrames.decode(frame, ReleaseRequestStateChanged.class);
    if (!FAILED_STATES.contains(changed.state())
        || changed.state().equals(changed.previousState())
        || changed.projectId() == null
        || changed.releaseRequestQualifiedId() == null
        || changed.releaseRequestId() == null) {
      return List.of();
    }
    UUID eventId = DeskTriggerFrames.eventIdOf(frame);
    if (admitted(eventId)) {
      return List.of();
    }
    ReleaseRequest row = ReleaseRequest.findById(changed.releaseRequestId());
    String sha = changed.mergedSha() != null ? changed.mergedSha() : row == null ? null : row.mergedSha;
    List<ReleaseRequestFailureNotice> notices = noticesOf(changed.releaseRequestId(), sha);
    Instant occurredAt = changed.changedAt() == null ? frame.occurredAt() : changed.changedAt();
    String detail = changed.detail();
    if (isBlank(detail) && row != null) {
      detail = "CONFLICTED".equals(changed.state()) ? row.conflictDetail : row.detail;
    }
    String why;
    if (notices.isEmpty()) {
      why = noAgentPhrase(row, changed.state());
    } else {
      Decision decision = decide(notices);
      if (decision != Decision.NOBODY_TOLD) {
        return List.of();
      }
      eventId = foldKey(changed.releaseRequestId(), sha);
      if (admitted(eventId)) {
        return List.of();
      }
      why = notToldPhrase(notices);
    }
    String principal = declinedBy(row, changed.state(), sha);
    return List.of(
        new DeskTrigger(
            changed.projectId(),
            changed.releaseRequestQualifiedId(),
            changed.releaseRequestId(),
            principal != null ? principal : "fold " + sha8(sha),
            label(changed.state(), detail, why),
            eventId,
            occurredAt,
            null,
            principal));
  }

  /**
   * The folds whose notices have all settled since the event, with none told: the case {@link
   * #qualify} left open. A request that has since left its failed state, or moved to another fold,
   * is not reported.
   */
  @Override
  public List<DeskTrigger> scan(Instant now) {
    Map<List<String>, List<ReleaseRequestFailureNotice>> byFold = new LinkedHashMap<>();
    for (ReleaseRequestFailureNotice notice :
        ReleaseRequestFailureNotice.<ReleaseRequestFailureNotice>list(
            "owedAt > ?1 order by owedAt", now.minus(NOTICE_HORIZON))) {
      byFold
          .computeIfAbsent(List.of(notice.requestId, notice.mergedSha), k -> new ArrayList<>())
          .add(notice);
    }
    List<DeskTrigger> triggers = new ArrayList<>();
    for (Map.Entry<List<String>, List<ReleaseRequestFailureNotice>> fold : byFold.entrySet()) {
      if (decide(fold.getValue()) != Decision.NOBODY_TOLD) {
        continue;
      }
      String requestId = fold.getKey().get(0);
      String sha = fold.getKey().get(1);
      UUID key = foldKey(requestId, sha);
      if (admitted(key)) {
        continue;
      }
      ReleaseRequest row = ReleaseRequest.findById(requestId);
      if (row == null
          || row.projectId == null
          || row.state == null
          || !FAILED_STATES.contains(row.state.name())
          || !sha.equals(row.mergedSha)) {
        continue;
      }
      String principal = declinedBy(row, row.state.name(), sha);
      triggers.add(
          new DeskTrigger(
              row.projectId,
              row.qualifiedId,
              row.id,
              principal != null ? principal : "fold " + sha8(sha),
              label(
                  row.state.name(),
                  row.state == ReleaseRequest.State.CONFLICTED ? row.conflictDetail : row.detail,
                  notToldPhrase(fold.getValue())),
              key,
              row.updatedAt == null ? now : row.updatedAt,
              null,
              principal));
    }
    return triggers;
  }

  @Override
  public String render(String projectSlug, List<DeskTriggerEventRow> events) {
    return "Release requests that failed, with no active agent told:\n"
        + events.stream().map(DeskTriggerEventRow::line).collect(Collectors.joining("\n"));
  }

  /** The synthetic event id of a line decided from a fold's notices. */
  static UUID foldKey(String requestId, String sha) {
    return UUID.nameUUIDFromBytes(
        (ID + "|" + requestId + "|" + sha).getBytes(StandardCharsets.UTF_8));
  }

  private enum Decision {
    AGENT_TOLD,
    OWED,
    NOBODY_TOLD
  }

  private static Decision decide(List<ReleaseRequestFailureNotice> notices) {
    if (notices.stream().anyMatch(n -> n.outcome != null && TOLD.contains(n.outcome))) {
      return Decision.AGENT_TOLD;
    }
    if (notices.stream().anyMatch(n -> n.outcome == null)) {
      return Decision.OWED;
    }
    return Decision.NOBODY_TOLD;
  }

  private static boolean admitted(UUID eventId) {
    return DeskTriggerEvent.count("source = ?1 and eventId = ?2", ID, eventId) > 0;
  }

  private static List<ReleaseRequestFailureNotice> noticesOf(String requestId, String sha) {
    if (sha == null) {
      return List.of();
    }
    return ReleaseRequestFailureNotice.list("requestId = ?1 and mergedSha = ?2", requestId, sha);
  }

  /** Why no agent was told, when qits-1672 owed none. */
  private static String noAgentPhrase(ReleaseRequest row, String state) {
    boolean agentFiled =
        row != null
            && row.requester != null
            && row.requester.startsWith("tok-workspace-agent-");
    if (agentFiled) {
      return "CONFLICTED".equals(state)
          ? "its agent is not told of a conflict"
          : "its agent is not told of this";
    }
    return "no agent owns it";
  }

  /** Why the agents qits-1672 owed were not told. */
  private static String notToldPhrase(List<ReleaseRequestFailureNotice> notices) {
    Set<String> outcomes =
        notices.stream().map(n -> n.outcome).collect(Collectors.toCollection(java.util.TreeSet::new));
    if (outcomes.contains("NOT_ACTIVE")) {
      return "its agent is not active";
    }
    if (outcomes.contains("NO_AGENT")) {
      return "its agent is gone";
    }
    return "its agent could not be told";
  }

  /** The actor of the decline that rejected this fold; null when no person or desk rejected it. */
  private static String declinedBy(ReleaseRequest row, String state, String sha) {
    if (!"REJECTED".equals(state)
        || row == null
        || sha == null
        || row.rejectingRunId != null
        || row.rejectingGate != null) {
      return null;
    }
    ReleaseRequestApproval decline =
        ReleaseRequestApproval.<ReleaseRequestApproval>find(
                "requestId = ?1 and mergedSha = ?2 and decision = ?3 order by decidedAt desc",
                row.id,
                sha,
                ReleaseRequestApproval.Decision.DECLINED)
            .firstResult();
    return decline == null ? null : decline.actor;
  }

  private static String label(String state, String detail, String why) {
    String text = isBlank(detail) ? state : state + ": " + cut(detail.strip());
    return text + "; " + why;
  }

  /** One line, at most {@link #DETAIL_MAX} characters. */
  static String cut(String detail) {
    String oneLine = detail.replaceAll("\\s+", " ");
    return oneLine.length() <= DETAIL_MAX ? oneLine : oneLine.substring(0, DETAIL_MAX - 1) + "…";
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
