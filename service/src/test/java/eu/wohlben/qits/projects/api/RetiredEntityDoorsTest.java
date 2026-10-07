package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.DossierService;
import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.DossierOwner;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The work-entity doors that were deleted stay deleted</b>, pressed as an operator against rows
 * that exist.
 *
 * <p>Epic qits-965 moved every work-entity capability onto the archetype-free {@code /work} family,
 * and qits-976 deleted what it replaced: the per-archetype controllers ({@code /epics}, {@code
 * /features}, {@code /tasks}, {@code /tickets}, {@code /ticket-comments}, {@code /campaigns}), both
 * dossier halves and the epic's figures ({@code /epics/{id}/dossier}, {@code
 * /epics/{id}/dossier-assets}, {@code /tickets/{id}/dossier}), the project listings ({@code
 * /projects/{id}/epics}, {@code …/tickets}, {@code …/campaigns}, {@code …/entities}), every {@code
 * /entities} route and {@code /comments/{commentId}}. {@link #DELETED} is every one of those routes,
 * method by method, as the deleted classes declared them; and the five qits-399 removed earlier
 * (qits-frontend 072512f) are kept beside them.
 *
 * <p><b>Each answers 404</b> — what JAX-RS makes of a request no resource matches. Since the whole
 * paths are gone there is no 405 any more: before qits-976, {@code PUT /epics/{id}} answered 405
 * because GET and DELETE still lived on that path. {@code POST /refinements} is a 404 although
 * {@code RefinementController} is still rooted at {@code /refinements}: RESTEasy Reactive matches
 * the class, finds no method on its bare root for any verb, and answers 404 rather than 405
 * (measured, not assumed).
 *
 * <p>The ids name real rows on purpose, so a 404 here cannot be the entity's own "not found" — and
 * the rows are read back through {@code /work} afterwards to show nothing was written: no retitle,
 * no status move, no block, no comment, no page, no dispatch asked of the port and no refinement
 * room opened.
 *
 * <p>The stored figure URL keeps the deleted route's spelling ({@code
 * /epics/{epicId}/dossier-assets/{assetId}/content}) because it is data in page bodies, not an
 * address; its bytes are served by {@code GET /work/{qualifiedId}/dossier-assets/{assetId}/content}.
 *
 * <p>No profile of its own: the default application every door suite shares.
 */
@QuarkusTest
public class RetiredEntityDoorsTest {

  private static final String PROJECT = "retired-doors";
  private static final String REPO = "retired-doors-repo";

  /**
   * One deleted route: the verb and the path under {@code /projects/api}, with {@code {name}}
   * standing for a seeded row (see {@link #expand}).
   */
  record Route(String method, String path) {
    @Override
    public String toString() {
      return method + " " + path;
    }
  }

  /** Every route qits-976 deleted, in the order the deleted controllers declared them. */
  static final List<Route> DELETED =
      List.of(
          // CampaignController
          new Route("GET", "/campaigns/{campaign}"),
          new Route("GET", "/campaigns/{campaign}/progress"),
          new Route("POST", "/campaigns/{campaign}/transition"),
          new Route("POST", "/campaigns/{campaign}/members"),
          new Route("PUT", "/campaigns/{campaign}/members/{membership}/position"),
          new Route("DELETE", "/campaigns/{campaign}/members/{membership}"),
          new Route("PUT", "/campaigns/{campaign}/members/{membership}/condition"),
          new Route("POST", "/campaigns/{campaign}/members/{membership}/criteria/{criterion}/approve"),
          // CommentController
          new Route("PATCH", "/comments/{comment}"),
          new Route("DELETE", "/comments/{comment}"),
          // DossierAssetController
          new Route("POST", "/epics/{epic}/dossier-assets"),
          new Route("GET", "/epics/{epic}/dossier-assets"),
          new Route("GET", "/epics/{epic}/dossier-assets/{asset}/content"),
          // DossierController
          new Route("GET", "/epics/{epic}/dossier"),
          new Route("POST", "/epics/{epic}/dossier"),
          new Route("GET", "/epics/{epic}/dossier/{epicPage}"),
          new Route("PUT", "/epics/{epic}/dossier/{epicPage}"),
          new Route("POST", "/epics/{epic}/dossier/{epicPage}/move"),
          new Route("DELETE", "/epics/{epic}/dossier/{epicPage}"),
          // EntityArchetypesController
          new Route("GET", "/entities/archetypes"),
          new Route("GET", "/entities/archetypes/TICKET/schemas/create"),
          // EntityBlockController
          new Route("POST", "/entities/{ticket}/blocked"),
          // EntityCommentController
          new Route("GET", "/entities/{ticket}/comments"),
          new Route("POST", "/entities/{ticket}/comments"),
          // EntityCreateController
          new Route("POST", "/entities"),
          // EntityPatchController
          new Route("PATCH", "/entities/{ticket}"),
          // EntityReadController
          new Route("GET", "/entities/{ticket}"),
          // EntityStatusController
          new Route("POST", "/entities/{ticket}/status"),
          // EntityTransitionController
          new Route("POST", "/entities/transition"),
          // EpicController
          new Route("GET", "/epics/{epic}"),
          new Route("POST", "/epics/{epic}/transition"),
          new Route("DELETE", "/epics/{epic}"),
          new Route("GET", "/epics/{epic}/features"),
          new Route("POST", "/epics/{epic}/features"),
          new Route("GET", "/epics/{epic}/audit"),
          // FeatureController
          new Route("GET", "/features/{feature}"),
          new Route("PUT", "/features/{feature}"),
          new Route("DELETE", "/features/{feature}"),
          new Route("GET", "/features/{feature}/tasks"),
          new Route("POST", "/features/{feature}/tasks"),
          // ProjectCampaignsController, ProjectEntitiesController, ProjectEpicsController,
          // ProjectTicketsController
          new Route("GET", "/projects/{project}/campaigns"),
          new Route("POST", "/projects/{project}/campaigns"),
          new Route("GET", "/projects/{project}/entities"),
          new Route("GET", "/projects/{project}/epics"),
          new Route("POST", "/projects/{project}/epics"),
          new Route("GET", "/projects/{project}/tickets"),
          new Route("POST", "/projects/{project}/tickets"),
          // TaskController
          new Route("GET", "/tasks/{task}"),
          new Route("PUT", "/tasks/{task}"),
          new Route("DELETE", "/tasks/{task}"),
          // TicketCommentController
          new Route("PUT", "/ticket-comments/{comment}"),
          new Route("DELETE", "/ticket-comments/{comment}"),
          // TicketController
          new Route("GET", "/tickets/{ticket}"),
          new Route("POST", "/tickets/{ticket}/transition"),
          new Route("POST", "/tickets/{ticket}/blocked"),
          new Route("DELETE", "/tickets/{ticket}"),
          new Route("GET", "/tickets/{ticket}/comments"),
          new Route("POST", "/tickets/{ticket}/comments"),
          // TicketDossierController
          new Route("GET", "/tickets/{ticket}/dossier"),
          new Route("POST", "/tickets/{ticket}/dossier"),
          new Route("GET", "/tickets/{ticket}/dossier/{ticketPage}"),
          new Route("PUT", "/tickets/{ticket}/dossier/{ticketPage}"),
          new Route("POST", "/tickets/{ticket}/dossier/{ticketPage}/move"),
          new Route("DELETE", "/tickets/{ticket}/dossier/{ticketPage}"),
          // EntityDispatchController
          new Route("POST", "/entities/{ticket}/dispatch"),
          new Route("GET", "/entities/{ticket}/dispatch"),
          // EntityRefinementController
          new Route("POST", "/entities/{epic}/refinement"),
          new Route("GET", "/entities/{epic}/refinement"));

  /** The five doors qits-399 removed, which the routes above outlived. */
  static final List<Route> RETIRED_EARLIER =
      List.of(
          new Route("PUT", "/epics/{epic}"),
          new Route("PUT", "/tickets/{ticket}"),
          new Route("POST", "/epics/{epic}/dispatch-agent"),
          new Route("POST", "/tickets/{ticket}/dispatch-agent"),
          new Route("POST", "/refinements"));

  @Inject RecordingWorkspaceAgentDispatch dispatch;
  @Inject WorkEntityService entities;
  @Inject EntityCommentService comments;
  @Inject DossierService dossier;

  private Map<String, String> rows;

  @BeforeEach
  void seed() {
    dispatch.reset();
    // The project and the repository a task must name are `domain` rows, written straight through
    // Panache; the entity rows through their own services, which own their transactions.
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (Project.findById(PROJECT) == null) {
                Project project = new Project();
                project.id = PROJECT;
                project.name = PROJECT;
                project.slug = PROJECT;
                project.persist();
              }
              if (Repository.findById(REPO) == null) {
                Repository repository = new Repository();
                repository.id = REPO;
                repository.project = Project.findById(PROJECT);
                repository.mainBranch = "main";
                repository.persist();
              }
            });
    String epic =
        entities.create(Archetype.EPIC, PROJECT, EntityWrite.epic("A plan", null), "seed").entity().id;
    String feature =
        entities
            .create(Archetype.FEATURE, epic, EntityWrite.feature("A part", null, null), "seed")
            .entity()
            .id;
    String task =
        entities
            .create(Archetype.TASK, feature, EntityWrite.task(REPO, "A step", null, null), "seed")
            .entity()
            .id;
    String ticket =
        entities
            .create(
                Archetype.TICKET,
                PROJECT,
                EntityWrite.ticket("A ticket", "something occurs", null, "BUG", null),
                "seed")
            .entity()
            .id;
    String campaign =
        entities
            .create(Archetype.CAMPAIGN, PROJECT, EntityWrite.campaign("An order", null), "seed")
            .entity()
            .id;
    String comment = comments.addComment(ticket, "a note", "seed").id;
    var epicPage = dossier.create(DossierOwner.epic(epic), "Data flow", "as written", "seed");
    var ticketPage =
        dossier.create(DossierOwner.ticket(ticket), "Reproduction", "as written", "seed");
    rows =
        Map.ofEntries(
            Map.entry("project", PROJECT),
            Map.entry("epic", epic),
            Map.entry("feature", feature),
            Map.entry("task", task),
            Map.entry("ticket", ticket),
            Map.entry("campaign", campaign),
            Map.entry("comment", comment),
            Map.entry("epicPage", epicPage.id),
            Map.entry("ticketPage", ticketPage.slug),
            // No membership, criterion or asset row is needed for a path no resource matches.
            Map.entry("membership", "no-such-membership"),
            Map.entry("criterion", "no-such-criterion"),
            Map.entry("asset", "no-such-asset"));
  }

  private static RequestSpecification asAdmin() {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "mallory")
        .header("X-Qits-Roles", "qits:admin");
  }

  /** The route's path under {@code /projects/api} with every {@code {name}} a seeded row. */
  private String expand(Route route) {
    String path = route.path();
    for (Map.Entry<String, String> row : rows.entrySet()) {
      path = path.replace("{" + row.getKey() + "}", row.getValue());
    }
    return "/projects/api" + path;
  }

  /**
   * The request a caller of the deleted route would have sent: a body for every write — one naming
   * a real target, title and status, so that a door still listening would have something to write.
   */
  private Response press(Route route) {
    RequestSpecification request = asAdmin();
    if (!route.method().equals("GET") && !route.method().equals("DELETE")) {
      request =
          request.body(
              Map.of(
                  "title", "Written through a door that is gone",
                  "body", "Written through a door that is gone",
                  "target", "REFINED",
                  "blocked", true,
                  "reason", "Written through a door that is gone",
                  "epicId", rows.get("epic"),
                  "entityId", rows.get("ticket")));
    }
    return request.when().request(route.method(), expand(route));
  }

  @Test
  public void everyDeletedRouteAnswers404AndWritesNothing() {
    assertEquals(68, DELETED.size(), "every route the 24 deleted controllers declared");
    int before = projectWork();
    List<String> wrong = new ArrayList<>();
    List<Route> all = new ArrayList<>(DELETED);
    all.addAll(RETIRED_EARLIER);
    for (Route route : all) {
      int status = press(route).statusCode();
      if (status != 404) {
        wrong.add(route + " answered " + status);
      }
    }
    assertTrue(wrong.isEmpty(), "every deleted route must answer 404: " + wrong);

    // Nothing was written: every row reads back through /work as the seed left it.
    for (String row : List.of("epic", "feature", "task", "ticket", "campaign")) {
      asAdmin()
          .when()
          .get("/projects/api/work/" + rows.get(row))
          .then()
          .statusCode(200)
          .body("status", equalTo("REPORTED"))
          // A feature and a task carry no block at all.
          .body("blocked", anyOf(nullValue(), equalTo(false)));
    }
    asAdmin()
        .when()
        .get("/projects/api/work/" + rows.get("ticket"))
        .then()
        .body("title", equalTo("A ticket"));
    asAdmin()
        .when()
        .get("/projects/api/work/" + rows.get("ticket") + "/comments")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(1))
        .body("entries[0].comment.body", equalTo("a note"));
    asAdmin()
        .when()
        .get("/projects/api/work/" + rows.get("epic") + "/dossier")
        .then()
        .statusCode(200)
        .body("pages.size()", equalTo(1))
        .body("pages[0].body", equalTo("as written"));
    asAdmin()
        .when()
        .get("/projects/api/work/" + rows.get("ticket") + "/dossier")
        .then()
        .statusCode(200)
        .body("pages.size()", equalTo(1))
        .body("pages[0].body", equalTo("as written"));
    asAdmin()
        .when()
        .get("/projects/api/work/" + rows.get("epic") + "/children")
        .then()
        .statusCode(200)
        .body("children.size()", equalTo(1));
    asAdmin()
        .when()
        .get("/projects/api/work/" + rows.get("campaign") + "/members")
        .then()
        .statusCode(200)
        .body("members.size()", equalTo(0));
    asAdmin()
        .when()
        .get("/projects/api/work/" + rows.get("epic") + "/refinement")
        .then()
        .statusCode(200)
        .body("refinement", equalTo(null));
    assertTrue(dispatch.calls().isEmpty(), "no workspace was asked for: " + dispatch.calls());
    assertEquals(before, projectWork(), "no entity was filed or deleted");
  }

  /** How many entities the project's listing holds — the seed's tree plus earlier tests' rows. */
  private int projectWork() {
    return asAdmin()
        .when()
        .get("/projects/api/projects/" + PROJECT + "/work")
        .then()
        .statusCode(200)
        .extract()
        .<List<?>>path("entities")
        .size();
  }
}
