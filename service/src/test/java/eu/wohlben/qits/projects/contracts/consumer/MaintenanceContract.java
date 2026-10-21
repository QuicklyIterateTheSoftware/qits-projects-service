package eu.wohlben.qits.projects.contracts.consumer;

import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import eu.wohlben.qits.projects.control.ReleaseRequestAutomations;
import eu.wohlben.qits.projects.maintenancehost.HttpAutomationKinds;
import eu.wohlben.qits.projects.maintenancehost.HttpDownstreamComponents;
import eu.wohlben.qits.projects.maintenancehost.HttpDownstreamPins;
import eu.wohlben.qits.projects.maintenancehost.HttpReleaseRequestAutomations;
import eu.wohlben.qits.projects.maintenancehost.MaintenanceBearer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.json;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What qits-projects asks qits-maintenance</b> (ticket qits-1149): the downstream closure of a
 * repository, and a release request's automations — settled, read, and run again.
 *
 * <p>The approval question's contract source (qits-1291) asks two more: the closure again, for each
 * entry's {@code catalogId} and {@code depth} too, and a consumer's pins ({@code getRepository}).
 * The second stays pending until qits-maintenance records a provider state that answers it — today
 * its golden masters hold no {@code getRepository} at all.
 *
 * <p>The automation content source (qits-1046) reads the kinds catalogue ({@code
 * listAutomationKinds}, state "the automation kinds catalogue"). qits-maintenance records that state
 * from 2026.1021.154504 on; until the pinned golden masters reach that version the row is pending.
 */
final class MaintenanceContract {

  static final String PROVIDER = "qits-maintenance-service";
  static final String APP = "qits-maintenance";

  private static final MaintenanceBearer BEARER = () -> Optional.of("Bearer machine-token");

  /** What every automations answer is read for ({@code HttpReleaseRequestAutomations.answer}). */
  private static final List<String> AUTOMATIONS =
      List.of(
          "$.requestId",
          "$.foldSha",
          "$.automations[*].kind",
          "$.automations[*].label",
          "$.automations[*].state",
          "$.automations[*].detail",
          "$.automations[*].bumpId",
          "$.automations[*].runIds",
          "$.automations[*].branch",
          "$.automations[*].resultSha",
          "$.automations[*].updatedAt",
          "$.automations[*].failure");

  private MaintenanceContract() {}

  private static HttpReleaseRequestAutomations automations(String base) {
    return Fields.with(
        new HttpReleaseRequestAutomations(RowClients.clients()), "maintenanceUrl", Optional.of(base), "bearer", BEARER);
  }

  private static HttpAutomationKinds kinds(String base) {
    return Fields.with(
        new HttpAutomationKinds(RowClients.clients()), "maintenanceUrl", Optional.of(base), "bearer", BEARER);
  }

  private static HttpDownstreamPins pins(String base) {
    return Fields.with(
        new HttpDownstreamPins(RowClients.clients()), "maintenanceUrl", Optional.of(base), "bearer", BEARER);
  }

  static final List<ConsumerRow> ROWS =
      List.of(
          new ConsumerRow(
              PROVIDER,
              APP,
              "getRepositoryDownstream",
              "a repository with downstream components",
              "GET",
              "/maintenance/api/repositories/{repositoryId}/downstream",
              Map.of(),
              null,
              List.of(
                  "$.downstream[*].repository",
                  "$.downstream[*].catalogId",
                  "$.downstream[*].depth"),
              200,
              Trigger.operation("approveReleaseRequest"),
              (base, p) ->
                  assertFalse(
                      pins(base).downstreamOf(p.get("repositoryId")).orElseThrow().isEmpty()),
              "GET .../repositories/{repositoryId}/downstream answering 200 with downstream[] naming"
                  + " each repository, its catalogId and its depth"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "listAutomationKinds",
              "the automation kinds catalogue",
              "GET",
              "/maintenance/api/automations/kinds",
              Map.of(),
              null,
              List.of(
                  "$.kinds[*].kind",
                  "$.kinds[*].label",
                  "$.kinds[*].committablePaths",
                  "$.kinds[*].approvalRoles"),
              200,
              Trigger.operation("approveReleaseRequest"),
              (base, p) -> assertFalse(kinds(base).kinds().orElseThrow().isEmpty()),
              "GET .../automations/kinds answering 200 with kinds[] (kind, label, committablePaths[],"
                  + " approvalRoles[])"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "getRepository",
              "a repository whose manifests pin golden masters",
              "GET",
              "/maintenance/api/repositories/{repoName}",
              Map.of(),
              null,
              List.of("$.pins[*].ecosystem", "$.pins[*].name"),
              200,
              Trigger.operation("approveReleaseRequest"),
              (base, p) ->
                  assertFalse(pins(base).pinsOf(p.get("repoName")).orElseThrow().isEmpty()),
              "GET .../repositories/{repoName} answering 200 with pins[] (ecosystem, name) holding"
                  + " a maven or npm pin on another repository's <app>-golden-masters package"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "getRepositoryDownstream",
              "a repository with downstream components",
              "GET",
              "/maintenance/api/repositories/{repositoryId}/downstream",
              Map.of(),
              null,
              List.of("$.downstream[*].repository"),
              200,
              Trigger.operation("createReleaseRequest"),
              (base, p) ->
                  assertFalse(
                      Fields.with(
                              new HttpDownstreamComponents(RowClients.clients()),
                              "maintenanceUrl", Optional.of(base),
                              "bearer", BEARER)
                          .downstreamOf(p.get("repositoryId"), p.get("repoName"))
                          .orElseThrow()
                          .isEmpty()),
              "GET .../repositories/{repositoryId}/downstream answering 200 with downstream[] naming"
                  + " each repository"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "triggerReleaseRequestAutomations",
              "a release request with automations",
              "POST",
              "/maintenance/api/release-requests/{requestId}/automations",
              Map.of(),
              json(
                  "{\"repository\":\"{repoName}\",\"foldSha\":\"{foldSha}\",\"previousFoldSha\":null,"
                      + "\"changedSincePrevious\":null,\"sourceBranches\":[\"{branch}\"],"
                      + "\"workItem\":null,\"accepts\":[\"WAITING\",\"NOT_APPLICABLE\",\"DEFERRED\"],"
                      + "\"backingBranch\":\"{backingBranch}\",\"qualifiedId\":\"{qualifiedId}\"}"),
              AUTOMATIONS,
              200,
              Trigger.schedule("AutomationRefresh.attempt"),
              (base, p) -> {
                ReleaseRequestAutomations.Answer answer =
                    automations(base)
                        .request(
                            p.get("repoName"),
                            p.get("requestId"),
                            p.get("qualifiedId"),
                            p.get("backingBranch"),
                            p.get("foldSha"),
                            null,
                            null,
                            List.of(p.get("branch")),
                            null)
                        .orElseThrow();
                assertFalse(answer.automations().isEmpty());
              },
              "POST .../release-requests/{requestId}/automations answering 200 with requestId,"
                  + " foldSha and automations[] (one with a failure object)"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "listReleaseRequestAutomations",
              "a release request with automations",
              "GET",
              "/maintenance/api/release-requests/{requestId}/automations",
              Map.of("foldSha", "{foldSha}"),
              null,
              AUTOMATIONS,
              200,
              Trigger.schedule("AutomationRefresh.attempt"),
              (base, p) ->
                  assertTrue(
                      automations(base)
                          .status(p.get("repoName"), p.get("requestId"), p.get("foldSha"))
                          .isPresent()),
              "GET .../release-requests/{requestId}/automations?foldSha answering 200 with the same"
                  + " shape"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "runReleaseRequestAutomation",
              "a release request with a failed automation",
              "POST",
              "/maintenance/api/release-requests/{requestId}/automations/{kind}/runs",
              Map.of(),
              json("{\"workItem\":null,\"repository\":\"{repoName}\"}"),
              List.of("$.id"),
              202,
              Trigger.operation("rerunReleaseRequestAutomation"),
              (base, p) ->
                  assertTrue(
                      automations(base)
                          .rerun(p.get("repoName"), p.get("requestId"), p.get("kind"))
                          .wasAccepted()),
              "POST .../automations/{kind}/runs answering 2xx with the new run's id"));
}
