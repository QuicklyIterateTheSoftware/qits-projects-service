package eu.wohlben.qits.projects.refinementhost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.api.TestCriteria;
import eu.wohlben.qits.entities.api.WorkRequests;
import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A refinement room's own token (qits-1482): minted at the open as kind {@code refinement} for the
 * row, claiming its project and its entity; an open that cannot mint still opens; the discard — and
 * so an entity's resolution — revokes it before the row goes; and the reconcile keeps tokens and
 * rows in step both ways. Against {@link FakeRefinementTokens}, the suite's qits-idp.
 */
@QuarkusTest
public class RefinementTokenTest {

  @Inject FakeRefinementTokens tokens;
  @Inject FakeRefinementRuntime runtime;
  @Inject RefinementService service;
  @Inject RefinementTokenReconcile reconcile;
  @Inject RefinementRepository rows;

  @BeforeEach
  void reset() {
    tokens.reset();
    runtime.reset();
  }

  // ---- fixtures ------------------------------------------------------------------------------

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private String createEpic(String projectId, String title) {
    return TestCriteria.give(
        WorkRequests.create(
                WorkRequests.map(
                    "archetype", "EPIC",
                    "project", projectId,
                    "title", title,
                    "description", "A draft."))
            .path("id"));
  }

  private long open(String epicId) {
    Number id =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of())
            .when()
            .post("/projects/api/work/" + epicId + "/refinement")
            .then()
            .statusCode(200)
            .extract()
            .path("refinement.id");
    return id.longValue();
  }

  private Refinement row(long id) {
    return QuarkusTransaction.requiringNew().call(() -> rows.findByIdOptional(id)).orElse(null);
  }

  // ---- mint ----------------------------------------------------------------------------------

  @Test
  public void openingARoomMintsItsTokenBoundToItsEntity() {
    String projectId = createProject("Token Mint");
    String epicId = createEpic(projectId, "Minted Epic");

    long id = open(epicId);

    List<FakeRefinementTokens.Mint> mints = tokens.mintsFor(id);
    assertEquals(1, mints.size());
    assertEquals(projectId, mints.get(0).projectId());
    assertEquals(epicId, mints.get(0).entityId());
    Refinement stored = row(id);
    assertEquals(mints.get(0).tokenId(), stored.tokenId);
    assertEquals("qits_tok_" + stored.tokenId, stored.tokenValue);
    assertTrue(stored.tokenSubject.startsWith("tok-"));
    assertTrue(tokens.isLive(stored.tokenId));

    // A second open answers the same room and mints nothing more.
    assertEquals(id, open(epicId));
    assertEquals(1, tokens.mintsFor(id).size());

    // The credential the session push will carry: keyed by the entity id.
    RefinementTokens.SessionCredential credential = service.credentialOf(id).orElseThrow();
    assertEquals(epicId, credential.key());
    assertEquals(stored.tokenValue, credential.token());
    assertEquals(stored.tokenSubject, credential.subject());
    assertFalse(credential.toString().contains(stored.tokenValue), "the value is never logged");
  }

  @Test
  public void anOpenThatCannotMintStillOpensAndTheReconcileMintsLater() {
    String projectId = createProject("Token Mint Fails");
    String epicId = createEpic(projectId, "Unminted Epic");
    tokens.failMint(true);

    long id = open(epicId);

    assertNull(row(id).tokenId, "the room opened with no token");
    assertEquals(Optional.empty(), service.credentialOf(id));

    tokens.failMint(false);
    RefinementTokenReconcile.Pass pass = reconcile.reconcile(Instant.now());

    assertTrue(pass.minted() >= 1);
    assertEquals(1, tokens.mintsFor(id).size());
    assertNotNull(row(id).tokenId);
    assertTrue(tokens.isLive(row(id).tokenId));
  }

  @Test
  public void aLaterOpenRetriesAMissingToken() {
    String projectId = createProject("Token Reopen");
    String epicId = createEpic(projectId, "Reopened Epic");
    tokens.failMint(true);
    long id = open(epicId);
    tokens.failMint(false);

    assertEquals(id, open(epicId));

    assertNotNull(row(id).tokenId);
    assertEquals(1, tokens.mintsFor(id).size());
  }

  // ---- revoke --------------------------------------------------------------------------------

  @Test
  public void discardingARoomRevokesItsToken() {
    String projectId = createProject("Token Discard");
    String epicId = createEpic(projectId, "Discarded Epic");
    long id = open(epicId);
    String tokenId = row(id).tokenId;

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/refinements/" + id + "/discard")
        .then()
        .statusCode(200);

    assertEquals(List.of(tokenId), tokens.revoked());
    assertFalse(tokens.isLive(tokenId));
    assertNull(row(id));
  }

  @Test
  public void resolvingTheEntityRevokesItsRoomsToken() {
    String projectId = createProject("Token Resolve");
    String epicId = createEpic(projectId, "Resolved Epic");
    long id = open(epicId);
    String tokenId = row(id).tokenId;

    given()
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev"))
        .contentType(ContentType.JSON)
        .body(WorkRequests.statusBody("DROPPED"))
        .when()
        .post("/projects/api/work/" + epicId + "/status")
        .then()
        .statusCode(200);

    assertEquals(List.of(tokenId), tokens.revoked());
    assertNull(row(id));
  }

  // ---- reconcile -----------------------------------------------------------------------------

  @Test
  public void theReconcileRevokesTokensNoRowClaimsAndKeepsTheRest() {
    String projectId = createProject("Token Reconcile");
    long id = open(createEpic(projectId, "Claimed Epic"));
    String claimed = row(id).tokenId;
    Instant now = Instant.now();
    tokens.plant(new RefinementTokens.Live("orphan", "999999999", now.minusSeconds(3600)));
    tokens.plant(new RefinementTokens.Live("stale-twin", Long.toString(id), now.minusSeconds(3600)));
    tokens.plant(new RefinementTokens.Live("young", "999999998", now.minusSeconds(10)));

    RefinementTokenReconcile.Pass pass = reconcile.reconcile(now);

    assertEquals(2, pass.revoked());
    assertFalse(tokens.isLive("orphan"), "a token naming no row is revoked");
    assertFalse(tokens.isLive("stale-twin"), "a second token of a row that holds another is revoked");
    assertTrue(tokens.isLive("young"), "a token inside the mint grace is left for its row");
    assertTrue(tokens.isLive(claimed), "the row's own token is kept");
  }

  @Test
  public void aFailedListingReapsNothing() {
    tokens.plant(
        new RefinementTokens.Live("orphan", "999999999", Instant.now().minusSeconds(3600)));
    tokens.failList(true);

    assertEquals(0, reconcile.reconcile(Instant.now()).revoked());

    assertTrue(tokens.isLive("orphan"));
  }
}
