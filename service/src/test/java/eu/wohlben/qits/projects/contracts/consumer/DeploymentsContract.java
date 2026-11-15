package eu.wohlben.qits.projects.contracts.consumer;

import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import eu.wohlben.qits.projects.control.DeploymentRedeploys;
import eu.wohlben.qits.projects.control.DeploymentRequests;
import eu.wohlben.qits.projects.deploymenthost.HttpDeploymentRedeploys;
import eu.wohlben.qits.projects.deploymenthost.HttpDeploymentRequests;
import eu.wohlben.qits.projects.deploymenthost.TestDeploymentsBearers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.json;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * <b>What qits-projects asks qits-deployments</b> (ticket qits-1149): the deployment requests of a
 * released version, and the ask to deploy a version again.
 */
final class DeploymentsContract {

  static final String PROVIDER = "qits-deployments-service";
  static final String APP = "qits-deployments";

  /** The release request a held redeploy names (qits-2268). */
  static final String HELD_REQUEST = "contract-app-00000001-rr-1";

  private DeploymentsContract() {}

  static final List<ConsumerRow> ROWS =
      List.of(
          new ConsumerRow(
              PROVIDER,
              APP,
              "listDeploymentRequests",
              "an application deployed in an environment",
              "GET",
              "/deployments/api/deployment-requests",
              Map.of("repoId", "{repoId}", "version", "{version}"),
              null,
              List.of(
                  "$.deploymentRequests[*].id",
                  "$.deploymentRequests[*].deploymentStatus",
                  "$.deploymentRequests[*].createdAt"),
              200,
              Trigger.operation("getReleaseRequest"),
              (base, p) -> {
                List<DeploymentRequests.DeploymentRequestView> views =
                    Fields.with(
                            new HttpDeploymentRequests(RowClients.clients()),
                            "deploymentsUrl", Optional.of(base),
                            "bearer", TestDeploymentsBearers.machine())
                        .forRelease(p.get("repoId"), p.get("version"))
                        .orElseThrow();
                assertFalse(views.isEmpty());
              },
              "GET /deployments/api/deployment-requests?repoId&version answering 200 with"
                  + " deploymentRequests[] holding id, deploymentStatus and createdAt"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "softwareReleased",
              "a released version of a deployable repository",
              "POST",
              "/deployments/api/events/software-released",
              Map.of(),
              json(
                  "{\"repoId\":\"{repoId}\",\"projectId\":\"{projectId}\","
                      + "\"repoName\":\"{repoName}\",\"application\":\"{application}\","
                      + "\"version\":\"{version}\","
                      // qits-2268: the request the release came out of, and its deploy hold —
                      // sent only when held, so the row pins the held shape. Literals: the
                      // provider's state names neither, and the intake reads both as optional.
                      + "\"releaseRequestQualifiedId\":\"" + HELD_REQUEST + "\","
                      + "\"deployHold\":true}"),
              List.of(),
              202,
              Trigger.operation("rerunReleasePipelinePhase"),
              (base, p) ->
                  Fields.with(
                          new HttpDeploymentRedeploys(RowClients.clients()),
                          "deploymentsUrl", Optional.of(base),
                          "bearer", TestDeploymentsBearers.machine())
                      .deployAgain(
                          new DeploymentRedeploys.Redeploy(
                              p.get("repoId"),
                              p.get("projectId"),
                              p.get("repoName"),
                              p.get("application"),
                              p.get("version"),
                              HELD_REQUEST,
                              Boolean.TRUE)),
              "POST /deployments/api/events/software-released answering 202"));
}
