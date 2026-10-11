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
 * What V30 does to a database that already has rows (qits-248): the DROPPED rows that predate it
 * keep a null outcome — "unrecorded", never backfilled — and from V30 on the column takes the six
 * words and nothing else, and only on a DROPPED row. Plain JUnit on a database of its own per case,
 * like every migration test here.
 */
class EntityOutcomeMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  @Test
  void legacyDroppedRowsKeepANullOutcome() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v30_legacy");
    migrateTo(url, "29");
    try (Connection db = connect(url)) {
      entity(db, "t-dropped", "TICKET", 1, "DROPPED");
      entity(db, "e-dropped", "EPIC", 2, "DROPPED");
      entity(db, "t-open", "TICKET", 3, "REFINED");
      try (Statement sql = db.createStatement()) {
        // qits-382's shape: an epic-era pointer on a dropped row, which V30 leaves as it found it.
        sql.executeUpdate(
            "update entity set superseded_by_entity_id = 't-open' where id = 'e-dropped'");
      }
    }

    migrateTo(url, "30");

    try (Connection db = connect(url)) {
      assertNull(outcomeOf(db, "t-dropped"), "nothing is invented for a dropped ticket");
      assertNull(outcomeOf(db, "e-dropped"), "nor for a dropped epic");
      assertNull(outcomeOf(db, "t-open"), "nor for an open one");
      assertEquals("DROPPED", statusOf(db, "t-dropped"), "the status is untouched");
      assertEquals("t-open", supersededByOf(db, "e-dropped"), "and so is the pointer");
    }
  }

  @Test
  void theSixWordsAreStoredOnADroppedRowAndNothingElseIs() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v30_vocabulary");
    migrateTo(url, "30");
    try (Connection db = connect(url)) {
      long number = 1;
      for (String word :
          new String[] {
            "DUPLICATE", "RESOLVED_ELSEWHERE", "SUPERSEDED", "OBSOLETE", "INVALID", "WONT_DO"
          }) {
        String id = "t-" + word;
        entity(db, id, "TICKET", number++, "DROPPED");
        setOutcome(db, id, word);
        assertEquals(word, outcomeOf(db, id));
      }

      entity(db, "t-typo", "TICKET", number++, "DROPPED");
      assertTrue(refuses(() -> setOutcome(db, "t-typo", "DUPLICATED")), "a word nobody declared");
      assertTrue(refuses(() -> setOutcome(db, "t-typo", "DONE")), "a status is not an outcome");
      assertNull(outcomeOf(db, "t-typo"));
    }
  }

  @Test
  void anOutcomeStandsOnlyOnADroppedRow() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v30_dropped_only");
    migrateTo(url, "30");
    try (Connection db = connect(url)) {
      entity(db, "t-open", "TICKET", 1, "REPORTED");
      assertTrue(
          refuses(() -> setOutcome(db, "t-open", "OBSOLETE")),
          "an outcome on a REPORTED row is refused");

      entity(db, "t-closed", "TICKET", 2, "DROPPED");
      setOutcome(db, "t-closed", "WONT_DO");
      assertTrue(
          refuses(() -> setStatus(db, "t-closed", "REPORTED")),
          "a reopen that keeps the outcome is refused: the move must clear it");

      try (Statement sql = db.createStatement()) {
        sql.executeUpdate(
            "update entity set status = 'REPORTED', outcome = null where id = 't-closed'");
      }
      assertEquals("REPORTED", statusOf(db, "t-closed"));
      assertNull(outcomeOf(db, "t-closed"));
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

  private static void entity(Connection db, String id, String archetype, long number, String status)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into entity"
                + " (id, project_id, archetype, title, slug, slug_scope, number, status,"
                + " created_at, updated_at)"
                + " values (?, 'proj-1', ?, ?, ?, 'proj-1', ?, ?, ?, ?)")) {
      insert.setString(1, id);
      insert.setString(2, archetype);
      insert.setString(3, id);
      insert.setString(4, id);
      insert.setLong(5, number);
      insert.setString(6, status);
      insert.setObject(7, T0);
      insert.setObject(8, T0);
      insert.executeUpdate();
    }
  }

  private static void setOutcome(Connection db, String id, String outcome) throws SQLException {
    try (PreparedStatement update =
        db.prepareStatement("update entity set outcome = ? where id = ?")) {
      update.setString(1, outcome);
      update.setString(2, id);
      update.executeUpdate();
    }
  }

  private static void setStatus(Connection db, String id, String status) throws SQLException {
    try (PreparedStatement update =
        db.prepareStatement("update entity set status = ? where id = ?")) {
      update.setString(1, status);
      update.setString(2, id);
      update.executeUpdate();
    }
  }

  private static String outcomeOf(Connection db, String id) throws Exception {
    return column(db, "outcome", id);
  }

  private static String statusOf(Connection db, String id) throws Exception {
    return column(db, "status", id);
  }

  private static String supersededByOf(Connection db, String id) throws Exception {
    return column(db, "superseded_by_entity_id", id);
  }

  private static String column(Connection db, String column, String id) throws Exception {
    try (Statement sql = db.createStatement();
        ResultSet found =
            sql.executeQuery("select " + column + " from entity where id = '" + id + "'")) {
      assertTrue(found.next(), "no entity row " + id);
      return found.getString(1);
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
