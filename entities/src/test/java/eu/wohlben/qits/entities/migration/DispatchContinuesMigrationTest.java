package eu.wohlben.qits.entities.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.testdb.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * What V16 does to a database that already has rows: every existing ticket keeps carrying its
 * phases on (true, what TicketPhaseAdvance always did), every existing epic stops after one phase
 * (false, what the single-shot epic dispatch always did), and a row inserted afterwards without
 * naming the column continues. Plain JUnit on a database of its own, like every migration test here.
 */
class DispatchContinuesMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  @Test
  void existingTicketsContinueExistingEpicsStopAndNewRowsContinue() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v16_dispatch_continues");
    migrateTo(url, "15");
    try (Connection db = connect(url)) {
      entity(db, "e-standing", "EPIC", 1, "REFINED");
      entity(db, "t-standing", "TICKET", 2, "REFINED");
      entity(db, "f-plain", "FEATURE", 3, null);
    }

    migrateTo(url, "16");

    try (Connection db = connect(url)) {
      assertEquals("f", continuesOf(db, "e-standing"), "an epic's dispatch was single-shot");
      assertEquals("t", continuesOf(db, "t-standing"), "a ticket's phases always carried on");
      assertEquals("t", continuesOf(db, "f-plain"), "a feature takes the default and never reads it");

      entity(db, "t-new", "TICKET", 4, "REPORTED");
      entity(db, "e-new", "EPIC", 5, "REPORTED");
      assertEquals("t", continuesOf(db, "t-new"));
      assertEquals("t", continuesOf(db, "e-new"), "the backfill is not a rule for new rows");
    }
  }

  private static void entity(Connection db, String id, String archetype, long number, String status)
      throws Exception {
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

  private static String continuesOf(Connection db, String id) throws Exception {
    try (Statement sql = db.createStatement();
        ResultSet found =
            sql.executeQuery(
                "select dispatch_continues from entity where id = '" + id + "'")) {
      assertTrue(found.next(), "no entity row " + id);
      return found.getBoolean(1) ? "t" : "f";
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
