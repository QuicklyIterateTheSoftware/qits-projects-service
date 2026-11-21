package eu.wohlben.qits.projects.agenthost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A daemon's answer keeps its body on a reused connection</b> (qits-2394). With {@code body()}
 * chained flat after the send, the caller's thread attached it only after the first {@code compose}
 * returned, and a keep-alive connection could deliver the whole response in that gap and drop it: a
 * 429 came back with no body. The hook holds the caller's thread right there, so a flat chain loses
 * the body every time rather than once in a hundred.
 */
@QuarkusTest
public class DaemonApiClientBodyTest {

  @Inject Vertx vertx;

  @Inject AgentTunnels tunnels;

  @Inject DaemonApiClient client;

  @TestHTTPResource("/")
  URL root;

  private FakeProjectDaemon daemon;

  @AfterEach
  void tearDown() {
    client.afterChainBuilt(null);
    if (daemon != null) {
      daemon.close();
      daemon = null;
    }
  }

  @Test
  public void everyAnswerKeepsItsStatusAndBodyWhenTheCallerIsSlowToAttach() throws Exception {
    String projectId = createProject("Daemon Body " + UUID.randomUUID());
    daemon =
        FakeProjectDaemon.connect(
            vertx,
            tunnels,
            root.getHost(),
            root.getPort(),
            projectId,
            seen -> {
              int status = Integer.parseInt(seen.uri().substring(seen.uri().lastIndexOf('/') + 1));
              return new FakeProjectDaemon.Reply(status, body(status));
            });
    client.afterChainBuilt(
        () -> {
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    List<Integer> statuses = List.of(200, 409, 429, 200, 429);
    for (int i = 0; i < statuses.size(); i++) {
      int status = statuses.get(i);
      DaemonApiClient.Answer answer =
          i % 2 == 0
              ? client.post(projectId, "echo/" + status, "{\"n\":" + i + "}", 10_000)
              : client.get(projectId, "echo/" + status, 10_000);
      assertEquals(new DaemonApiClient.Answer(status, body(status)), answer, "call " + i);
    }
  }

  private static String body(int status) {
    return "{\"answered\":" + status + "}";
  }

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .header("Authorization", ContractBearers.caller("dana", "qits:admin"))
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dana"))
        .body(
            new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }
}
