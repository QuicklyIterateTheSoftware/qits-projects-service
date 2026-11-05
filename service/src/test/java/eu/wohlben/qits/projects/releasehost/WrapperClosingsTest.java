package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.control.ReleaseRequestNumbers;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.BranchDelivery;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /projects/api/projects/{project}/wrapper-closings} (qits-1873): one test per verdict
 * {@code TicketWrapperDone} classifies a VERIFIED ticket into — no row and a PUSHED row
 * (NO_REQUEST), a JOINED row on a PENDING request (IN_FLIGHT), a LIVE row and the empty wrapper's
 * withdrawal (CLOSING), a withdrawal without that proof and a row naming an OBSOLETE request
 * (UNPROVEN) — and the read's edges: no wrapper at all, a ticket not yet VERIFIED left out, the
 * order by number, the slug, an unknown project's 404, and the approval state a person's
 * {@code manual-review} makes WAITING, equal to what the release-request read answers.
 *
 * <p>The ledger rows and the requests are written straight to their tables, as {@code
 * BranchDeliveriesControllerTest} writes its rows: what is under test is the read and its
 * classification, and {@link BranchDeliveryTest} is where the flows that write them, and the close
 * rules the verdict is read off, are driven. Nothing here moves a ticket: the reconcile is off in
 * the suite and no ending is told.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
public class WrapperClosingsTest {

  /** The repository's release-request settings, where {@code manual-review} is declared. */
  private static final String SETTINGS_FILE = ".config/qits/release-requests.yml";

  @Inject WorkEntityService entities;

  @Inject ReleaseRequestNumbers numbers;

  @Inject RecordingReleaseGitHost gitHost;

  private String projectId;
  private String slug;
  private String wrapper;
  private final List<String> requestIds = new ArrayList<>();

  @BeforeEach
  void seed() {
    gitHost.reset();
    requestIds.clear();
    String unique = UUID.randomUUID().toString().substring(0, 8);
    projectId = "closings-" + UUID.randomUUID();
    slug = "closings-" + unique;
    wrapper = "closings-wrapper-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = projectId;
              project.slug = slug;
              project.persist();
              Repository repository = new Repository();
              repository.id = wrapper;
              repository.project = project;
              repository.mainBranch = "main";
              repository.archetype = RepositoryArchetype.PROJECT;
              repository.persist();
            });
  }

  @AfterEach
  void drop() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              BranchDelivery.delete("projectId = ?1", projectId);
            });
    gitHost.reset();
  }

  // ---- NO_REQUEST ------------------------------------------------------------------------------

  @Test
  public void aVerifiedTicketWithNoRowHasNoRequest() {
    String ticket = qualifiedIdOf(verifiedTicket());

    Map<String, Object> closing = only();
    assertEquals(ticket, closing.get("ticket"));
    assertEquals("ticket/" + ticket, closing.get("branch"));
    assertEquals("NO_REQUEST", closing.get("verdict"));
    assertAllNull(closing, "step", "requestId", "requestQualifiedId", "requestState", "approval", "version");
  }

  @Test
  public void aProjectWithNoWrapperHasNoRequestWhateverTheLedgerSays() {
    String ticket = qualifiedIdOf(verifiedTicket());
    String id = request(ReleaseRequest.State.PENDING, null);
    row(ticket, "JOINED", id, null);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                Repository.<Repository>findById(wrapper).archetype = RepositoryArchetype.SERVICE);

    Map<String, Object> closing = only();
    assertEquals("NO_REQUEST", closing.get("verdict"));
    assertAllNull(closing, "step", "requestId", "requestQualifiedId", "requestState", "approval", "version");
  }

  @Test
  public void aPushedRowNamesNoRequest() {
    String ticket = qualifiedIdOf(verifiedTicket());
    row(ticket, "PUSHED", null, null);

    Map<String, Object> closing = only();
    assertEquals("NO_REQUEST", closing.get("verdict"));
    assertEquals("PUSHED", closing.get("step"));
    assertAllNull(closing, "requestId", "requestQualifiedId", "requestState", "approval", "version");
  }

  // ---- IN_FLIGHT -------------------------------------------------------------------------------

  @Test
  public void aJoinedRowOnAPendingRequestIsInFlight() {
    String ticket = qualifiedIdOf(verifiedTicket());
    String id = request(ReleaseRequest.State.PENDING, null);
    row(ticket, "JOINED", id, null);

    Map<String, Object> closing = only();
    assertEquals("IN_FLIGHT", closing.get("verdict"));
    assertEquals("JOINED", closing.get("step"));
    assertEquals(id, closing.get("requestId"));
    assertEquals(requestQualifiedIdOf(id), closing.get("requestQualifiedId"));
    assertEquals("PENDING", closing.get("requestState"));
    assertEquals("NOT_REQUIRED", closing.get("approval"));
    assertEquals(approvalStateOf(id), closing.get("approval"), "the release-request read's");
    assertNull(closing.get("version"));
  }

  /**
   * The approval is the release-request read's, derived and never stored: a wrapper declaring
   * {@code manual-review} makes its PENDING request WAITING for a person, here as there.
   */
  @Test
  public void aPendingRequestWhosePolicyNeedsAPersonIsWaiting() {
    gitHost.gatedTreeFor(wrapper, "refs/heads/main", Map.of(SETTINGS_FILE, "manual-review: true\n"));
    String ticket = qualifiedIdOf(verifiedTicket());
    String id = request(ReleaseRequest.State.PENDING, "5eed5eed5eed5eed5eed5eed5eed5eed18730001");
    row(ticket, "JOINED", id, null);

    Map<String, Object> closing = only();
    assertEquals("IN_FLIGHT", closing.get("verdict"));
    assertEquals("WAITING", closing.get("approval"));
    assertEquals(approvalStateOf(id), closing.get("approval"), "the release-request read's");
  }

  // ---- CLOSING ---------------------------------------------------------------------------------

  @Test
  public void aLiveRowIsClosing() {
    String ticket = qualifiedIdOf(verifiedTicket());
    String id = request(ReleaseRequest.State.FINALIZED, "5eed5eed5eed5eed5eed5eed5eed5eed18730002");
    row(ticket, "LIVE", id, "2026.1105.120000");

    Map<String, Object> closing = only();
    assertEquals("CLOSING", closing.get("verdict"));
    assertEquals("LIVE", closing.get("step"));
    assertEquals("FINALIZED", closing.get("requestState"));
    assertEquals("2026.1105.120000", closing.get("version"));
    assertEquals(approvalStateOf(id), closing.get("approval"));
  }

  @Test
  public void theEmptyWrappersWithdrawalIsClosing() {
    String ticket = qualifiedIdOf(verifiedTicket());
    String id =
        request(
            ReleaseRequest.State.WITHDRAWN,
            "5eed5eed5eed5eed5eed5eed5eed5eed18730003",
            ReleaseRequests.EMPTY_WRAPPER_WITHDRAWN);
    row(ticket, "WITHDRAWN", id, null);

    Map<String, Object> closing = only();
    assertEquals("CLOSING", closing.get("verdict"));
    assertEquals("WITHDRAWN", closing.get("step"));
    assertEquals("WITHDRAWN", closing.get("requestState"));
  }

  // ---- UNPROVEN --------------------------------------------------------------------------------

  @Test
  public void aWithdrawalWithoutTheEmptyWrappersProofIsUnproven() {
    String ticket = qualifiedIdOf(verifiedTicket());
    String id =
        request(
            ReleaseRequest.State.WITHDRAWN,
            "5eed5eed5eed5eed5eed5eed5eed5eed18730004",
            "Withdrawn by dev");
    row(ticket, "WITHDRAWN", id, null);

    Map<String, Object> closing = only();
    assertEquals("UNPROVEN", closing.get("verdict"));
    assertEquals("WITHDRAWN", closing.get("requestState"));
  }

  @Test
  public void aRowNamingAnObsoleteRequestIsUnproven() {
    String ticket = qualifiedIdOf(verifiedTicket());
    String id = request(ReleaseRequest.State.OBSOLETE, "5eed5eed5eed5eed5eed5eed5eed5eed18730005");
    row(ticket, "JOINED", id, null);

    Map<String, Object> closing = only();
    assertEquals("UNPROVEN", closing.get("verdict"));
    assertEquals("JOINED", closing.get("step"));
    assertEquals("OBSOLETE", closing.get("requestState"));
  }

  // ---- the read's edges ------------------------------------------------------------------------

  /** Only VERIFIED tickets, by number; the project is named by its slug as well as its id. */
  @Test
  public void onlyVerifiedTicketsAreListedInNumberOrderByIdOrSlug() {
    String first = qualifiedIdOf(verifiedTicket());
    ticketAt("VERIFYING");
    ticketAt("IMPLEMENTED");
    String second = qualifiedIdOf(verifiedTicket());

    for (String project : List.of(projectId, slug)) {
      JsonPath answer = read(project);
      assertEquals(List.of(first, second), answer.getList("closings.ticket"), project);
    }
  }

  @Test
  public void anUnknownProjectIsA404() {
    given().get(door("no-such-project-" + UUID.randomUUID())).then().statusCode(404);
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private static String door(String project) {
    return "/projects/api/projects/" + project + "/wrapper-closings";
  }

  private JsonPath read(String project) {
    return given().get(door(project)).then().statusCode(200).extract().jsonPath();
  }

  /** The one closing the project lists, every one of its nine members present. */
  private Map<String, Object> only() {
    JsonPath answer = read(projectId);
    assertEquals(1, answer.getList("closings").size(), answer.prettify());
    Map<String, Object> closing = answer.getMap("closings[0]");
    assertEquals(MEMBERS, closing.keySet(), "every member is always present: " + closing);
    return closing;
  }

  private static final java.util.Set<String> MEMBERS =
      java.util.Set.of(
          "ticket",
          "branch",
          "verdict",
          "step",
          "requestId",
          "requestQualifiedId",
          "requestState",
          "approval",
          "version");

  /** These members are null. */
  private static void assertAllNull(Map<String, Object> closing, String... members) {
    for (String member : members) {
      assertNull(closing.get(member), member + ": " + closing);
    }
  }

  /** A ticket of the fixture's project walked to {@code status} in process, past every gate. */
  private String ticketAt(String status) {
    String id =
        entities
            .create(
                Archetype.TICKET,
                projectId,
                EntityWrite.ticket("Closing", "it occurs", null, "BUG", null)
                    .withAcceptanceCriteria(List.of("It does what it says.")),
                "seed")
            .entity()
            .id;
    for (String step : List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFYING", "VERIFIED")) {
      entities.transition(Archetype.TICKET, id, step, Mover.person("seed"));
      if (step.equals(status)) {
        break;
      }
    }
    return id;
  }

  private String verifiedTicket() {
    return ticketAt("VERIFIED");
  }

  private String qualifiedIdOf(String ticketId) {
    long number =
        QuarkusTransaction.requiringNew()
            .call(() -> entities.get(Archetype.TICKET, ticketId))
            .number;
    return slug + "-" + number;
  }

  private String request(ReleaseRequest.State state, String mergedSha) {
    return request(state, mergedSha, null);
  }

  /** A wrapper release request written straight to its table, as the provider states write one. */
  private String request(ReleaseRequest.State state, String mergedSha, String detail) {
    Instant now = Instant.now();
    String id =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest row = new ReleaseRequest();
                  row.id = UUID.randomUUID().toString();
                  row.repoId = wrapper;
                  row.projectId = projectId;
                  row.repoName = slug + "-qits";
                  row.state = state;
                  row.mergedSha = mergedSha;
                  row.detail = detail;
                  row.createdAt = now;
                  row.armedAt = now;
                  row.updatedAt = now;
                  row.name(numbers.next(wrapper));
                  row.persist();
                  return row.id;
                });
    requestIds.add(id);
    return id;
  }

  /** The wrapper ledger row of {@code ticket}'s branch, as {@code BranchDeliveries} writes it. */
  private void row(String ticket, String step, String requestId, String version) {
    String qualifiedId = requestId == null ? null : requestQualifiedIdOf(requestId);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              BranchDelivery row = new BranchDelivery();
              row.projectId = projectId;
              row.repoId = wrapper;
              row.repoName = slug + "-qits";
              row.branch = "ticket/" + ticket;
              row.step = step;
              row.requestId = requestId;
              row.qualifiedId = qualifiedId;
              row.version = version;
              row.changedAt = Instant.now();
              row.persist();
            });
  }

  private String requestQualifiedIdOf(String requestId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.<ReleaseRequest>findById(requestId).qualifiedId);
  }

  /** What the release-request read answers as the request's {@code approvalState}. */
  private String approvalStateOf(String requestId) {
    return given()
        .get("/projects/api/repositories/" + wrapper + "/release-requests/" + requestId)
        .then()
        .statusCode(200)
        .extract()
        .path("request.approvalState");
  }
}
