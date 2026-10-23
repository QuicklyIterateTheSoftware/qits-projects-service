package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.gate.GateSubject;
import eu.wohlben.qits.projects.entity.ReleasePipelineRun;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.RepositoryName;
import eu.wohlben.qits.projects.persistence.ReleaseRequestRepository;
import eu.wohlben.qits.projects.persistence.ReleasedTagPendingMergeRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * <b>The open requests upstream of a request, and which of them is building right now</b> (epic
 * qits-897, gate 3). The facts {@code UpstreamInFlightGate} reads and {@code
 * ReleaseRequests.evaluate} decides on.
 *
 * <p><b>Upstream of R</b>: an open request in another repository of R's project whose downstream
 * closure ({@link UpstreamClosures}) names R's repository. There is no upstream read anywhere and
 * none is needed: R's upstreams are the repositories whose downstream list names R.
 *
 * <p><b>Active</b>, from this service's own tables alone:
 *
 * <ul>
 *   <li>PENDING with a QA run ({@code release_pipeline_run}, phase {@code RELEASE_REQUEST}) QUEUED
 *       or RUNNING at its {@code mergedSha};
 *   <li>READY;
 *   <li>RELEASED with its {@code publishState} PENDING.
 * </ul>
 *
 * Everything else is not active — a PENDING request in its pre-run or waiting on approval included
 * (judgement call on the epic: decision 1 is about a build running now).
 *
 * <p><b>Only an active candidate's closure is asked for.</b> An inactive one holds nothing whatever
 * it is upstream of, so it is listed only where its closure is already on record. An active one whose
 * closure could not be read is listed as not known, which the gate answers UNKNOWN and never holds.
 *
 * <p><b>No mutual hold.</b> The closure is a DAG (qits-ci's {@code CiRunOrdering} relies on it); if
 * R is also upstream of U the pair is skipped with a WARN, once per pair per process.
 *
 * <p>Must be called inside a transaction: everything here is a read of this service's database,
 * plus — on a deciding path ({@code mayAsk=true}) only — qits-maintenance on a cache miss.
 */
@ApplicationScoped
public class UpstreamRequests {

  private static final Logger LOG = Logger.getLogger(UpstreamRequests.class);

  /** The states an upstream candidate can be in: the three that can be building. */
  static final List<ReleaseRequest.State> CANDIDATE_STATES =
      List.of(
          ReleaseRequest.State.PENDING,
          ReleaseRequest.State.READY,
          ReleaseRequest.State.RELEASED);

  /** qits-ci's words for a QA run that has not finished. */
  static final Set<String> BUILDING = Set.of("QUEUED", "RUNNING");

  @Inject ReleaseRequestRepository requests;

  @Inject ReleasePipelineRuns pipelineRuns;

  @Inject ReleasedTagPendingMergeRepository pendingTags;

  @Inject RepositoryNameRepository names;

  @Inject UpstreamClosures closures;

  @Inject ApprovalPolicy approvalPolicy;

  /** The pairs already warned about, so a cycle is one line and not one per sweep. */
  private final Set<String> warnedCycles = ConcurrentHashMap.newKeySet();

  /**
   * The upstream requests of one request — see the class.
   *
   * @param mayAsk whether a closure not on record may be asked for (a deciding caller); a display
   *     caller passes false
   */
  public List<GateSubject.Upstream> activeFor(ReleaseRequest row, boolean mayAsk) {
    if (row == null || row.projectId == null) {
      return List.of();
    }
    return forEach(List.of(row), mayAsk).getOrDefault(row.id, List.of());
  }

  /**
   * Gate 3's facts for a page of requests, batched: one read of the candidates, one of their QA
   * runs, one of their released tags and one of the projects' names, whatever the page's size. Only
   * a PENDING row with a fold is read for; every other row is left out of the map.
   */
  public Map<String, GateSubject.Upstreams> forPage(List<ReleaseRequest> rows) {
    List<ReleaseRequest> gated =
        rows.stream()
            .filter(row -> row.state == ReleaseRequest.State.PENDING && row.mergedSha != null)
            .toList();
    Map<String, Boolean> wrapper = new HashMap<>();
    gated.forEach(
        row -> wrapper.computeIfAbsent(row.repoId, approvalPolicy::isEstateWrapper));
    List<ReleaseRequest> asked =
        gated.stream().filter(row -> !wrapper.getOrDefault(row.repoId, false)).toList();
    Map<String, List<GateSubject.Upstream>> found = forEach(asked, false);
    Map<String, GateSubject.Upstreams> out = new HashMap<>();
    for (ReleaseRequest row : gated) {
      out.put(
          row.id,
          GateSubject.Upstreams.of(
              wrapper.getOrDefault(row.repoId, false), found.getOrDefault(row.id, List.of())));
    }
    return out;
  }

  /** Gate 3's facts for one request, as {@link #forPage} answers a page. */
  public GateSubject.Upstreams forOne(ReleaseRequest row) {
    return forPage(List.of(row)).getOrDefault(row.id, GateSubject.Upstreams.NOT_READ);
  }

  private Map<String, List<GateSubject.Upstream>> forEach(
      List<ReleaseRequest> rows, boolean mayAsk) {
    Set<String> projectIds =
        rows.stream()
            .map(row -> row.projectId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (projectIds.isEmpty()) {
      return Map.of();
    }
    List<ReleaseRequest> candidates = candidatesIn(projectIds);
    if (candidates.isEmpty()) {
      return Map.of();
    }
    Map<String, List<ReleasePipelineRun>> runs =
        pipelineRuns.runsForEach(
            candidates.stream()
                .filter(c -> c.state != ReleaseRequest.State.RELEASED)
                .map(c -> c.id)
                .collect(Collectors.toSet()));
    List<String> releasedIds =
        candidates.stream()
            .filter(c -> c.state == ReleaseRequest.State.RELEASED)
            .map(c -> c.id)
            .toList();
    Map<String, ReleasedTagPendingMerge> tags =
        pendingTags.listByRequests(releasedIds).stream()
            .collect(Collectors.toMap(tag -> tag.releaseRequestId, tag -> tag, (a, b) -> a));
    Map<String, Set<String>> aliases = aliasesIn(projectIds);
    Map<String, Activity> activity = new HashMap<>();
    candidates.forEach(c -> activity.put(c.id, activityOf(c, runs, tags)));

    Map<String, List<GateSubject.Upstream>> out = new LinkedHashMap<>();
    for (ReleaseRequest row : rows) {
      Set<String> ownNames = namesOf(row.repoId, row.repoName, aliases);
      List<GateSubject.Upstream> upstream = new ArrayList<>();
      for (ReleaseRequest candidate : candidates) {
        if (!java.util.Objects.equals(candidate.projectId, row.projectId)
            || candidate.repoId.equals(row.repoId)) {
          continue;
        }
        Activity doing = activity.get(candidate.id);
        Optional<List<String>> closure =
            closures.closureOf(candidate.repoId, candidate.repoName, mayAsk && doing.active());
        if (closure.isEmpty()) {
          if (doing.active()) {
            upstream.add(upstream(candidate, doing, false));
          }
          continue;
        }
        if (!namesAny(closure.get(), ownNames)) {
          continue;
        }
        if (doing.active() && cyclic(row, candidate, aliases, mayAsk)) {
          continue;
        }
        upstream.add(upstream(candidate, doing, true));
      }
      out.put(row.id, List.copyOf(upstream));
    }
    return out;
  }

  /** Whether {@code row}'s repository is also upstream of {@code candidate}'s: a cycle. */
  private boolean cyclic(
      ReleaseRequest row,
      ReleaseRequest candidate,
      Map<String, Set<String>> aliases,
      boolean mayAsk) {
    Optional<List<String>> own = closures.closureOf(row.repoId, row.repoName, mayAsk);
    if (own.isEmpty()
        || !namesAny(own.get(), namesOf(candidate.repoId, candidate.repoName, aliases))) {
      return false;
    }
    if (warnedCycles.add(row.repoId + "|" + candidate.repoId)) {
      LOG.warnf(
          "%s and %s are each downstream of the other; the dependency closure is not a DAG, so"
              + " neither holds the other's QA (request %s, upstream request %s)",
          row.repoName, candidate.repoName, qualified(row), qualified(candidate));
    }
    return true;
  }

  private List<ReleaseRequest> candidatesIn(Collection<String> projectIds) {
    return requests.list(
        "projectId in ?1 and state in ?2 order by createdAt", projectIds, CANDIDATE_STATES);
  }

  /** Every name each repository of the projects goes by, keyed by repository id. */
  private Map<String, Set<String>> aliasesIn(Collection<String> projectIds) {
    Map<String, Set<String>> out = new HashMap<>();
    for (RepositoryName alias :
        names.list("project.id in ?1", List.copyOf(projectIds))) {
      if (alias.repository != null && alias.name != null) {
        out.computeIfAbsent(alias.repository.id, key -> new HashSet<>()).add(alias.name);
      }
    }
    return out;
  }

  private static Set<String> namesOf(
      String repoId, String repoName, Map<String, Set<String>> aliases) {
    Set<String> all = new HashSet<>(aliases.getOrDefault(repoId, Set.of()));
    if (repoName != null) {
      all.add(repoName);
    }
    return all;
  }

  private static boolean namesAny(List<String> closure, Set<String> names) {
    return closure.stream().anyMatch(names::contains);
  }

  /** What one candidate is doing, and whether that is an active build. */
  record Activity(boolean active, String doing, String qaRunId) {}

  static Activity activityOf(
      ReleaseRequest candidate,
      Map<String, List<ReleasePipelineRun>> runs,
      Map<String, ReleasedTagPendingMerge> tags) {
    String fold = shortSha(candidate.mergedSha);
    return switch (candidate.state) {
      case PENDING -> {
        ReleasePipelineRun building =
            qaRunsAtFold(candidate, runs).stream()
                .filter(run -> BUILDING.contains(run.status))
                .findFirst()
                .orElse(null);
        yield building == null
            ? new Activity(false, "Pending, with no QA run going", null)
            : new Activity(
                true,
                "QA run " + building.runId + " is " + building.status + " at " + fold,
                building.runId);
      }
      case READY -> {
        String runId =
            qaRunsAtFold(candidate, runs).stream().map(run -> run.runId).findFirst().orElse(null);
        yield new Activity(true, "Ready to release " + fold, runId);
      }
      case RELEASED -> {
        ReleasedTagPendingMerge tag = tags.get(candidate.id);
        yield tag != null && tag.publishState == ReleasedTagPendingMerge.PublishState.PENDING
            ? new Activity(true, "Publishing " + tag.tagName, null)
            : new Activity(false, "Released", null);
      }
      default -> new Activity(false, candidate.state.name(), null);
    };
  }

  /** The candidate's QA runs at its current fold, newest transition first. */
  private static List<ReleasePipelineRun> qaRunsAtFold(
      ReleaseRequest candidate, Map<String, List<ReleasePipelineRun>> runs) {
    if (candidate.mergedSha == null) {
      return List.of();
    }
    return runs.getOrDefault(candidate.id, List.of()).stream()
        .filter(run -> ReleasePipelineRuns.PHASE_RELEASE_REQUEST.equals(run.phase))
        .filter(run -> candidate.mergedSha.equals(run.commitSha))
        .toList();
  }

  private static GateSubject.Upstream upstream(
      ReleaseRequest candidate, Activity doing, boolean known) {
    return new GateSubject.Upstream(
        candidate.id,
        qualified(candidate),
        candidate.repoId,
        doing.doing(),
        doing.qaRunId(),
        doing.active(),
        known,
        // qits-914 attaches a person's override of the pair here.
        null);
  }

  private static String qualified(ReleaseRequest row) {
    return row.qualifiedId == null ? row.id : row.qualifiedId;
  }

  private static String shortSha(String sha) {
    if (sha == null) {
      return "(nothing)";
    }
    return sha.length() <= 10 ? sha : sha.substring(0, 10);
  }
}
