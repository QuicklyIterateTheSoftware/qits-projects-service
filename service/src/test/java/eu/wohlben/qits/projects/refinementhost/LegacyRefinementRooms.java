package eu.wohlben.qits.projects.refinementhost;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.entities.control.EntityDispatchService;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.GitMirrorRegistry;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * A legacy container room for the suites that still pin the container machinery (until qits-1316
 * removes it): since epic qits-770 the open makes a session room, so this opens one through the
 * real door and turns it into what an open made before V57 — the {@code refining/<slug>} branch cut
 * on the wrapper and named on the row, the parent its default branch, no session demand. Test scope
 * only.
 */
@ApplicationScoped
public class LegacyRefinementRooms {

  @Inject RefinementRepository store;

  @Inject RepositoryService repositories;

  @Inject GitMirrorRegistry mirrors;

  @Inject EntityDispatchService entities;

  @Inject RefinementSessions sessions;

  @Inject RefinementService service;

  /**
   * {@code POST /work/{entityId}/refinement}; a 200 is turned into a legacy container room and the
   * room answered as {@code GET /work/{entityId}/refinement} reads it, anything else is answered as
   * it came.
   */
  public Response open(String entityId) {
    Response opened =
        given()
            .contentType(ContentType.JSON)
            .body(java.util.Map.of())
            .when()
            .post("/projects/api/work/" + entityId + "/refinement");
    if (opened.statusCode() != 200) {
      return opened;
    }
    Number id = opened.path("refinement.id");
    asContainerRoom(id.longValue());
    return given().when().get("/projects/api/work/" + entityId + "/refinement");
  }

  /** Turn the session room {@code id} into a legacy container room; idempotent. */
  public void asContainerRoom(long id) {
    Refinement room = QuarkusTransaction.requiringNew().call(() -> store.findById(id));
    if (room.containerRoom()) {
      return;
    }
    WorkEntity entity = entities.fresh(room.entityId);
    Repository wrapper =
        QuarkusTransaction.requiringNew().call(() -> repositories.get(room.repositoryId));
    String branch = "refining/" + entity.slug;
    String parent = wrapper.mainBranch == null ? "main" : wrapper.mainBranch;
    RepoMirror mirror = mirrors.of(wrapper.id);
    if (mirror.remoteBranchSha(branch).isEmpty()) {
      mirror.refresh();
      mirror.createBranch(branch, parent);
    }
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                store.update(
                    "branch = ?1, parent = ?2, lastDemandAt = null where id = ?3",
                    branch,
                    parent,
                    id));
    sessions.push(room.projectId);
    // The open's own read, as the pre-V57 open answered it: it schedules the first drift pass.
    service.view(QuarkusTransaction.requiringNew().call(() -> store.findById(id)));
  }
}
