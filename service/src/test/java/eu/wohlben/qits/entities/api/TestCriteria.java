package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;

import java.util.List;
import java.util.Map;

/**
 * Acceptance criteria for a fixture epic or ticket (qits-887): what lets it pass the {@code
 * ACCEPTANCE_CRITERIA} gate into REFINED and READY_FOR_DEV, for a test that is not about the gate.
 * Written through the merge patch, as any caller writes them, or handed to a service-level write.
 */
public final class TestCriteria {

  /** One criterion, obeying every item rule. */
  public static final List<String> CRITERIA = List.of("It does what it says.");

  private TestCriteria() {}

  /** Gives entity {@code id} {@link #CRITERIA} through {@code PATCH /work/{id}}; answers the id. */
  public static String give(String id) {
    given()
        .contentType("application/merge-patch+json")
        .body(Map.of("acceptanceCriteria", CRITERIA))
        .when()
        .patch("/projects/api/work/" + id)
        .then()
        .statusCode(200);
    return id;
  }
}
