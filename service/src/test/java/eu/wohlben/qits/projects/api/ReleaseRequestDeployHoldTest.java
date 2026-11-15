package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryName;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code PUT …/release-requests/{requestId}/deploy-hold} (qits-2268): every person and every agent
 * switches a request's deploy hold, in any project, until the request releases; the switch is
 * attributed to the caller, idempotent, and never inherited by another request.
 */
@QuarkusTest
public class ReleaseRequestDeployHoldTest {

  private static final String PROJECT = "deploy-hold-project";
  private static final String REPO = "deploy-hold-repo";
  private static final String REPO_NAME = "deploy-hold-service";

  @Inject ReleaseRequests releaseRequests;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (Project.findById(PROJECT) != null) {
                return;
              }
              Project project = new Project();
              project.id = PROJECT;
              project.name = PROJECT;
              project.slug = PROJECT;
              project.persist();
              Repository repository = new Repository();
              repository.id = REPO;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
              RepositoryName name = new RepositoryName();
              name.project = project;
              name.repository = repository;
              name.name = REPO_NAME;
              name.persist();
            });
  }

  /** Open requests must not outlive the class: other tests sweep every open row. */
  @AfterEach
  void dropRequests() {
    QuarkusTransaction.requiringNew().run(() -> ReleaseRequest.delete("projectId", PROJECT));
  }

  private String request(String branch) {
    return releaseRequests.request(REPO, branch, "setup", null).id();
  }

  private static ValidatableResponse put(String requestId, String body, String... caller) {
    return given()
        .header("Authorization", ContractBearers.caller(caller[0], caller[1]))
        .contentType(ContentType.JSON)
        .body(body)
        .put("/projects/api/repositories/" + REPO + "/release-requests/" + requestId + "/deploy-hold")
        .then();
  }

  private static ReleaseRequest row(String id) {
    return QuarkusTransaction.requiringNew().call(() -> ReleaseRequest.findById(id));
  }

  private static void state(String id, ReleaseRequest.State state) {
    // Straight to the column: the transition writer is the state machine's, which a fixture
    // must not have to drive to RELEASED.
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ReleaseRequest.update(
                    "state = ?1 where id = ?2", state, id));
  }

  @Test
  void anAgentHoldsAndReleasesTheDeployAndTheRequestSaysWho() {
    String id = request("ticket/hold-agent");

    put(id, "{\"hold\":true}", "agent-7", "qits:agent")
        .statusCode(200)
        .body("request.id", org.hamcrest.Matchers.equalTo(id))
        .body("request.deployHold", org.hamcrest.Matchers.equalTo(true))
        .body("request.deployHoldBy", org.hamcrest.Matchers.equalTo("agent-7"))
        .body("request.deployHoldAt", org.hamcrest.Matchers.notNullValue());
    assertTrue(row(id).deployHold);

    put(id, "{\"hold\":false}", "agent-8", "qits:agent")
        .statusCode(200)
        .body("request.deployHold", org.hamcrest.Matchers.equalTo(false))
        .body("request.deployHoldBy", org.hamcrest.Matchers.equalTo("agent-8"));
    ReleaseRequest off = row(id);
    assertFalse(off.deployHold);
    assertEquals("agent-8", off.deployHoldBy, "off keeps naming who switched it off");
    assertNotNull(off.deployHoldAt);
    assertEquals(ReleaseRequest.State.PENDING, off.state, "no state moves");
  }

  @Test
  void theDeskAndAPersonSwitchItToo() {
    String id = request("ticket/hold-desk");

    put(id, "{\"hold\":true}", "desk-delivery", "qits:desk").statusCode(200);
    assertEquals("desk-delivery", row(id).deployHoldBy);
    put(id, "{\"hold\":false}", "ada", "qits:admin").statusCode(200);
    assertEquals("ada", row(id).deployHoldBy);
    assertFalse(row(id).deployHold);
  }

  /** The second identical ask changes nothing: who switched it and when stay. */
  @Test
  void askingForTheValueItHasIsANoOp() {
    String id = request("ticket/hold-twice");
    put(id, "{\"hold\":true}", "ada", "qits:admin").statusCode(200);
    ReleaseRequest first = row(id);

    put(id, "{\"hold\":true}", "agent-7", "qits:agent").statusCode(200);

    ReleaseRequest second = row(id);
    assertEquals("ada", second.deployHoldBy);
    assertEquals(first.deployHoldAt, second.deployHoldAt);
  }

  /** The id is the uuid or the qualified id, in any case, as on every sibling door. */
  @Test
  void theQualifiedIdAddressesTheRequest() {
    String id = request("ticket/hold-qualified");
    String qualified = row(id).qualifiedId;
    assertTrue(qualified.startsWith(REPO_NAME + "-rr-"), qualified);

    put(qualified.toUpperCase(Locale.ROOT), "{\"hold\":true}", "ada", "qits:admin")
        .statusCode(200)
        .body("request.id", org.hamcrest.Matchers.equalTo(id));
  }

  @Test
  void aReleasedOrFinalizedRequestIs409() {
    String released = request("ticket/hold-released");
    state(released, ReleaseRequest.State.RELEASED);
    put(released, "{\"hold\":true}", "ada", "qits:admin")
        .statusCode(409)
        .body(
            org.hamcrest.Matchers.containsString(
                "a deploy hold must be set before the request releases"));

    String finalized = request("ticket/hold-finalized");
    state(finalized, ReleaseRequest.State.FINALIZED);
    put(finalized, "{\"hold\":false}", "agent-7", "qits:agent").statusCode(409);
    assertFalse(row(finalized).deployHold);
  }

  @Test
  void rejectedAndConflictedRequestsStillTakeIt() {
    String rejected = request("ticket/hold-rejected");
    state(rejected, ReleaseRequest.State.REJECTED);
    put(rejected, "{\"hold\":true}", "ada", "qits:admin").statusCode(200);
    String conflicted = request("ticket/hold-conflicted");
    state(conflicted, ReleaseRequest.State.CONFLICTED);
    put(conflicted, "{\"hold\":true}", "ada", "qits:admin").statusCode(200);
  }

  @Test
  void anUnknownRequestIs404AndAMissingHoldIs400() {
    put("no-such-request", "{\"hold\":true}", "ada", "qits:admin").statusCode(404);
    String id = request("ticket/hold-missing");
    put(id, "{}", "ada", "qits:admin").statusCode(400);
    assertFalse(row(id).deployHold);
  }

  /** {@code qits:system} is not among the callers: a machine holds nobody's deploy. */
  @Test
  void aServiceClientIs403() {
    String id = request("ticket/hold-system");
    put(id, "{\"hold\":true}", "dev-qits-maintenance", "qits:system").statusCode(403);
  }

  /**
   * Every request starts off: a request minted after a held one was withdrawn, and the request a
   * moved source lands in, inherit nothing.
   */
  @Test
  void noOtherRequestInheritsTheHold() {
    String held = request("ticket/hold-first");
    releaseRequests.addSource(held, "ticket/hold-second", "setup", null);
    releaseRequests.setDeployHold(held, true, "ada");

    ReleaseRequests.Moved moved =
        releaseRequests.moveSource(held, "ticket/hold-second", "not ready", "ada");
    assertTrue(moved.from().deployHold(), "the request keeps its own hold through the refold");
    assertNotEquals(held, moved.to().id());
    assertFalse(moved.to().deployHold(), "the queued request starts off");
    assertNull(moved.to().deployHoldBy());

    // Releasing the queue: withdrawing both leaves the repository free for a fresh ask.
    releaseRequests.withdraw(moved.to().id(), null, "ada");
    releaseRequests.withdraw(held, null, "ada");
    ReleaseRequestDto fresh = releaseRequests.request(REPO, "ticket/hold-first", "setup", null);
    assertNotEquals(held, fresh.id());
    assertFalse(fresh.deployHold(), "a successor starts off");
    assertNull(fresh.deployHoldAt());
  }

  @Test
  void theListCarriesTheHoldToo() {
    String id = request("ticket/hold-listed");
    releaseRequests.setDeployHold(id, true, "ada");
    Instant before = Instant.now().plusSeconds(1);

    List<ReleaseRequestDto> listed = releaseRequests.listByRepo(REPO, null);
    ReleaseRequestDto mine = listed.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    assertTrue(mine.deployHold());
    assertEquals("ada", mine.deployHoldBy());
    assertTrue(mine.deployHoldAt().isBefore(before));
  }
}
