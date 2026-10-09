package eu.wohlben.qits.projects.control.gate;

/**
 * One release-request quality gate. One CDI bean per kind; {@link ReleaseGateEvaluator} finds them
 * all through {@code Instance<ReleaseGate>}, so a new gate is a new class and nothing else.
 *
 * <p>qits-maintenance's {@code ReleaseRequestAutomation} and the {@code entities} module's {@code
 * TransitionGate} are the same shape.
 *
 * <p><b>A gate reads, it does not fetch.</b> Everything it needs is on the {@link GateSubject},
 * which the caller batches for a whole page of requests. A gate that made a call per request would
 * put a call per row on the busiest read this service has.
 *
 * <p><b>This is the gate as the answer reports it.</b> What moves a request ({@code
 * ReleaseRequests.evaluate} before the tag, {@code ReleaseFinalization} after it) still decides the
 * built-in gates in its own fixed order, because their interplay (a red build beside moving
 * automations holds instead of rejecting) is not a property of any one gate. A gate here never
 * moves a request.
 */
public interface ReleaseGate {

  /** The wire kind, {@code [a-z0-9-]+}, unique across gates: {@code ci}. */
  String kind();

  /** What a page prints: {@code CI build}. */
  String label();

  /** Which pipeline slot the gate stands in. */
  ReleaseGatePosition position();

  /**
   * Where the gate sorts among the others in the answer. The built-ins keep the order the
   * {@code gates} list always had: 100 to 500 in steps of 100.
   */
  int order();

  /** Whether this gate holds this request at all. */
  GateApplicability applicability(GateSubject subject);

  /** What the gate says. Asked only where {@link #applicability} answered {@code APPLIES}. */
  GateEvaluation evaluate(GateSubject subject);
}
