package eu.wohlben.qits.projects.desktriggers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.entities.control.WorkBranches;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditEntry;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.AuditRepository;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.api.RunningItems;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <b>Which of a project's slots finished work holds, and what it waits on</b> (qits-1778). A
 * VERIFIED item keeps its agent ACTIVE until its branches release and the agent goes OBSOLETE —
 * after the wrapper's release — so it keeps its slot in {@link RunningItems} for the whole wrapper
 * cycle. That is right (qits-1569 decides what holds a slot, and this does not change it), but the
 * desk could not see it: on 2026-10-31 it counted 5 of 5, could not tell that 3 of them were
 * VERIFIED work in one PENDING wrapper request, and dispatched nothing for an hour. This is the one
 * reading of that fact, shared by the Slots section every delivery trigger now ends with ({@link
 * DeskTriggerSweep}, {@link #section}) and by the line that says an item has no request at all
 * ({@link ShipMissingSource}).
 *
 * <p><b>Running</b> is {@link RunningItems#of}, the reading the dispatch check and {@link
 * CapacityOpenedSource} make, in a transaction of its own: work items and their audit live in the
 * epics datasource, never in a caller's projects one (one non-XA resource per transaction).
 * {@link RunningItems} answers an empty list when the agent port is unsatisfied, which would read
 * as {@code running 0 of N}; so an unsatisfied port is {@link Optional#empty()} here, and the
 * caller leaves the reading out rather than claim a count nobody made. A port that throws throws
 * through: each caller decides what a failure costs it.
 *
 * <p><b>VERIFIED since</b> is the {@code changed_at} of the audit row where the item last moved
 * into VERIFIED: the oldest of the unbroken run of newest rows whose snapshot (the row as {@code
 * AuditService} serialized it) says {@code "status": "VERIFIED"}. A later write that keeps the
 * status (an assignee, a block) is in that run and does not move it. With no such row — the audit
 * missing, or a snapshot that will not parse — it is the row's {@code updated_at}.
 *
 * <p><b>Its open requests</b> are this service's own rows, no call out: every request of the
 * project in {@link #OPEN} with a source in the item's scope — its wrapper branch ({@code
 * ticket/<qualified id>} or {@code epic/<qualified id>}, {@link WorkBranches#of}, the rule {@code
 * api/EntityWorkspaces} names it by) or a {@code <that branch>-<name>} submodule branch ({@link
 * WorkBranches#isAgentBranch}). They are ordered by qualified id with the wrapper's last, since the
 * wrapper's release is the one the slot waits for.
 */
@ApplicationScoped
public class VerifiedSlotHolders {

  /**
   * The states in which a request still carries a branch toward a release: PENDING, READY,
   * RELEASED, REJECTED, CONFLICTED. Not {@code ReleaseRequestRepository.OPEN}: FAILED is left out
   * with FINALIZED, WITHDRAWN and OBSOLETE, because a request whose release execution failed is
   * not shipping the branch now, and an item held only by one is the case {@link
   * ShipMissingSource} exists to say.
   */
  static final List<ReleaseRequest.State> OPEN =
      List.of(
          ReleaseRequest.State.PENDING,
          ReleaseRequest.State.READY,
          ReleaseRequest.State.RELEASED,
          ReleaseRequest.State.REJECTED,
          ReleaseRequest.State.CONFLICTED);

  static final String VERIFIED = EntityStatus.VERIFIED.name();

  /** One open request in an item's scope, as a line names it. */
  public record OpenRequest(String qualifiedId, ReleaseRequest.State state, boolean wrapper) {

    /** {@code <qualified id> <STATE>}. */
    String text() {
      return qualifiedId + " " + state;
    }
  }

  /**
   * One running VERIFIED item.
   *
   * @param itemId the work item's id
   * @param qualifiedId {@code <slug>-<number>}, or the item id where the project has no slug or the
   *     row no number
   * @param branch its wrapper branch; null where it has no qualified id to name one by
   * @param verifiedSince when it last moved into VERIFIED (see the class javadoc)
   * @param requests its open requests, wrapper last; empty when none
   */
  public record Holder(
      String itemId,
      String qualifiedId,
      String branch,
      Instant verifiedSince,
      List<OpenRequest> requests) {}

  /**
   * The project's slots as they stand: how many items run, and the VERIFIED ones among them, oldest
   * VERIFIED first.
   */
  public record Reading(int running, List<Holder> verified) {}

  @Inject RunningItems running;

  @Inject Instance<WorkspaceAgentDispatch> dispatch;

  @Inject AuditRepository audit;

  @Inject ObjectMapper json;

  /** A running item read out of the epics transaction. */
  private record Item(WorkEntity row, boolean verified, Instant verifiedSince) {}

  /**
   * The reading of {@code project}'s slots, or empty when the agent port is unsatisfied. Opens its
   * own transactions, so it may be called inside a projects transaction or outside any. Throws
   * whatever the agent port throws.
   */
  public Optional<Reading> read(Project project) {
    if (dispatch.isUnsatisfied()) {
      return Optional.empty();
    }
    String projectId = project.id;
    String slug = project.slug;
    String wrapperName = slug == null ? null : ProjectService.wrapperName(project);
    List<Item> items =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<Item> read = new ArrayList<>();
                  for (WorkEntity row : running.of(projectId, null)) {
                    boolean verified = VERIFIED.equals(row.status);
                    read.add(new Item(row, verified, verified ? verifiedSince(row) : null));
                  }
                  return read;
                });
    List<Holder> holders = new ArrayList<>();
    Map<String, String> branches = new HashMap<>();
    for (Item item : items) {
      if (!item.verified()) {
        continue;
      }
      WorkEntity row = item.row();
      String qualifiedId =
          slug == null || row.number < 1 ? null : QualifiedEntityIds.render(slug, row.number);
      String branch = qualifiedId == null ? null : WorkBranches.of(row, qualifiedId);
      if (branch != null) {
        branches.put(row.id, branch);
      }
      holders.add(
          new Holder(
              row.id,
              qualifiedId == null ? row.id : qualifiedId,
              branch,
              item.verifiedSince(),
              List.of()));
    }
    if (!branches.isEmpty()) {
      Map<String, List<OpenRequest>> open = openRequests(projectId, wrapperName, branches);
      holders.replaceAll(
          holder ->
              new Holder(
                  holder.itemId(),
                  holder.qualifiedId(),
                  holder.branch(),
                  holder.verifiedSince(),
                  open.getOrDefault(holder.itemId(), List.of())));
    }
    holders.sort(
        Comparator.comparing(Holder::verifiedSince).thenComparing(Holder::qualifiedId));
    return Optional.of(new Reading(items.size(), List.copyOf(holders)));
  }

  /**
   * The Slots section of a delivery trigger, exactly:
   *
   * <pre>
   * Slots: running 5 of 5, 2 VERIFIED waiting to ship
   * - qits-1774 (VERIFIED 1h 5m; qits-qits-rr-264 PENDING)
   * - qits-1769 (VERIFIED 43m; no open release request)
   * </pre>
   *
   * With nothing VERIFIED the heading line is the whole section, without its VERIFIED clause. The
   * duration is {@link RequestStalledSource#human}'s.
   */
  static String section(Reading reading, int maxRunning, Instant now) {
    StringBuilder section =
        new StringBuilder("Slots: running ")
            .append(reading.running())
            .append(" of ")
            .append(maxRunning);
    if (reading.verified().isEmpty()) {
      return section.toString();
    }
    section.append(", ").append(reading.verified().size()).append(" VERIFIED waiting to ship");
    for (Holder holder : reading.verified()) {
      section
          .append("\n- ")
          .append(holder.qualifiedId())
          .append(" (VERIFIED ")
          .append(verifiedFor(holder, now))
          .append("; ")
          .append(
              holder.requests().isEmpty()
                  ? "no open release request"
                  : holder.requests().stream()
                      .map(OpenRequest::text)
                      .collect(Collectors.joining(", ")))
          .append(")");
    }
    return section.toString();
  }

  /** How long {@code holder} has been VERIFIED at {@code now}: {@code 1h 5m}, {@code 43m}. */
  static String verifiedFor(Holder holder, Instant now) {
    return RequestStalledSource.human(Duration.between(holder.verifiedSince(), now));
  }

  /** Inside the caller's epics transaction. */
  private Instant verifiedSince(WorkEntity row) {
    Instant since = null;
    if (row.archetype != null) {
      for (AuditEntry entry : audit.listForEntity(AuditEntityType.of(row.archetype), row.id)) {
        if (!VERIFIED.equals(statusOf(entry.snapshot))) {
          break;
        }
        since = entry.changedAt;
      }
    }
    return since != null ? since : row.updatedAt;
  }

  private String statusOf(String snapshot) {
    if (snapshot == null || snapshot.isBlank()) {
      return null;
    }
    try {
      return json.readTree(snapshot).path("status").asText(null);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  /**
   * Each item's open requests by item id, from {@code branches} (item id → wrapper branch). A
   * {@code like} on the name can only over-match ({@code _} is a wildcard there), and {@link
   * WorkBranches#isAgentBranch} then keeps exactly the scope.
   */
  private static Map<String, List<OpenRequest>> openRequests(
      String projectId, String wrapperName, Map<String, String> branches) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Map<String, Set<String>> requestIdsByItem = new HashMap<>();
              Set<String> requestIds = new LinkedHashSet<>();
              for (Map.Entry<String, String> entry : branches.entrySet()) {
                String branch = entry.getValue();
                for (ReleaseRequestSource source :
                    ReleaseRequestSource.<ReleaseRequestSource>list(
                        "name = ?1 or name like ?2",
                        branch,
                        branch + WorkBranches.AGENT_SEPARATOR + "%")) {
                  if (WorkBranches.isAgentBranch(branch, source.name)) {
                    requestIdsByItem
                        .computeIfAbsent(entry.getKey(), id -> new LinkedHashSet<>())
                        .add(source.requestId);
                    requestIds.add(source.requestId);
                  }
                }
              }
              if (requestIds.isEmpty()) {
                return Map.of();
              }
              Map<String, OpenRequest> open = new HashMap<>();
              for (ReleaseRequest request :
                  ReleaseRequest.<ReleaseRequest>list(
                      "id in ?1 and projectId = ?2 and state in ?3",
                      List.copyOf(requestIds),
                      projectId,
                      OPEN)) {
                open.put(
                    request.id,
                    new OpenRequest(
                        request.qualifiedId == null ? request.id : request.qualifiedId,
                        request.state,
                        wrapperName != null && wrapperName.equals(request.repoName)));
              }
              Map<String, List<OpenRequest>> byItem = new HashMap<>();
              requestIdsByItem.forEach(
                  (itemId, ids) ->
                      byItem.put(
                          itemId,
                          ids.stream()
                              .map(open::get)
                              .filter(request -> request != null)
                              .sorted(
                                  Comparator.comparing(OpenRequest::wrapper)
                                      .thenComparing(OpenRequest::qualifiedId))
                              .toList()));
              return byItem;
            });
  }
}
