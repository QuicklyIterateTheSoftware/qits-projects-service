package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import eu.wohlben.qits.projects.dto.ReleaseAutomationDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

/** Builders for {@link GateSubject}s in the gate tests. */
final class GateFixtures {

  static final String FOLD = "0123456789abcdef0123456789abcdef01234567";

  private GateFixtures() {}

  static ReleaseRequest request() {
    ReleaseRequest row = new ReleaseRequest();
    row.id = "rr-1";
    row.repoId = "repo-1";
    row.mergedSha = FOLD;
    row.state = ReleaseRequest.State.PENDING;
    return row;
  }

  static ReleaseGates.GateSet configured(ReleaseGates.Kind... kinds) {
    EnumSet<ReleaseGates.Kind> set = EnumSet.noneOf(ReleaseGates.Kind.class);
    set.addAll(List.of(kinds));
    return ReleaseGates.GateSet.of(set);
  }

  static ReleaseGates.GateSet unreadable() {
    return ReleaseGates.GateSet.unknown("release-requests.yml does not parse");
  }

  static GateSubject.Approval notRequired() {
    return new GateSubject.Approval(
        false, ReleaseRequest.ApprovalState.NOT_REQUIRED, null, null, null, null);
  }

  static GateSubject.Approval approval(ReleaseRequest.ApprovalState state) {
    boolean decided =
        state == ReleaseRequest.ApprovalState.APPROVED
            || state == ReleaseRequest.ApprovalState.DECLINED;
    return new GateSubject.Approval(
        true,
        state,
        decided ? "ada" : null,
        decided ? Instant.parse("2026-10-09T10:00:00Z") : null,
        decided ? " not this week " : null,
        "manual-review is on");
  }

  static GateSubject.Automations noAutomations() {
    return new GateSubject.Automations(false, null, null);
  }

  static GateSubject.Automations automations(
      ReleaseGates.State state, ReleaseAutomationDto... rows) {
    return new GateSubject.Automations(true, state, rows.length == 0 ? null : List.of(rows));
  }

  static ReleaseAutomationDto automation(String kind, String label, String state, String detail) {
    return new ReleaseAutomationDto(
        kind, label, state, FOLD, "run-" + kind, null, detail, null, null);
  }

  static CommitBuildStatusDto green(String runId) {
    return new CommitBuildStatusDto(runId, "SUCCESS", "release/rr-1", null);
  }

  static CommitBuildStatusDto red(String runId) {
    return new CommitBuildStatusDto(runId, "FAILED", "release/rr-1", null);
  }

  static ReleasedTagPendingMerge released(
      ReleasedTagPendingMerge.PublishState publish, boolean merged) {
    ReleasedTagPendingMerge tag = new ReleasedTagPendingMerge();
    tag.tagName = "2026.1009.100000";
    tag.publishState = publish;
    tag.publishRunId = publish == null ? null : "publish-run";
    tag.publishDetail = publish == ReleasedTagPendingMerge.PublishState.FAILED ? "Run red" : null;
    tag.mergedAt = merged ? Instant.parse("2026-10-09T11:00:00Z") : null;
    return tag;
  }

  static GateSubject subject(
      ReleaseGates.GateSet set,
      GateSubject.Approval approval,
      GateSubject.Automations automations,
      List<CommitBuildStatusDto> verdicts,
      ReleasedTagPendingMerge released) {
    return new GateSubject(request(), set, approval, automations, verdicts, released);
  }

  static GateSubject subject(ReleaseGates.GateSet set) {
    return subject(set, notRequired(), noAutomations(), List.of(), null);
  }
}
