package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
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
 * <p><b>Answers</b> PENDING while some active upstream request is not overridden; PASSED when none is
 * active or every active one is overridden; UNKNOWN when an active request's closure could not be
 * read and nothing active is known — and <b>UNKNOWN does not hold</b>: the gate postpones and never
 * protects content, so a qits-maintenance outage must not freeze every release.
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
    return answer(subject.upstream().requests());
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
                one.doing() + "; overridden by " + one.override().decidedBy(),
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
