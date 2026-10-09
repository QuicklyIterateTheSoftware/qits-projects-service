package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.AutomationLedger;
import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.dto.ReleaseAutomationDto;
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
 */
@ApplicationScoped
public class AutomationsGate implements ReleaseGate {

  public static final String KIND = "automations";

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
    return ReleaseGatePosition.QA_PUBLISH;
  }

  @Override
  public int order() {
    return 200;
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
    // A kind that does not apply is listed on the request, and is no part of this gate.
    List<ReleaseAutomationDto> rows =
        facts.rows() == null
            ? List.of()
            : facts.rows().stream()
                .filter(row -> !AutomationLedger.NOT_APPLICABLE.equals(row.state()))
                .toList();
    List<GateCheck> checks =
        rows.stream()
            .map(
                row ->
                    new GateCheck(
                        row.label() == null ? row.kind() : row.label(),
                        checkState(row.state()),
                        row.detail() == null ? row.state() : row.state() + ": " + row.detail(),
                        row.runId()))
            .toList();
    return new GateEvaluation(state, detail(state, rows), checks, null, null);
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
      default -> ReleaseGates.State.PENDING.name();
    };
  }

  private static String detail(ReleaseGates.State state, List<ReleaseAutomationDto> rows) {
    return switch (state) {
      case PASSED ->
          rows.stream().anyMatch(row -> "WAIVED".equals(row.state()))
              ? "A person waived the automations for this fold"
              : null;
      case FAILED -> "Failed: " + labels(rows, "FAILED");
      case UNKNOWN -> "The automations' state could not be read";
      case PENDING ->
          rows.isEmpty()
              ? "Waiting for the automations to answer"
              : "Waiting for: " + labels(rows, "PENDING");
    };
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
