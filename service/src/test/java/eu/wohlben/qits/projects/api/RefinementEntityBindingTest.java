package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.api.WorkRequests;
import eu.wohlben.qits.entities.control.DossierAssetService;
import eu.wohlben.qits.entities.error.ForbiddenException;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.refinementhost.FakeRefinementRuntime;
import eu.wohlben.qits.projects.refinementhost.RefinementDesigns;
import eu.wohlben.qits.projects.refinementhost.RefinementPromptDrafts;
import eu.wohlben.qits.projects.refinementhost.RefinementService;
import eu.wohlben.qits.projects.security.AgentTokens;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * A refinement room's own token reaches only its own entity's refinement (qits-1482 §4): a bearer
 * carrying an {@code entity} claim is refused (403) on every other room's doors, sees only its own
 * row in the project's listing, and reaches its own room as before; a bearer with no such claim (a
 * workspace agent, a person) is judged exactly as before.
 *
 * <p><b>Driven directly, as {@code EntityAgentBoundsTest} explains:</b> the binding reads the
 * token's claims, which a {@code @QuarkusTest} cannot put in front of this service over HTTP (the
 * {@code %test} dev user wins), so the controllers are built by hand with identities from {@link
 * AgentTokens} over real rows and the real beans. The roles half — dispatch, which a refinement
 * token's {@code qits:agent} cannot open — is a forwarded header and is driven over HTTP.
 */
@QuarkusTest
public class RefinementEntityBindingTest {

  private static final String OWN_DRAFT = "{\"text\":\"own draft\"}";
  private static final String FOREIGN_DRAFT = "{\"text\":\"foreign draft\"}";

  @Inject RefinementService refinements;
  @Inject RefinementDesigns designs;
  @Inject RefinementPromptDrafts drafts;
  @Inject DossierAssetService assets;
  @Inject EntityIdResolver ids;
  @Inject FakeRefinementRuntime runtime;

  @BeforeEach
  void reset() {
    runtime.reset();
  }

  /** Two rooms in one project, and the project. */
  private record Rooms(String projectId, String entityA, long roomA, String entityB, long roomB) {}

  private Rooms seed(String name) {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new ProjectController.CreateProjectRequest(
                    name, null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(200)
            .extract()
            .path("project.id");
    String a = WorkRequests.epic(projectId, name + " A");
    String b = WorkRequests.epic(projectId, name + " B");
    return new Rooms(projectId, a, refinements.findOrCreate(a).id, b, refinements.findOrCreate(b).id);
  }

  /** A refinement room's token: {@code qits:agent}, its project and its entity. */
  private static SecurityIdentity room(String projectId, String entityId) {
    return AgentTokens.token(
        Map.of("sub", "tok-room-" + entityId, "project", projectId, "entity", entityId),
        "qits:agent");
  }

  /** A workspace agent of the project: no entity claim, so bound to the project alone. */
  private static SecurityIdentity workspaceAgent(String projectId) {
    return AgentTokens.token(Map.of("sub", "tok-agent", "project", projectId), "qits:agent");
  }

  private static void refused(Executable door) {
    ForbiddenException refusal = assertThrows(ForbiddenException.class, door);
    assertEquals(403, refusal.statusCode());
    assertTrue(refusal.getMessage().contains("bound to entity"), refusal.getMessage());
  }

  // ---- the doors, built by hand --------------------------------------------------------------

  private RefinementDesignController designDoor(SecurityIdentity caller) {
    RefinementDesignController door = new RefinementDesignController();
    door.refinements = refinements;
    door.designs = designs;
    door.assets = assets;
    door.identity = caller;
    return door;
  }

  private RefinementPromptDraftController draftDoor(SecurityIdentity caller) {
    RefinementPromptDraftController door = new RefinementPromptDraftController();
    door.refinements = refinements;
    door.drafts = drafts;
    door.identity = caller;
    return door;
  }

  private WorkRefinementController workDoor(SecurityIdentity caller) {
    WorkRefinementController door = new WorkRefinementController();
    door.ids = ids;
    door.refinements = refinements;
    door.identity = caller;
    return door;
  }

  private ProjectRefinementsController listDoor(SecurityIdentity caller) {
    ProjectRefinementsController door = new ProjectRefinementsController();
    door.refinements = refinements;
    door.identity = caller;
    return door;
  }

  private RefinementController roomDoor(SecurityIdentity caller) {
    RefinementController door = new RefinementController();
    door.refinements = refinements;
    door.identity = caller;
    return door;
  }

  private static RefinementDesignController.NewDesign design(String title) {
    return new RefinementDesignController.NewDesign(
        title, "<!doctype html><html><body>x</body></html>", "/x", false);
  }

  // ---- designs -------------------------------------------------------------------------------

  @Test
  public void aRoomsTokenWritesAndReadsOnlyItsOwnRoomsDesigns() {
    Rooms rooms = seed("Bind Designs");
    SecurityIdentity a = room(rooms.projectId(), rooms.entityA());

    assertEquals(201, designDoor(a).add(rooms.roomA(), design("Own")).getStatus());
    assertEquals(1, designDoor(a).list(rooms.roomA()).designs().size());

    refused(() -> designDoor(a).add(rooms.roomB(), design("Foreign")));
    refused(() -> designDoor(a).list(rooms.roomB()));
    assertTrue(designDoor(workspaceAgent(rooms.projectId())).list(rooms.roomB()).designs().isEmpty());

    // An id naming nothing is still the 404 it always was, never a 403.
    assertThrows(NotFoundException.class, () -> designDoor(a).list(Long.MAX_VALUE));
  }

  // ---- prompt draft --------------------------------------------------------------------------

  @Test
  public void aRoomsTokenReadsOnlyItsOwnRoomsPromptDraft() {
    Rooms rooms = seed("Bind Draft");
    drafts.save(rooms.roomA(), OWN_DRAFT, null);
    drafts.save(rooms.roomB(), FOREIGN_DRAFT, null);
    SecurityIdentity a = room(rooms.projectId(), rooms.entityA());

    assertEquals(OWN_DRAFT, draftDoor(a).get(rooms.roomA()).draft().content());
    refused(() -> draftDoor(a).get(rooms.roomB()));
    assertEquals(
        FOREIGN_DRAFT,
        draftDoor(workspaceAgent(rooms.projectId())).get(rooms.roomB()).draft().content());
  }

  // ---- the room itself -----------------------------------------------------------------------

  @Test
  public void aRoomsTokenReadsOnlyItsOwnRoom() {
    Rooms rooms = seed("Bind Room");
    SecurityIdentity a = room(rooms.projectId(), rooms.entityA());

    assertEquals(rooms.roomA(), roomDoor(a).get(rooms.roomA()).refinement().id());
    refused(() -> roomDoor(a).get(rooms.roomB()));
    refused(() -> roomDoor(a).activeProcess(rooms.roomB()));
    refused(() -> roomDoor(a).discard(rooms.roomB()));
    assertNotNull(refinements.findByEntity(rooms.entityB()).orElse(null), "nothing was discarded");
  }

  // ---- POST|GET /work/{qualifiedId}/refinement -----------------------------------------------

  @Test
  public void theWorkRefinementDoorAnswersOnlyTheTokensOwnEntity() {
    Rooms rooms = seed("Bind Work");
    SecurityIdentity a = room(rooms.projectId(), rooms.entityA());
    String entityC = WorkRequests.epic(rooms.projectId(), "Bind Work C");

    assertEquals(rooms.roomA(), workDoor(a).find(rooms.entityA()).refinement().id());
    assertEquals(rooms.roomA(), workDoor(a).open(rooms.entityA()).refinement().id());
    refused(() -> workDoor(a).find(rooms.entityB()));
    refused(() -> workDoor(a).open(entityC));
    assertTrue(refinements.findByEntity(entityC).isEmpty(), "a refused open creates no room");

    assertEquals(
        rooms.roomB(),
        workDoor(workspaceAgent(rooms.projectId())).find(rooms.entityB()).refinement().id());
  }

  // ---- the project's listing -----------------------------------------------------------------

  @Test
  public void aRoomsTokenListsOnlyItsOwnRoomAndOthersListEveryRoom() {
    Rooms rooms = seed("Bind List");

    List<Long> seen =
        listDoor(room(rooms.projectId(), rooms.entityA()))
            .list(rooms.projectId())
            .refinements()
            .stream()
            .map(RefinementDto::id)
            .toList();
    assertEquals(List.of(rooms.roomA()), seen);

    List<Long> all =
        listDoor(workspaceAgent(rooms.projectId()))
            .list(rooms.projectId())
            .refinements()
            .stream()
            .map(RefinementDto::id)
            .toList();
    assertEquals(List.of(rooms.roomA(), rooms.roomB()), all);
  }

  // ---- dispatch: the role a refinement token lacks -------------------------------------------

  /**
   * A refinement token holds {@code qits:agent} and nothing else (qits-idp's {@code CommissionRoles}
   * for kind {@code refinement}); the dispatch door admits {@code qits:admin}, {@code
   * qits:admin-agent} and {@code qits:desk}. Roles arrive as a forwarded header here, which is
   * exactly the half the role check reads; approve is {@code security/RefinementTokenRolesTest}.
   */
  @Test
  public void aRefinementTokensRoleIsRefusedAtTheDispatchDoor() {
    Rooms rooms = seed("Bind Dispatch");

    given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "tok-room")
        .header("X-Qits-Roles", "qits:agent")
        .body(Map.of("mode", "FLOW"))
        .when()
        .post("/projects/api/work/" + rooms.entityA() + "/dispatch")
        .then()
        .statusCode(403);
  }
}
