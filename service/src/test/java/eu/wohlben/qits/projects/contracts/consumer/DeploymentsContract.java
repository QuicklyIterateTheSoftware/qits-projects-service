package eu.wohlben.qits.projects.contracts.consumer;

import static eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.json;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import eu.wohlben.qits.projects.control.DeploymentRedeploys;
import eu.wohlben.qits.projects.control.DeploymentRequests;
import eu.wohlben.qits.projects.deploymenthost.HttpDeploymentRedeploys;
import eu.wohlben.qits.projects.deploymenthost.HttpDeploymentRequests;
import eu.wohlben.qits.projects.deploymenthost.IdpDeploymentsBearer;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>What qits-projects asks qits-deployments</b> (ticket qits-1149): the deployment requests of a
 * released version, and the ask to deploy a version again. qits-deployments publishes no golden
 * masters yet, so both rows wait on their state.
 */
final class DeploymentsContract {

  static final String PROVIDER = "qits-deployments-service";
  static final String APP = "qits-deployments";

  private DeploymentsContract() {}

  static final List<ConsumerRow> ROWS =
      List.of(
          new ConsumerRow(
              PROVIDER,
              APP,
              "listDeploymentRequests",
              "a released version with a deployment request",
              "GET",
              "/deployments/api/deployment-requests",
              Map.of("repoId", "{repositoryId}", "version", "{version}"),
              null,
              List.of(
                  "$.deploymentRequests[*].id",
                  "$.deploymentRequests[*].deploymentStatus",
                  "$.deploymentRequests[*].createdAt"),
              200,
              Trigger.operation("getReleaseRequest"),
              (base, p) -> {
                List<DeploymentRequests.DeploymentRequestView> views =
                    Fields.with(new HttpDeploymentRequests(), "deploymentsUrl", Optional.of(base))
                        .forRelease(p.get("repositoryId"), p.get("version"))
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
                  "{\"repoId\":\"{repositoryId}\",\"projectId\":\"{projectId}\","
                      + "\"repoName\":\"{repoName}\",\"application\":\"{application}\","
                      + "\"version\":\"{version}\"}"),
              List.of(),
              202,
              Trigger.operation("rerunReleasePipelinePhase"),
              (base, p) ->
                  Fields.with(
                          new HttpDeploymentRedeploys(),
                          "deploymentsUrl", Optional.of(base),
                          "bearer", new IdpDeploymentsBearer())
                      .deployAgain(
                          new DeploymentRedeploys.Redeploy(
                              p.get("repositoryId"),
                              p.get("projectId"),
                              p.get("repoName"),
                              p.get("application"),
                              p.get("version"))),
              "POST /deployments/api/events/software-released answering 202"));
}
