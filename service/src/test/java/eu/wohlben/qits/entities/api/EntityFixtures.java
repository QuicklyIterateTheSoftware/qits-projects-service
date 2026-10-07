package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rows for the work family's suites: a project, a repository, and the entities written through
 * {@code POST /projects/api/work} ({@link WorkRequests}) — since qits-976 the one create door. An
 * epic and a ticket are given {@link TestCriteria#CRITERIA}, so a suite not about the criteria gate
 * can walk them into REFINED.
 */
final class EntityFixtures {

  private EntityFixtures() {}

  /** A fresh project, as {@code {id, slug}}. */
  record Project(String id, String slug) {}

  static Project project(String name) {
    Response created =
        given()
            .contentType(ContentType.JSON)
            .body(
                new ProjectController.CreateProjectRequest(
                    name, null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects");
    created.then().statusCode(200);
    return new Project(created.path("project.id"), created.path("project.slug"));
  }

  /** A repository of {@code projectId}, which a task must name. */
  static String repository(String projectId) {
    String fixtureUrl;
    try {
      fixtureUrl = GitFixtures.path("testing-repo.git");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRepositoryRequest(
                fixtureUrl, null, RepositoryArchetype.SERVICE, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(200)
        .extract()
        .path("repository.id");
  }

  static String epic(String projectId) {
    return TestCriteria.give(WorkRequests.epic(projectId, "The plan"));
  }

  static String ticket(String projectId) {
    return TestCriteria.give(WorkRequests.ticket(projectId, "The ticket", "BUG", "it occurs"));
  }

  static String campaign(String projectId) {
    return WorkRequests.campaign(projectId, "The order");
  }

  static String feature(String epicId) {
    return WorkRequests.feature(epicId, "The part");
  }

  static String task(String featureId, String repositoryId) {
    return WorkRequests.task(featureId, repositoryId, "The step");
  }

  /** The entity's qualified id, read off {@code GET /work/{id}}. */
  static String qualifiedId(String id) {
    return WorkRequests.qualifiedId(id);
  }

  /** A map that, unlike {@link Map#of}, keeps its order and admits a null value. */
  static Map<String, Object> map(Object... keysAndValues) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      out.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return out;
  }
}
