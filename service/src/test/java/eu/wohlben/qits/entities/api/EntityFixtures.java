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
 * Rows for the qits-548 doors' suites ({@code EntitySchemaApiTest}, {@code EntityCreateApiTest},
 * {@code EntityReadApiTest}, {@code EntityStatusApiTest}), written through the per-archetype doors
 * the SPA uses — deliberately not through the doors under test, so a fixture never passes because
 * the thing it sets up is the thing being judged.
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
    return post("/projects/api/projects/" + projectId + "/epics", map("title", "The plan"))
        .path("epic.id");
  }

  static String ticket(String projectId) {
    return post(
            "/projects/api/projects/" + projectId + "/tickets",
            map("title", "The ticket", "impetus", "it occurs", "type", "BUG"))
        .path("ticket.id");
  }

  static String campaign(String projectId) {
    return post("/projects/api/projects/" + projectId + "/campaigns", map("title", "The order"))
        .path("campaign.id");
  }

  static String feature(String epicId) {
    return post("/projects/api/epics/" + epicId + "/features", map("title", "The part"))
        .path("feature.id");
  }

  static String task(String featureId, String repositoryId) {
    return post(
            "/projects/api/features/" + featureId + "/tasks",
            map("repositoryId", repositoryId, "title", "The step"))
        .path("task.id");
  }

  /** The entity's qualified id, read off the merged read. */
  static String qualifiedId(String id) {
    return given().when().get("/projects/api/entities/" + id).then().statusCode(200).extract()
        .path("qualifiedId");
  }

  private static Response post(String path, Map<String, Object> body) {
    Response response = given().contentType(ContentType.JSON).body(body).when().post(path);
    response.then().statusCode(200);
    return response;
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
