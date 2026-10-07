package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * <b>Rows written through the {@code /work} family</b>, for the suites that need an epic, a ticket,
 * a campaign, a feature or a task to stand on and are not about the create itself. Since qits-976
 * deleted the per-archetype and {@code /entities} routes, {@code POST /projects/api/work} is the one
 * create door, and this is that door with the archetype filled in.
 *
 * <p>Every method answers the new row's UUID — the {@code /work} paths take it as readily as a
 * qualified id — and asserts the 201. The {@code caller} overloads hand the request a caller of the
 * test's own (an admin header pair, a forwarded agent); the plain ones run as the {@code %test} dev
 * user.
 */
public final class WorkRequests {

  private WorkRequests() {}

  /** {@code POST /projects/api/work} with {@code body}, as the dev user; the 201's body. */
  public static Response create(Map<String, Object> body) {
    return create(io.restassured.RestAssured::given, body);
  }

  /** {@code POST /projects/api/work} with {@code body}, as {@code caller}; the 201's body. */
  public static Response create(Supplier<RequestSpecification> caller, Map<String, Object> body) {
    Response response =
        caller.get().contentType(ContentType.JSON).body(body).when().post("/projects/api/work");
    response.then().statusCode(201);
    return response;
  }

  public static String epic(String projectId, String title) {
    return epic(io.restassured.RestAssured::given, projectId, title);
  }

  public static String epic(Supplier<RequestSpecification> caller, String projectId, String title) {
    return create(caller, map("archetype", "EPIC", "project", projectId, "title", title)).path("id");
  }

  public static String ticket(String projectId, String title, String ticketType, String impetus) {
    return ticket(io.restassured.RestAssured::given, projectId, title, ticketType, impetus);
  }

  public static String ticket(
      Supplier<RequestSpecification> caller,
      String projectId,
      String title,
      String ticketType,
      String impetus) {
    return create(
            caller,
            map(
                "archetype", "TICKET",
                "project", projectId,
                "title", title,
                "ticketType", ticketType,
                "impetus", impetus))
        .path("id");
  }

  public static String campaign(String projectId, String title) {
    return campaign(io.restassured.RestAssured::given, projectId, title);
  }

  public static String campaign(
      Supplier<RequestSpecification> caller, String projectId, String title) {
    return create(caller, map("archetype", "CAMPAIGN", "project", projectId, "title", title))
        .path("id");
  }

  public static String feature(String epicId, String title) {
    return feature(io.restassured.RestAssured::given, epicId, title);
  }

  public static String feature(Supplier<RequestSpecification> caller, String epicId, String title) {
    return create(caller, map("archetype", "FEATURE", "parent", epicId, "title", title)).path("id");
  }

  public static String task(String featureId, String repositoryId, String title) {
    return task(io.restassured.RestAssured::given, featureId, repositoryId, title);
  }

  public static String task(
      Supplier<RequestSpecification> caller,
      String featureId,
      String repositoryId,
      String title) {
    return create(
            caller,
            map(
                "archetype", "TASK",
                "parent", featureId,
                "repositoryId", repositoryId,
                "title", title))
        .path("id");
  }

  /** {@code GET /projects/api/work/{id}} as the dev user, asserted 200. */
  public static Response read(String id) {
    Response response = given().when().get("/projects/api/work/" + id);
    response.then().statusCode(200);
    return response;
  }

  /** The entity's qualified id, read off {@code GET /work/{id}}. */
  public static String qualifiedId(String id) {
    return read(id).path("qualifiedId");
  }

  /** {@code POST /projects/api/work/{id}/status}, as the dev user; the response, unasserted. */
  public static Response status(String id, String target) {
    return status(io.restassured.RestAssured::given, id, target);
  }

  public static Response status(Supplier<RequestSpecification> caller, String id, String target) {
    return caller
        .get()
        .contentType(ContentType.JSON)
        .body(Map.of("target", target))
        .when()
        .post("/projects/api/work/" + id + "/status");
  }

  /** A map that, unlike {@link Map#of}, keeps its order and admits a null value. */
  public static Map<String, Object> map(Object... keysAndValues) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      out.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return out;
  }
}
