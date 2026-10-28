package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.ReleaseRequestStateChanged;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleaseRequestFailureNotice;
import eu.wohlben.qits.projects.entity.ReleaseRequestParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

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
 *   <li><b>Still owed:</b> not decided yet. {@link #scan} decides on a later sweep, once every
 *       notice of the fold is settled.
 *   <li><b>All settled:</b> decided by liveness, now. A told notice ({@code DELIVERED}, {@code
 *       LAUNCHED}, {@code RESUMED}) counts only while its agent is still ACTIVE ({@link
 *       WorkspaceAgentDispatch#agentsFor} on the told notices' work items, the reading {@code
 *       RunningItems} makes, matched on the notice's agent id): that agent owns it, and nothing is
 *       said here. With none ACTIVE — its agent has since gone OBSOLETE or REMOVED, none was told
 *       ({@code NOT_ACTIVE}, {@code NO_AGENT}, {@code GAVE_UP}), or the read failed or has no
 *       workspaces context to ask — the desk is told.
 * </ul>
 *
 * <p>Liveness rather than the notices' outcomes, because a fold can be rejected twice without moving
 * sha (qits-1694): {@code reconsider} and {@code reopenAutomationRejection} reopen it at the same
 * {@code mergedSha}, the next red gate rejects it again, and qits-1672 owes no new notice for a fold
 * it already noted. The second rejection is therefore read against the first's settled notices,
 * and an agent told of the first that has gone since reaches nobody unless the desk is told.
 *
 * <p>The notices are written in the transaction that rejects the fold, and the event is published
 * after it commits, so the event never arrives before them.
 *
 * <p><b>Once per rejection.</b> A line decided from notices carries a synthetic event id, a
 * name-based UUID of (request, fold) ({@link #foldKey}), while that key is free: the fold's first
 * rejection, so the event path and the sweep can never both admit it. Once a window holds it, a
 * line from the event path carries the bus event's id — a re-rejection at the same fold, which is
 * its own line by the no-notice rule. No timestamps are compared. The source answers nothing for an
 * event id some window already holds, so a replay of a frame that admitted under its own id adds
 * nothing; the one frame the store cannot tell from a re-rejection is the fold's first, redelivered
 * after its line was admitted under the fold key (by the sweep, or by an admit that committed under
 * a claim that then rolled back), and that costs one redundant line rather than a silent failure.
 * {@link #scan} still reports only folds none of whose notices told an agent, under {@link #foldKey}.
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

  private static final Logger LOG = Logger.getLogger(RequestFailedSource.class);

  @Inject Instance<WorkspaceAgentDispatch> dispatch;

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
      if (notices.stream().anyMatch(n -> n.outcome == null)) {
        return List.of();
      }
      List<ReleaseRequestFailureNotice> told =
          notices.stream().filter(n -> TOLD.contains(n.outcome)).toList();
      if (told.isEmpty()) {
        why = notToldPhrase(notices);
      } else {
        Liveness liveness = livenessOf(told);
        if (liveness == Liveness.ACTIVE) {
          return List.of();
        }
        why = liveness.why;
      }
      UUID fold = foldKey(changed.releaseRequestId(), sha);
      if (!admitted(fold)) {
        eventId = fold;
      }
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

  /** How the agents told of a fold stand now. */
  private enum Liveness {
    ACTIVE(null),
    NOT_ACTIVE("its agent is not active"),
    GONE("its agent is gone"),
    UNKNOWN("its agent could not be checked");

    final String why;

    Liveness(String why) {
      this.why = why;
    }
  }

  /**
   * Whether any told notice's agent is still ACTIVE: an agent on the notice's work item, and the
   * notice's own agent when it names one (a fresh agent on the item was not told). A read that
   * fails, or no workspaces context to ask, counts as not told.
   */
  private Liveness livenessOf(List<ReleaseRequestFailureNotice> told) {
    List<String> workIds =
        told.stream().map(n -> n.workId).filter(id -> !isBlank(id)).distinct().toList();
    if (workIds.isEmpty()) {
      return Liveness.GONE;
    }
    if (dispatch.isUnsatisfied()) {
      return Liveness.UNKNOWN;
    }
    List<WorkspaceAgentDispatch.Agent> agents;
    try {
      agents = Objects.requireNonNullElse(dispatch.get().agentsFor(workIds), List.of());
    } catch (RuntimeException e) {
      LOG.debugf("Could not read the told agents of %s: %s", workIds, e.toString());
      return Liveness.UNKNOWN;
    }
    boolean listed = false;
    for (ReleaseRequestFailureNotice notice : told) {
      for (WorkspaceAgentDispatch.Agent agent : agents) {
        boolean same =
            notice.workId != null
                && notice.workId.equals(agent.workId())
                && (isBlank(notice.agentId) || notice.agentId.equals(agent.agentId()));
        if (same && agent.active()) {
          return Liveness.ACTIVE;
        }
        listed |= same;
      }
    }
    return listed ? Liveness.NOT_ACTIVE : Liveness.GONE;
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

  /**
   * Why no agent was told, when qits-1672 owed none. An agent "filed" the request where one of its
   * participants is a workspace agent's token subject (qits-1673) — an authenticated principal, so
   * no agent can claim another's request, and one that joined counts as much as one that opened.
   */
  private static String noAgentPhrase(ReleaseRequest row, String state) {
    boolean agentFiled =
        row != null
            && ReleaseRequestParticipant.<ReleaseRequestParticipant>list("requestId", row.id).stream()
                .anyMatch(
                    participant ->
                        participant.principal != null
                            && participant.principal.startsWith("tok-workspace-agent-"));
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
