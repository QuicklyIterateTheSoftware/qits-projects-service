package eu.wohlben.qits.projects.contracts.consumer;

import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import eu.wohlben.qits.projects.control.ReleaseDecisions;
import eu.wohlben.qits.projects.releasehost.HttpActiveBuilds;
import eu.wohlben.qits.projects.releasehost.HttpCiRunLineage;
import eu.wohlben.qits.projects.releasehost.HttpPipelinePhaseReruns;
import eu.wohlben.qits.projects.releasehost.HttpPublishRuns;
import eu.wohlben.qits.projects.releasehost.HttpQaRunCancellations;
import eu.wohlben.qits.projects.releasehost.HttpReleaseDecisions;
import eu.wohlben.qits.projects.releasehost.IdpCiBearer;
import io.opentelemetry.api.OpenTelemetry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.json;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What qits-projects asks qits-ci</b> (ticket qits-1149): the release record, the release
 * phase, the active runs, one run's lineage, and the two asks a release request makes of its runs.
 * Every client here calls with {@link IdpCiBearer}, which is off in a test, so the request carries
 * the header identity instead; neither is part of the contract.
 */
final class CiContract {

  static final String PROVIDER = "qits-ci-service";
  static final String APP = "qits-ci";

  private CiContract() {}

  private static <T> T ci(T client, String base) {
    return Fields.with(client, "ciUrl", Optional.of(base), "bearer", new IdpCiBearer());
  }

  private static ConsumerRow row(
      String operationId,
      String state,
      String method,
      String path,
      Map<String, String> query,
      String body,
      List<String> consumes,
      int status,
      Trigger trigger,
      ConsumerRow.Call call,
      String needs) {
    return new ConsumerRow(
        PROVIDER, APP, operationId, state, method, path, query, body == null ? null : json(body),
        consumes, status, trigger, call, needs);
  }

  static final List<ConsumerRow> ROWS =
      List.of(
          row(
              "listReleaseArtifacts",
              "a released version with artifact decisions",
              "GET",
              "/ci/api/repositories/{repositoryId}/releases/{version}/artifacts",
              Map.of(),
              null,
              List.of(
                  "$.artifacts[*].type",
                  "$.artifacts[*].name",
                  "$.artifacts[*].decision",
                  "$.artifacts[*].unchangedSince"),
              200,
              Trigger.operation("getReleaseRequestArtifacts"),
              (base, p) -> {
                ReleaseDecisions.Answer answer =
                    ci(new HttpReleaseDecisions(QitsHttpClients.of(OpenTelemetry.noop(), null)), base).of(p.get("repositoryId"), p.get("version"));
                assertTrue(answer.ok(), answer.failure());
                assertFalse(answer.decisions().isEmpty());
              },
              "artifacts[] with type, name, decision and unchangedSince for a released version"),
          row(
              "cancelReleaseRequestRuns",
              "a release request with runs in flight",
              "POST",
              "/ci/api/runs/cancellations",
              Map.of(),
              "{\"repoId\":\"{repositoryId}\",\"releaseRequestId\":\"{releaseRequestId}\"}",
              List.of(),
              202,
              Trigger.operation("withdrawReleaseRequest"),
              (base, p) ->
                  ci(new HttpQaRunCancellations(QitsHttpClients.of(OpenTelemetry.noop(), null)), base)
                      .cancelRunsOf(p.get("repositoryId"), p.get("releaseRequestId")),
              "POST /ci/api/runs/cancellations answering 202"),
          row(
              "cancelReleaseRequestRuns",
              "a release request with runs in flight",
              "POST",
              "/ci/api/runs/cancellations",
              Map.of(),
              "{\"repoId\":\"{repositoryId}\",\"releaseRequestId\":\"{releaseRequestId}\","
                  + "\"phase\":\"RELEASE_REQUEST\"}",
              List.of(),
              202,
              Trigger.schedule("ReleaseRequests.remerge (a source moved during QA)"),
              (base, p) ->
                  ci(new HttpQaRunCancellations(QitsHttpClients.of(OpenTelemetry.noop(), null)), base)
                      .cancelQaRunsOf(p.get("repositoryId"), p.get("releaseRequestId")),
              "POST /ci/api/runs/cancellations with phase RELEASE_REQUEST answering 202"),
          row(
              "rerunReleaseRequestPhase",
              "a release request whose QA run failed",
              "POST",
              "/ci/api/runs/rerun",
              Map.of(),
              "{\"repoId\":\"{repositoryId}\",\"releaseRequestId\":\"{releaseRequestId}\","
                  + "\"phase\":\"RELEASE_REQUEST\"}",
              List.of("$.runId"),
              202,
              Trigger.operation("rerunReleasePipelinePhase"),
              (base, p) -> {
                String runId =
                    ci(new HttpPipelinePhaseReruns(QitsHttpClients.of(OpenTelemetry.noop(), null)), base)
                        .rerun(p.get("repositoryId"), p.get("releaseRequestId"), "RELEASE_REQUEST");
                assertFalse(runId.isBlank());
              },
              "POST /ci/api/runs/rerun answering 202 with runId"),
          row(
              "getRun",
              "a failed run that retries another",
              "GET",
              "/ci/api/runs/{runId}",
              Map.of(),
              null,
              List.of("$.retryOfRunId"),
              200,
              Trigger.event("BuildFailed"),
              (base, p) ->
                  assertTrue(
                      ci(new HttpCiRunLineage(QitsHttpClients.of(OpenTelemetry.noop(), null)), base).retryOfRunId(p.get("runId")).isPresent()),
              "GET /ci/api/runs/{runId} answering 200 with retryOfRunId set"),
          row(
              "getReleasePhase",
              "a repository that declares a release phase",
              "GET",
              "/ci/api/repositories/{repositoryId}/release-phase",
              Map.of("rev", "{rev}"),
              null,
              List.of("$.declared"),
              200,
              Trigger.schedule("ReleaseFinalization.releasedTree"),
              (base, p) ->
                  assertTrue(
                      ci(new HttpPublishRuns(QitsHttpClients.of(OpenTelemetry.noop(), null)), base)
                          .declaredFor(p.get("repositoryId"), p.get("rev"))
                          .isPresent()),
              "GET .../release-phase?rev={rev} answering 200 with a boolean declared"),
          row(
              "listActiveRuns",
              "a commit with a run in flight",
              "GET",
              "/ci/api/runs/active",
              Map.of(),
              null,
              List.of("$.runs[*].repoId", "$.runs[*].commitSha"),
              200,
              Trigger.operation("getReleaseRequest"),
              (base, p) ->
                  assertTrue(
                      ci(new HttpActiveBuilds(QitsHttpClients.of(OpenTelemetry.noop(), null)), base)
                              .activeFor(p.get("repositoryId"), p.get("commitSha"))
                              .orElseThrow()
                          >= 1),
              "GET /ci/api/runs/active answering 200 with runs[] holding {repositoryId} at"
                  + " {commitSha}"));
}
