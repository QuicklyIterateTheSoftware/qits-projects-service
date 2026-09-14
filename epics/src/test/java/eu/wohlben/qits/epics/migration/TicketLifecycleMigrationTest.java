package eu.wohlben.qits.epics.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.testdb.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * What V7 does to the rows that were already there — the half of a migration no other test can
 * reach, because every suite around it starts from an empty database and therefore proves only that
 * the DDL applies.
 *
 * <p>It is a plain JUnit test rather than a {@code @QuarkusTest}: the whole point is to stand at
 * <b>V6</b>, write rows in the old vocabulary, and only then run V7 — which means driving Flyway
 * directly, on a database of its own, while the application's own datasource is migrated to head at
 * boot like always. The embedded postgres is the module's, so this costs no second server.
 */
class TicketLifecycleMigrationTest {

  /** Its own database, for the reason every (module, datasource) pair here has one. */
  private static final String DATABASE = "qp_epics_v7_migration";

  private static final String LOCATION = "classpath:db/epics/migration";

  @Test
  void anOpenRowLandsRefinedAndAResolvedRowLandsDone() throws Exception {
    String url = EmbeddedPg.url(DATABASE);

    migrateTo(url, "6");

    try (Connection db = connect(url)) {
      insertTicket(db, "t-open", "Still broken", "still-broken", "OPEN", "what to do about it");
      insertTicket(db, "t-resolved", "Fixed", "fixed", "RESOLVED", "what was done about it");
    }

    migrateTo(url, "7");

    try (Connection db = connect(url)) {
      // OPEN becomes REFINED and not REPORTED: every ticket filed under the old vocabulary was
      // filed with a description saying what to do, so none of them is a bare report and no open
      // ticket goes back through phase 1.
      assertEquals("REFINED", statusOf(db, "t-open"));
      // RESOLVED becomes DONE and not VERIFIED: nobody checked these against the platform, and
      // claiming they were verified would be asserting a phase that never ran.
      assertEquals("DONE", statusOf(db, "t-resolved"));

      // The descriptions are untouched — they are what makes REFINED the honest landing place.
      assertEquals("what to do about it", descriptionOf(db, "t-open"));

      // And no impetus is invented for a row that predates the field: a migration cannot know what
      // somebody meant, and backfilling from the description would produce exactly the essay-length
      // impetus the field's length rule exists to prevent.
      assertNull(impetusOf(db, "t-open"));
      assertNull(impetusOf(db, "t-resolved"));
    }
  }

  @Test
  void theCheckConstraintNowSpellsTheFiveStatusesAndRefusesTheOldTwo() throws Exception {
    String url = EmbeddedPg.url(DATABASE + "_constraint");
    migrateTo(url, "7");

    try (Connection db = connect(url)) {
      insertTicket(db, "t-reported", "Filed", "filed", "REPORTED", null);
      assertEquals("REPORTED", statusOf(db, "t-reported"));

      // The retired words are refused by the database, not merely unused by the code.
      boolean refused = false;
      try {
        insertTicket(db, "t-old", "Old", "old", "OPEN", null);
      } catch (Exception expected) {
        refused = true;
      }
      assertTrue(refused, "the widened constraint must no longer accept OPEN");
    }
  }

  // ---- the pieces ------------------------------------------------------------------------------

  private static void migrateTo(String url, String version) {
    Flyway.configure()
        .dataSource(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD)
        .locations(LOCATION)
        .target(MigrationVersion.fromVersion(version))
        .load()
        .migrate();
  }

  private static Connection connect(String url) throws Exception {
    return DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
  }

  private static void insertTicket(
      Connection db, String id, String title, String slug, String status, String description)
      throws Exception {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into Ticket"
                + " (id, project_id, title, slug, type, status, description, created_at, updated_at)"
                + " values (?, 'proj-1', ?, ?, 'BUG', ?, ?, now(), now())")) {
      insert.setString(1, id);
      insert.setString(2, title);
      insert.setString(3, slug);
      insert.setString(4, status);
      insert.setString(5, description);
      insert.executeUpdate();
    }
  }

  private static String statusOf(Connection db, String id) throws Exception {
    return columnOf(db, "status", id);
  }

  private static String descriptionOf(Connection db, String id) throws Exception {
    return columnOf(db, "description", id);
  }

  private static String impetusOf(Connection db, String id) throws Exception {
    return columnOf(db, "impetus", id);
  }

  private static String columnOf(Connection db, String column, String id) throws Exception {
    try (Statement sql = db.createStatement();
        ResultSet found =
            sql.executeQuery("select " + column + " from Ticket where id = '" + id + "'")) {
      assertTrue(found.next(), "no ticket row " + id);
      return found.getString(1);
    }
  }
}
