package eu.wohlben.qits.epics.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;

import eu.wohlben.qits.epics.migration.VerificationReport;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

/**
 * The route and the CDI bridge behind it, which the pure {@code MigrationVerificationTest} one
 * module down cannot reach: that the persistence unit hands out a usable connection outside any
 * caller's transaction, that twenty statements of hand-written SQL actually parse against the schema
 * Flyway builds, and that the record tree serialises.
 *
 * <p>None of that is provable from the epics module — it drives a raw JDBC connection — and none of
 * it is a hypothetical: a comparison that will not unwrap a session, or one statement with a column
 * name that disagrees with a migration, would be a 500 on the one door an operator presses before
 * dropping four tables, with every other suite green.
 *
 * <p><b>A plain {@code @QuarkusTest} with no {@code @TestProfile}</b>, deliberately: a profile is a
 * second whole Quarkus application at roughly 125 MB of retained metaspace inside a 4 GB CI step,
 * and nothing here needs one. It shares the default application with every other REST suite.
 *
 * <p><b>The estate it runs against is empty of old rows and that is the assertion, not a
 * limitation.</b> Nothing under {@code src/main} has written {@code Epic}, {@code Ticket}, {@code
 * Feature} or {@code Task} for several commits, so every suite in this repository leaves those four
 * tables empty while filling {@code entity} through the ordinary services — which is precisely the
 * shape the three findings are about. A door written with the reverse assertion would answer
 * DISCREPANCIES here, on a database where nothing whatever is wrong.
 */
@QuarkusTest
class MigrationVerificationApiTest {

  private static final String PATH = "/projects/api/entities/migration-verification";

  @Test
  void theDoorAnswersCleanAndCarriesItsScopeInWords() {
    given()
        .when()
        .get(PATH)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("verdict", equalTo(VerificationReport.CLEAN))
        .body("discrepancies", equalTo(0))
        .body("scope.direction", containsString("FORWARD ONLY"))
        .body("scope.direction", containsString("REVERSE"))
        .body("scope.deCollidedSlugs", containsString("re-slugged"))
        .body("scope.createdSinceCutover", containsString("entity >= old"))
        .body("scope.notChecked", not(hasItem(equalTo(null))))
        // Every category is present on a clean answer, each saying what it compared. That is the
        // whole shape: a verdict with an empty body is what a bad verification looks like.
        .body(
            "categories.name",
            hasItems(
                "archetype-census",
                "archetype-counts",
                "missing-entities",
                "property-round-trip",
                "membership-parents",
                "membership-order",
                "orphaned-memberships",
                "rootless-entities",
                "dangling-owner-references",
                "dangling-audit-references",
                "work-branches",
                "entities-created-since-the-cutover",
                "expected-deleted-since-the-cutover",
                "expected-de-collided-slugs",
                "expected-changed-since-the-cutover",
                "expected-reparented-since-the-cutover"))
        .body("categories.compared", not(hasItem(equalTo(null))))
        .body("categories.question", not(hasItem(equalTo(null))));
  }
}
