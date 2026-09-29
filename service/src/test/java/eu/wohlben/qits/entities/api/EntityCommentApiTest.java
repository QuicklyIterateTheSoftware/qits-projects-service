package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.AuditService;
import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The thread every entity has (qits-551), over the wire: {@code GET/POST
 * /projects/api/entities/{id}/comments}, {@code PATCH/DELETE /projects/api/comments/{commentId}}.
 *
 * <p>What this class is for: the PATCH writes the text and nothing else (the author and the
 * creation time are the byte-identical strings they were), its refusals, the qualified id being a
 * real address, and the delete of an epic taking its descendants' remarks with it — each with a
 * DELETE audit row, because a remark that vanished through the FK cascade never happened as far as
 * the log is concerned. The binding of an agent to its project is {@link EntityAgentBoundsTest}'s,
 * and the wire shapes are pinned in {@code EntityRoutesGoldenTest}.
 */
@QuarkusTest
class EntityCommentApiTest {

  @Inject WorkEntityService entities;
  @Inject EntityCommentService comments;
  @Inject AuditService audit;

  /** A project and a repository of its own per test, so the qualified ids never collide. */
  private String projectSlug;

  private String repositoryId;

  private WorkEntity epic;
  private WorkEntity feature;
  private WorkEntity task;

  @BeforeEach
  void seed() {
    projectSlug = "comments-" + UUID.randomUUID().toString().substring(0, 8);
    repositoryId = projectSlug + "-repo";
    // Domain rows straight through Panache, as EntityAgentBoundsTest writes its pair; the entity
    // rows through their own service, which owns its transactions.
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectSlug;
              project.name = projectSlug;
              project.slug = projectSlug;
              project.persist();
              Repository repository = new Repository();
              repository.id = repositoryId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
    epic =
        entities
            .create(Archetype.EPIC, projectSlug, EntityWrite.epic("The plan", "the pitch"), "t")
            .entity();
    feature =
        entities
            .create(Archetype.FEATURE, epic.id, EntityWrite.feature("A part", null, null), "t")
            .entity();
    task =
        entities
            .create(
                Archetype.TASK, feature.id, EntityWrite.task(repositoryId, "A step", null, null), "t")
            .entity();
  }

  private static RequestSpecification as(String user) {
    return given().header("X-Qits-User", user).header("X-Qits-Roles", "qits:admin");
  }

  private static String comment(String user, String entityId, String body) {
    return as(user)
        .contentType(ContentType.JSON)
        .body(Map.of("body", body))
        .when()
        .post("/projects/api/entities/" + entityId + "/comments")
        .then()
        .statusCode(200)
        .body("comment.entityId", equalTo(entityId))
        .body("comment.author", equalTo(user))
        .extract()
        .path("comment.id");
  }

  private static ValidatableResponse patch(String commentId, String json) {
    return as("bob")
        .contentType(CommentController.MERGE_PATCH_JSON)
        .body(json)
        .when()
        .patch("/projects/api/comments/" + commentId)
        .then();
  }

  /** The one remark on {@code entityId}, as the listing draws it. */
  private static Map<String, Object> onlyRemarkOn(String entityId) {
    List<Map<String, Object>> entries =
        given()
            .when()
            .get("/projects/api/entities/" + entityId + "/comments")
            .then()
            .statusCode(200)
            .extract()
            .path("entries.comment");
    assertEquals(1, entries.size(), entries.toString());
    return entries.get(0);
  }

  // --- the PATCH writes the text and nothing else ------------------------------------------------

  @Test
  void aPatchRewritesTheBodyAndLeavesTheAuthorAndCreationByteIdentical() {
    String id = comment("alice", epic.id, "first words");
    Map<String, Object> before = onlyRemarkOn(epic.id);

    patch(id, "{\"body\":\"better words\"}")
        .statusCode(200)
        .body("comment.id", equalTo(id))
        .body("comment.body", equalTo("better words"))
        .body("comment.entityId", equalTo(epic.id))
        // Who wrote it is not who changed it: bob's edit is the audit log's fact, not the row's.
        .body("comment.author", equalTo("alice"));

    Map<String, Object> after = onlyRemarkOn(epic.id);
    assertEquals("better words", after.get("body"));
    assertEquals(before.get("author"), after.get("author"));
    assertEquals(before.get("createdAt"), after.get("createdAt"), "createdAt is never rewritten");
    assertEquals(before.get("entityId"), after.get("entityId"));
    assertNotEquals(before.get("updatedAt"), after.get("updatedAt"), "an edit moves updatedAt");

    var rows =
        audit.listForEpic(epic.id).stream()
            .filter(e -> e.entityType == AuditEntityType.COMMENT && id.equals(e.entityId))
            .toList();
    assertTrue(
        rows.stream()
            .anyMatch(e -> e.operation == AuditOperation.UPDATE && "bob".equals(e.changedBy)),
        "the edit is audited under the editor, on the epic's history: " + rows);
  }

  @Test
  void plainJsonIsAcceptedBesideTheMergePatchType() {
    String id = comment("alice", task.id, "on the task");
    as("bob")
        .contentType(ContentType.JSON)
        .body("{\"body\":\"on the task, again\"}")
        .when()
        .patch("/projects/api/comments/" + id)
        .then()
        .statusCode(200)
        .body("comment.body", equalTo("on the task, again"));
  }

  @Test
  void aPatchThatClearsBlanksOrNamesAnythingElseIsA400AndWritesNothing() {
    String id = comment("alice", feature.id, "kept as it is");

    patch(id, "{\"body\":null}")
        .statusCode(400)
        .body("message", containsString("body cannot be cleared"));
    patch(id, "{\"body\":\"  \"}").statusCode(400).body("message", containsString("blank"));
    patch(id, "{\"body\":7}").statusCode(400).body("message", containsString("must be a string"));
    patch(id, "{\"author\":\"mallory\"}")
        .statusCode(400)
        .body("message", containsString("author is server-owned"));
    patch(id, "{\"body\":\"x\",\"colour\":\"red\"}")
        .statusCode(400)
        .body("message", containsString("unknown property: colour"));
    patch(id, "{}").statusCode(400).body("message", containsString("at least one property"));
    patch(id, "[]").statusCode(400).body("message", containsString("JSON object"));

    Map<String, Object> after = onlyRemarkOn(feature.id);
    assertEquals("kept as it is", after.get("body"));
    assertEquals("alice", after.get("author"));
  }

  @Test
  void anUnknownCommentIsA404() {
    patch("no-such-comment", "{\"body\":\"x\"}").statusCode(404);
    given().when().delete("/projects/api/comments/no-such-comment").then().statusCode(404);
  }

  // --- the qualified id is an address ------------------------------------------------------------

  @Test
  void aQualifiedIdResolvesToTheSameThreadAsTheUuid() {
    String qualified = QualifiedEntityIds.render(projectSlug, task.number);
    String id =
        as("alice")
            .contentType(ContentType.JSON)
            .body(Map.of("body", "by its qualified id"))
            .when()
            .post("/projects/api/entities/" + qualified + "/comments")
            .then()
            .statusCode(200)
            .body("comment.entityId", equalTo(task.id))
            .extract()
            .path("comment.id");

    assertEquals(id, onlyRemarkOn(task.id).get("id"));
    assertEquals(id, onlyRemarkOn(qualified).get("id"));

    given()
        .when()
        .get("/projects/api/entities/" + projectSlug + "-999999/comments")
        .then()
        .statusCode(404);
    given()
        .when()
        .get("/projects/api/entities/no-such-project-1/comments")
        .then()
        .statusCode(404);
  }

  // --- a ticket's thread is the same thread on both doors -----------------------------------------

  @Test
  void aTicketsRemarkReadsTheSameOnTheOldDoorAndTheNew() {
    WorkEntity ticket =
        entities
            .create(
                Archetype.TICKET,
                projectSlug,
                EntityWrite.ticket("A bug", "it occurs", null, "BUG", null),
                "t")
            .entity();
    String id = comment("alice", ticket.id, "seen it");

    given()
        .when()
        .get("/projects/api/tickets/" + ticket.id + "/comments")
        .then()
        .statusCode(200)
        .body("entries[0].comment.id", equalTo(id))
        // The old door keeps its shape: the entity id spelled ticketId.
        .body("entries[0].comment.ticketId", equalTo(ticket.id));
  }

  // --- a delete takes its subtree's remarks with it, audited --------------------------------------

  @Test
  void deletingAnEpicRemovesItsFeaturesAndTasksRemarksEachWithAnAuditRow() {
    String onEpic = comment("alice", epic.id, "on the epic");
    String onFeature = comment("alice", feature.id, "on the feature");
    String onTask = comment("alice", task.id, "on the task");

    given().when().delete("/projects/api/epics/" + epic.id).then().statusCode(200);

    for (String gone : List.of(onEpic, onFeature, onTask)) {
      assertThrows(NotFoundException.class, () -> comments.getComment(gone), gone);
    }
    Set<String> deleted =
        audit.listForEpic(epic.id).stream()
            .filter(e -> e.entityType == AuditEntityType.COMMENT)
            .filter(e -> e.operation == AuditOperation.DELETE)
            .map(e -> e.entityId)
            .collect(Collectors.toSet());
    assertEquals(
        Set.of(onEpic, onFeature, onTask),
        deleted,
        "every remark in the subtree leaves a DELETE row under the epic's key");
  }

  @Test
  void deletingATaskRemovesItsRemarksUnderTheEpicsKey() {
    String onTask = comment("alice", task.id, "on the task");

    given().when().delete("/projects/api/tasks/" + task.id).then().statusCode(200);

    assertThrows(NotFoundException.class, () -> comments.getComment(onTask));
    assertTrue(
        audit.listForEpic(epic.id).stream()
            .anyMatch(
                e ->
                    e.entityType == AuditEntityType.COMMENT
                        && e.operation == AuditOperation.DELETE
                        && onTask.equals(e.entityId)),
        "a task's remark is part of its epic's history");
  }
}
