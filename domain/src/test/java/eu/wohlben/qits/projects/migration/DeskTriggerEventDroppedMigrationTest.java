package eu.wohlben.qits.projects.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.testdb.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * What V80 does (qits-2501): {@code desk_trigger_event} gains nullable {@code dropped_at} and
 * {@code dropped_reason}, not backfilled, so every line stored before reads live; a dropped line
 * still holds its {@code desk_trigger_event_once} key, so its event is not admitted again; and a
 * window may be stored {@code REDUNDANT} beside a new {@code OPEN} one of the same project.
 */
class DeskTriggerEventDroppedMigrationTest {

  private static final String LOCATION = "classpath:db/projects/migration";

  private static final Instant T0 = Instant.parse("2026-11-22T10:00:00Z");

  @Test
  void linesGainNullableDropColumnsAndADroppedLineStillRefusesItsEvent() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v80_desk_trigger_event_dropped");
    migrateTo(url, "79");
    UUID eventId = UUID.randomUUID();
    UUID before;
    try (Connection db = connect(url)) {
      project(db, "p-1");
      UUID window = window(db, "p-1", "OPEN");
      before = event(db, window, eventId);
    }

    migrateTo(url, "80");

    try (Connection db = connect(url)) {
      try (Statement sql = db.createStatement();
          ResultSet rows =
              sql.executeQuery(
                  "select dropped_at, dropped_reason from desk_trigger_event where id = '"
                      + before
                      + "'")) {
        assertTrue(rows.next());
        assertNull(rows.getObject(1), "no backfill: a stored line is live");
        assertNull(rows.getObject(2));
      }
      try (Statement sql = db.createStatement()) {
        sql.executeUpdate(
            "update desk_trigger_event set dropped_at = now(), dropped_reason = 'resolved'");
        sql.executeUpdate("update desk_trigger_window set state = 'REDUNDANT'");
      }
      UUID open = window(db, "p-1", "OPEN");
      SQLException refused = null;
      try {
        event(db, open, eventId);
      } catch (SQLException e) {
        refused = e;
      }
      assertTrue(
          refused != null && "23505".equals(refused.getSQLState()),
          "a dropped line keeps its event from being admitted again");
      try (Statement sql = db.createStatement();
          ResultSet rows =
              sql.executeQuery("select count(*) from desk_trigger_window where project_id = 'p-1'")) {
        rows.next();
        assertEquals(2, rows.getInt(1), "a REDUNDANT window and a new OPEN one");
      }
    }
  }

  private static void project(Connection db, String id) throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement("insert into project (id, name, slug) values (?, ?, ?)")) {
      insert.setString(1, id);
      insert.setString(2, id);
      insert.setString(3, id);
      insert.executeUpdate();
    }
  }

  private static UUID window(Connection db, String projectId, String state) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into desk_trigger_window (id, project_id, opened_at, closes_at, state)"
                + " values (?, ?, ?, ?, ?)")) {
      insert.setObject(1, id);
      insert.setString(2, projectId);
      insert.setTimestamp(3, Timestamp.from(T0));
      insert.setTimestamp(4, Timestamp.from(T0.plusSeconds(900)));
      insert.setString(5, state);
      insert.executeUpdate();
    }
    return id;
  }

  private static UUID event(Connection db, UUID windowId, UUID eventId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into desk_trigger_event (id, window_id, source, item_ref, item_id, label,"
                + " event_id, occurred_at, admitted_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setObject(1, id);
      insert.setObject(2, windowId);
      insert.setString(3, "approval-needed");
      insert.setString(4, "p-rr-1");
      insert.setString(5, "rr-1");
      insert.setString(6, "waits on manual-review");
      insert.setObject(7, eventId);
      insert.setTimestamp(8, Timestamp.from(T0));
      insert.setTimestamp(9, Timestamp.from(T0));
      insert.executeUpdate();
    }
    return id;
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
