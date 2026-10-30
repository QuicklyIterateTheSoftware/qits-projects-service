package eu.wohlben.qits.projects.entitieshost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.UnattendedGateTickets.Rejection;
import eu.wohlben.qits.projects.control.gate.AutomationsGate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the gate ticket says, per rejecting gate (qits-1561): a red CI run keeps its wording byte for
 * byte, and a failed release-request automation says that instead of "its build came back red".
 */
class TicketUnattendedGateTicketsWordingTest {

  private static Rejection rejection(String gate, String runId, String detail) {
    return new Rejection(
        "request-uuid",
        "demo-service-rr-7",
        "project-1",
        "repo-1",
        "demo-service",
        List.of("maintenance/dependencies"),
        "abc123",
        runId,
        "FAILED",
        detail,
        gate,
        List.of("dev-qits-maintenance"),
        null);
  }

  @Test
  void aRedCiRunReadsExactlyAsItAlwaysDid() {
    Rejection red = rejection(null, "run-1", "Run run-1 finished FAILED for abc123");
    assertEquals(
        "A release of `demo-service` was asked for by **dev-qits-maintenance** — a machine, so"
            + " nobody is waiting on it — and its build came back red. The repository stops moving"
            + " here until somebody fixes the build: the request re-arms by itself on the next push"
            + " to a participating branch.\n\n"
            + "| | |\n|---|---|\n"
            + "| Repository | `demo-service` |\n"
            + "| Repository id | `repo-1` |\n"
            + "| Release request | `demo-service-rr-7` |\n"
            + "| Branches folded | `maintenance/dependencies` |\n"
            + "| Folded sha | `abc123` |\n"
            + "| Run | `run-1` (FAILED) |\n"
            + "\n**The gate's own words:** Run run-1 finished FAILED for abc123\n\n"
            + "Reported by qits-projects because the release request has no human or agent"
            + " participant. Read the run's log in qits-ci to find out why the build failed; this"
            + " report deliberately does not guess.\n",
        TicketUnattendedGateTickets.body(red));
    assertEquals(
        "The gate is still red. Run `run-1` finished FAILED for the fold `abc123` of release"
            + " request `demo-service-rr-7`.\n\n> Run run-1 finished FAILED for abc123",
        TicketUnattendedGateTickets.comment(red));
  }

  @Test
  void aFailedAutomationSaysSoAndNamesTheWaysOut() {
    String detail = "Screenshot baselines failed at abc123; push, re-run, or waive this fold";
    Rejection failed = rejection(AutomationsGate.KIND, "run-screenshots", detail);

    String body = TicketUnattendedGateTickets.body(failed);
    assertTrue(body.contains("— and a release-request automation failed on its fold."), body);
    assertFalse(body.contains("build came back red"), body);
    assertFalse(body.contains("qits-ci"), body);
    assertTrue(body.contains("| Automation run | `run-screenshots` (FAILED) |\n"), body);
    assertTrue(body.contains("**The gate's own words:** " + detail + "\n"), body);
    assertTrue(body.contains("Read the automation run's log"), body);
    assertTrue(body.contains("push a fix to a participating branch"), body);
    assertTrue(body.contains("`qits maintenance automation run`"), body);
    assertTrue(body.contains("waive the automations at this fold"), body);

    assertEquals(
        "The gate is still red. Automation run `run-screenshots` FAILED for the fold `abc123` of"
            + " release request `demo-service-rr-7`.\n\n> "
            + detail,
        TicketUnattendedGateTickets.comment(failed));
  }

  @Test
  void aFailedAutomationWithNoRecordedRunSaysNoneRecorded() {
    Rejection failed = rejection(AutomationsGate.KIND, null, "Entity diagram failed at abc123");

    assertTrue(
        TicketUnattendedGateTickets.body(failed)
            .contains("| Automation run | _none recorded_ (FAILED) |\n"),
        TicketUnattendedGateTickets.body(failed));
    assertEquals(
        "The gate is still red. A release-request automation FAILED for the fold `abc123` of"
            + " release request `demo-service-rr-7`.\n\n> Entity diagram failed at abc123",
        TicketUnattendedGateTickets.comment(failed));
  }
}
