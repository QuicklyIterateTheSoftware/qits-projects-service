package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.AutomationLedger;
import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.control.StaleOutputs;
import eu.wohlben.qits.projects.dto.ReleaseAutomationDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Every release-request automation that applies is fresh for the current fold, or a person waived
 * that fold. On wherever qits-maintenance is configured, never read from {@code main}, so an
 * unreadable gate set does not make it UNKNOWN. See {@code AutomationLedger}.
 *
 * <p>The state is the one {@code ReleaseRequests} already read off the ledger and the waiver; this
 * class adds the per-automation checks and a sentence.
 *
 * <p><b>It is the first gate (qits-1133)</b>: the automations are the pre-run, QA is asked for only
 * once they pass, so the gate stands between the pre-run and QA ({@link
 * ReleaseGatePosition#PRE_RUN_QA}) and is ordered ahead of {@link CiBuildGate}.
 */
@ApplicationScoped
public class AutomationsGate implements ReleaseGate {

  public static final String KIND = "automations";

  /**
   * The state of a check QA answers instead of the pre-run (qits-1920). Only a check carries it:
   * the gate's own state stays PASSED, because a deferral never holds the request (qits-1180).
   */
  public static final String DEFERRED = "DEFERRED";

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "Automations";
  }

  @Override
  public ReleaseGatePosition position() {
    return ReleaseGatePosition.PRE_RUN_QA;
  }

  @Override
  public int order() {
    return 50;
  }

  @Override
  public GateApplicability applicability(GateSubject subject) {
    return subject.automations() != null && subject.automations().applies()
        ? GateApplicability.APPLIES
        : GateApplicability.DOES_NOT_APPLY;
  }

  @Override
  public GateEvaluation evaluate(GateSubject subject) {
    GateSubject.Automations facts = subject.automations();
    ReleaseGates.State state = facts.state() == null ? ReleaseGates.State.PENDING : facts.state();
    // A kind that does not apply is listed on the request, and is no part of this gate. A kind QA
    // checks (DEFERRED) holds nothing either — the state above already says so — but it is listed
    // as a DEFERRED check, so the request says what QA still owes and how to answer it (qits-1920).
    List<ReleaseAutomationDto> listed =
        facts.rows() == null
            ? List.of()
            : facts.rows().stream()
                .filter(row -> !AutomationLedger.NOT_APPLICABLE.equals(row.state()))
                .toList();
    List<ReleaseAutomationDto> rows =
        listed.stream().filter(row -> !AutomationLedger.notInPreRun(row.state())).toList();
    List<ReleaseAutomationDto> deferred =
        listed.stream().filter(row -> AutomationLedger.DEFERRED.equals(row.state())).toList();
    List<GateCheck> checks =
        listed.stream()
            .map(
                row ->
                    new GateCheck(
                        row.label() == null ? row.kind() : row.label(),
                        checkState(row.state()),
                        AutomationLedger.DEFERRED.equals(row.state())
                            ? deferredDetail(subject.request(), row.kind())
                            : row.detail() == null ? row.state() : row.state() + ": " + row.detail(),
                        row.runId()))
            .toList();
    return new GateEvaluation(state, detail(state, rows, deferred), checks, null, null);
  }

  /**
   * What a DEFERRED check says (qits-1920): QA checks the kind, and the command that answers a
   * stale verdict — {@link StaleOutputs#command}, which names the kind alone where the request
   * carries no project and repository name.
   */
  static String deferredDetail(ReleaseRequest request, String kind) {
    return AutomationLedger.DEFERRED_SENTENCE
        + ": regenerate with "
        + StaleOutputs.command(
            request == null ? null : request.projectId,
            request == null ? null : request.repoName,
            kind)
        + " if QA reports it stale";
  }

  /** An automation's own word in the gate vocabulary. */
  static String checkState(String automationState) {
    if (automationState == null) {
      return ReleaseGates.State.PENDING.name();
    }
    return switch (automationState) {
      case "FRESH", "WAIVED" -> ReleaseGates.State.PASSED.name();
      case "FAILED" -> ReleaseGates.State.FAILED.name();
      case "UNKNOWN" -> ReleaseGates.State.UNKNOWN.name();
      // A check word, not a gate state: the gate itself never reads DEFERRED (qits-1920).
      case AutomationLedger.DEFERRED -> DEFERRED;
      default -> ReleaseGates.State.PENDING.name();
    };
  }

  private static String detail(
      ReleaseGates.State state,
      List<ReleaseAutomationDto> rows,
      List<ReleaseAutomationDto> deferred) {
    return switch (state) {
      case PASSED -> passed(rows, deferred);
      case FAILED -> "Failed: " + labels(rows, "FAILED");
      case UNKNOWN -> "The automations' state could not be read";
      case PENDING ->
          rows.isEmpty()
              ? "Waiting for the automations to answer"
              : "Waiting for: " + labels(rows, "PENDING");
    };
  }

  /** A waiver says so; a kind QA checks is named (qits-1920); fresh alone says nothing. */
  private static String passed(
      List<ReleaseAutomationDto> rows, List<ReleaseAutomationDto> deferred) {
    String waived =
        rows.stream().anyMatch(row -> "WAIVED".equals(row.state()))
            ? "A person waived the automations for this fold"
            : null;
    if (deferred.isEmpty()) {
      return waived;
    }
    String qa =
        "QA checks: "
            + deferred.stream().map(ReleaseAutomationDto::kind).collect(Collectors.joining(", "));
    return waived == null ? qa : waived + ". " + qa;
  }

  private static String labels(List<ReleaseAutomationDto> rows, String checkState) {
    String named =
        rows.stream()
            .filter(row -> checkState.equals(checkState(row.state())))
            .map(row -> row.label() == null ? row.kind() : row.label())
            .collect(Collectors.joining(", "));
    return named.isEmpty() ? "the automations" : named;
  }
}
