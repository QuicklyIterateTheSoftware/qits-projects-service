package eu.wohlben.qits.entities.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * What V25 does (qits-887): {@code ck_entity_status} widens by READY_FOR_DEV, and rows already
 * there stay where they were — a REFINED row is not scheduled by the migration, a person schedules
 * it. Plain JUnit, Flyway driven directly on a database of its own.
 */
class ReadyForDevStatusMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  @Test
  void theConstraintTakesReadyForDevAndKeepsEveryOtherWordAndEveryRow() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v25_vocabulary");
    migrateTo(url, "24");
    try (Connection db = connect(url)) {
      assertTrue(
          refuses(() -> entity(db, "e-early", "EPIC", 1, "READY_FOR_DEV")),
          "before V25 the constraint must refuse READY_FOR_DEV");
      entity(db, "e-refined", "EPIC", 2, "REFINED");
      entity(db, "t-refined", "TICKET", 3, "REFINED");
      entity(db, "e-implementing", "EPIC", 4, "IMPLEMENTING");
    }

    migrateTo(url, "25");

    try (Connection db = connect(url)) {
      int number = 10;
      for (String word :
          new String[] {
            "REPORTED",
            "REFINED",
            "READY_FOR_DEV",
            "IMPLEMENTING",
            "IMPLEMENTED",
            "VERIFYING",
            "VERIFIED",
            "DONE",
            "DROPPED"
          }) {
        entity(db, "w-" + word, "EPIC", number++, word);
        assertEquals(word, columnOf(db, "status", "w-" + word));
      }
      long n = number;
      assertTrue(
          refuses(() -> entity(db, "w-in-progress", "EPIC", n, "IN_PROGRESS")),
          "ck_entity_status must still refuse a word the lifecycle does not spell");
      assertTrue(
          refuses(() -> entity(db, "w-null", "EPIC", n + 1, null)),
          "ck_entity_status must still refuse a null status");
      // No backfill: an existing REFINED row waits for a person to schedule it.
      assertEquals("REFINED", columnOf(db, "status", "e-refined"));
      assertEquals("REFINED", columnOf(db, "status", "t-refined"));
      assertEquals("IMPLEMENTING", columnOf(db, "status", "e-implementing"));
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
      Connection db, String id, String archetype, long number, String status)
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

  private static String columnOf(Connection db, String column, String id) throws Exception {
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
