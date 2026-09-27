package eu.wohlben.qits.entities.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.testdb.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * What V15 does to a database that already has epics in it — the backfill that moves every epic
 * row off {@code EpicStatus}' words and the narrowing of {@code ck_entity_status} that follows it.
 *
 * <p>Only here can the order inside the file be observed: stand at V14, write one epic per old word
 * (plus a ticket and a status-less feature beside them), run V15, and read every row back. A
 * narrowing that ran before the backfill would fail this migration outright; a backfill that missed
 * a word would leave a row the new constraint cannot hold. Plain JUnit, for the reason every
 * migration test in this module gives — Flyway driven directly on a database of its own.
 */
class OneEntityLifecycleMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  @Test
  void everyOldEpicWordLandsOnTheOneLifecycleAndNoRowIsLost() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v15_backfill");
    migrateTo(url, "14");

    try (Connection db = connect(url)) {
      entity(db, "e-successor", "EPIC", 1, "REFINING", null);
      entity(db, "e-refining", "EPIC", 2, "REFINING", null);
      entity(db, "e-implementation", "EPIC", 3, "IMPLEMENTATION", null);
      entity(db, "e-implemented", "EPIC", 4, "IMPLEMENTED", null);
      entity(db, "e-abandoned", "EPIC", 5, "ABANDONED", null);
      entity(db, "e-superseded", "EPIC", 6, "SUPERSEDED", "e-successor");
      // Beside them, rows V15 must leave alone: a ticket already in the surviving vocabulary and a
      // feature with no status at all.
      entity(db, "t-refined", "TICKET", 7, "REFINED", null);
      entity(db, "f-plain", "FEATURE", 8, null, null);
    }
    long before;
    try (Connection db = connect(url)) {
      before = count(db);
    }

    migrateTo(url, "15");

    try (Connection db = connect(url)) {
      assertEquals("REPORTED", statusOf(db, "e-refining"));
      assertEquals("REPORTED", statusOf(db, "e-successor"));
      assertEquals("REFINED", statusOf(db, "e-implementation"));
      assertEquals("IMPLEMENTED", statusOf(db, "e-implemented"));
      assertEquals("DROPPED", statusOf(db, "e-abandoned"));
      assertEquals("DROPPED", statusOf(db, "e-superseded"));

      // The successor pointer is what still tells a superseded DROPPED row from an abandoned one,
      // and the backfill must not touch it.
      assertEquals("e-successor", supersededByOf(db, "e-superseded"));
      assertNull(supersededByOf(db, "e-abandoned"));

      assertEquals("REFINED", statusOf(db, "t-refined"));
      assertNull(statusOf(db, "f-plain"));

      assertEquals(before, count(db), "V15 rewrites rows, it never drops one");
    }
  }

  @Test
  void theConstraintSpellsExactlyTheSixSurvivingWords() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v15_vocabulary");
    migrateTo(url, "15");

    try (Connection db = connect(url)) {
      int number = 1;
      for (String word :
          new String[] {"REPORTED", "REFINED", "IMPLEMENTED", "VERIFIED", "DONE", "DROPPED"}) {
        entity(db, "e-" + word, "EPIC", number++, word, null);
        assertEquals(word, statusOf(db, "e-" + word));
      }
      for (String retired : new String[] {"REFINING", "IMPLEMENTATION", "ABANDONED", "SUPERSEDED"}) {
        String id = "e-old-" + retired;
        long n = number++;
        assertTrue(
            refuses(() -> entity(db, id, "EPIC", n, retired, null)),
            "ck_entity_status must no longer accept " + retired);
      }
    }
  }

  // ---- the pieces ------------------------------------------------------------------------------

  private static boolean refuses(Write write) {
    try {
      write.run();
      return false;
    } catch (SQLException expected) {
      return true;
    }
  }

  private interface Write {
    void run() throws SQLException;
  }

  private static void entity(
      Connection db, String id, String archetype, long number, String status, String supersededBy)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into entity"
                + " (id, project_id, archetype, title, slug, slug_scope, number, status,"
                + " superseded_by_entity_id, created_at, updated_at)"
                + " values (?, 'proj-1', ?, ?, ?, 'proj-1', ?, ?, ?, ?, ?)")) {
      insert.setString(1, id);
      insert.setString(2, archetype);
      insert.setString(3, id);
      insert.setString(4, id);
      insert.setLong(5, number);
      insert.setString(6, status);
      insert.setString(7, supersededBy);
      insert.setObject(8, T0);
      insert.setObject(9, T0);
      insert.executeUpdate();
    }
  }

  private static String statusOf(Connection db, String id) throws Exception {
    return columnOf(db, "status", id);
  }

  private static String supersededByOf(Connection db, String id) throws Exception {
    return columnOf(db, "superseded_by_entity_id", id);
  }

  private static String columnOf(Connection db, String column, String id) throws Exception {
    try (Statement sql = db.createStatement();
        ResultSet found =
            sql.executeQuery("select " + column + " from entity where id = '" + id + "'")) {
      assertTrue(found.next(), "no entity row " + id);
      return found.getString(1);
    }
  }

  private static long count(Connection db) throws Exception {
    try (Statement sql = db.createStatement();
        ResultSet found = sql.executeQuery("select count(*) from entity")) {
      found.next();
      return found.getLong(1);
    }
  }

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
}
