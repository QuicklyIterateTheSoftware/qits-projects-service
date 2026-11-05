package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <b>Gate 3: an upstream is still releasing</b> (epic qits-897, qits-913). While a release request
 * in a repository upstream of this one has an <em>active build</em>, this request does not start QA:
 * the upstream gets the resources, and the downstream is not built twice (once now, and once more
 * after the dependency bump the upstream's publish brings).
 *
 * <p><b>Applies</b> to a PENDING request with a fold that has no QA verdict yet, in a repository
 * that is not the estate wrapper. Once QA has a verdict the gate is not asked (decision 5): it guards
 * the start of QA and nothing after it. The wrapper is exempt (decision 8): its pins move through
 * {@code estate-pins} and a person.
 *
 * <p><b>Overrides</b> (qits-914): a person may let this request start QA past one named upstream
 * request ({@code ReleaseRequests.overrideUpstream}). An overridden active upstream holds nothing;
 * its check is PASSED and its detail names who overrode.
 *
 * <p><b>Answers</b> PENDING while some active upstream request is not overridden; PASSED when none is
 * active or every active one is overridden; UNKNOWN when an active request's closure could not be
 * read and nothing active is known — and <b>UNKNOWN does not hold</b>: the gate postpones and never
 * protects content, so a qits-maintenance outage must not freeze every release.
 *
 * <p><b>An interrupted fold</b> (qits-909, qits-1872) — its QA cancelled by this gate's interrupt
 * and not announced again yet ({@code qaInterruptedSha} names {@code mergedSha}, {@code
 * qaAnnouncedSha} does not) — is held only by the upstream requests that interrupted it, recorded in
 * {@code qaInterruptedBy}. Any other active upstream is a PASSED check: held against whatever
 * happened to be building at each evaluation, a fold with many upstreams was re-announced only when
 * all of them were idle at once, which with releases leapfrogging was never. A row interrupted
 * before the column existed names nobody, and nothing holds it.
 *
 * <p><b>What decides is {@code ReleaseRequests.evaluate}</b>, which holds a fresh fold before it is
 * announced {@code preRun=DONE} through {@link #answer} — the same rule this class reports, so the
 * display and the decision cannot disagree. The facts are {@code UpstreamRequests}', read from this
 * service's own tables and a cache of qits-maintenance's closures.
 */
@ApplicationScoped
public class UpstreamInFlightGate implements ReleaseGate {

  public static final String KIND = "upstream-in-flight";

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "Upstream releases";
  }

  @Override
  public ReleaseGatePosition position() {
    return ReleaseGatePosition.PRE_RUN_QA;
  }

  @Override
  public int order() {
    return 75;
  }

  @Override
  public GateApplicability applicability(GateSubject subject) {
    GateSubject.Upstreams upstream = subject.upstream();
    ReleaseRequest request = subject.request();
    if (!upstream.read()
        || upstream.estateWrapper()
        || request == null
        || request.state != ReleaseRequest.State.PENDING
        || request.mergedSha == null) {
      return GateApplicability.DOES_NOT_APPLY;
    }
    // No QA verdict at the fold: the request's own runs alone, as the CI gate reads them.
    return CiVerdicts.own(subject.verdicts(), request, null).isEmpty()
        ? GateApplicability.APPLIES
        : GateApplicability.DOES_NOT_APPLY;
  }

  @Override
  public GateEvaluation evaluate(GateSubject subject) {
    return answer(subject.upstream().requests(), subject.request());
  }

  /**
   * The upstream requests that hold in this list — active, known, not overridden, exactly the ones
   * {@link #answer} names — by request id. What the interrupt records (qits-1872).
   */
  public static List<String> holdingIds(List<GateSubject.Upstream> upstream) {
    List<String> ids = new ArrayList<>();
    for (GateSubject.Upstream one : upstream == null ? List.<GateSubject.Upstream>of() : upstream) {
      if (one.active() && one.known() && one.override() == null) {
        ids.add(one.requestId());
      }
    }
    return ids;
  }

  /**
   * The upstream request ids that may hold this request's fold, or null where every active upstream
   * may: an interrupted fold not announced again (qits-1872) is held only by its recorded
   * interrupters — none at all on a row interrupted before they were recorded.
   */
  public static Set<String> interruptedBy(ReleaseRequest request) {
    if (request == null
        || request.mergedSha == null
        || !request.mergedSha.equals(request.qaInterruptedSha)
        || request.mergedSha.equals(request.qaAnnouncedSha)) {
      return null;
    }
    Set<String> ids = new LinkedHashSet<>();
    if (request.qaInterruptedBy != null) {
      Arrays.stream(request.qaInterruptedBy.split(","))
          .map(String::strip)
          .filter(id -> !id.isEmpty())
          .forEach(ids::add);
    }
    return ids;
  }

  /** Whether this answer holds QA: PENDING, and nothing else (UNKNOWN never holds). */
  public static boolean holds(GateEvaluation answer) {
    return answer.state() == ReleaseGates.State.PENDING;
  }

  /**
   * The gate's rule over a list of upstream requests — what the display reports and what {@code
   * ReleaseRequests.evaluate} decides on.
   */
  public static GateEvaluation answer(List<GateSubject.Upstream> upstream) {
    return answer(upstream, null);
  }

  /**
   * The gate's rule for {@code request}'s fold: {@link #answer(List)}, except that an interrupted
   * fold is held only by the upstreams that interrupted it ({@link #interruptedBy}, qits-1872).
   */
  public static GateEvaluation answer(List<GateSubject.Upstream> upstream, ReleaseRequest request) {
    Set<String> interrupters = interruptedBy(request);
    List<GateSubject.Upstream> list = upstream == null ? List.of() : upstream;
    List<GateCheck> checks = new ArrayList<>();
    List<String> holding = new ArrayList<>();
    List<String> overridden = new ArrayList<>();
    boolean unknown = false;
    for (GateSubject.Upstream one : list) {
      if (!one.active()) {
        continue;
      }
      String name = one.qualifiedId() == null ? one.requestId() : one.qualifiedId();
      if (!one.known()) {
        unknown = true;
        checks.add(
            new GateCheck(
                name,
                ReleaseGates.State.UNKNOWN.name(),
                "Could not read whether it is upstream: " + one.doing(),
                one.qaRunId()));
        continue;
      }
      if (one.override() != null) {
        overridden.add(name + " (" + one.override().decidedBy() + ")");
        checks.add(
            new GateCheck(
                name,
                ReleaseGates.State.PASSED.name(),
                "Overridden by " + one.override().decidedBy() + "; " + one.doing(),
                one.qaRunId()));
        continue;
      }
      if (interrupters != null && !interrupters.contains(one.requestId())) {
        checks.add(
            new GateCheck(
                name,
                ReleaseGates.State.PASSED.name(),
                "Did not interrupt this fold's QA, so it does not hold it; " + one.doing(),
                one.qaRunId()));
        continue;
      }
      holding.add(name);
      checks.add(
          new GateCheck(name, ReleaseGates.State.PENDING.name(), one.doing(), one.qaRunId()));
    }
    if (!holding.isEmpty()) {
      return new GateEvaluation(
          ReleaseGates.State.PENDING,
          "Waiting for upstream releases: " + String.join(", ", holding),
          checks,
          null,
          null);
    }
    if (unknown) {
      return new GateEvaluation(
          ReleaseGates.State.UNKNOWN, "Could not read what is upstream", checks, null, null);
    }
    return new GateEvaluation(
        ReleaseGates.State.PASSED,
        overridden.isEmpty()
            ? null
            : "Overridden: " + overridden.stream().collect(Collectors.joining(", ")),
        checks,
        null,
        null);
  }
}
