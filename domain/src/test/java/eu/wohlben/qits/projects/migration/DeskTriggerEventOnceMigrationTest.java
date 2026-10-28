package eu.wohlben.qits.projects.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * What V65 does (qits-1694): the duplicates a racing admit could store are deleted, keeping the
 * earliest admitted; {@code (source, event_id, item_ref)} becomes unique across windows, and
 * capacity-opened's {@code (source, item_id)}; {@code project} gains a nullable {@code
 * delivery_enabled_at}, not backfilled. Plain JUnit on a database of its own, like the other
 * migration tests here.
 */
class DeskTriggerEventOnceMigrationTest {

  private static final String LOCATION = "classpath:db/projects/migration";

  private static final Instant T0 = Instant.parse("2026-10-27T10:00:00Z");

  @Test
  void duplicatesGoKeepingTheEarliestAndTheSchemaRefusesNewOnes() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v65_desk_trigger_event_once");
    migrateTo(url, "64");
    UUID stall = UUID.randomUUID();
    UUID fanOut = UUID.randomUUID();
    UUID first;
    UUID earliestAgentLine;
    try (Connection db = connect(url)) {
      project(db, "p-1");
      UUID early = window(db, "p-1", "OWED", T0);
      UUID late = window(db, "p-1", "OPEN", T0.plusSeconds(1800));
      // One stall told twice: a sweep and a second replica, the label's duration apart.
      first = event(db, early, "request-stalled", "p-rr-1", null, "for 2h 1m", stall, T0);
      event(db, late, "request-stalled", "p-rr-1", null, "for 2h 31m", stall, T0.plusSeconds(1800));
      // work-verified's fan-out: one event, two items — both stay.
      event(db, early, "work-verified", "p-1", null, "VERIFIED", fanOut, T0);
      event(db, early, "work-verified", "p-2", null, "VERIFIED", fanOut, T0);
      // One agent's leave told from AgentObsolete and again from AgentRemoved.
      earliestAgentLine =
          event(db, early, "capacity-opened", "p-3", "agent-1", "done", UUID.randomUUID(), T0);
      event(db, late, "capacity-opened", "p-3", "agent-1", "removed", UUID.randomUUID(), T0.plusSeconds(60));
    }

    migrateTo(url, "65");

    try (Connection db = connect(url)) {
      assertEquals(List.of(first), ids(db, "source = 'request-stalled'"), "the earliest stays");
      assertEquals(2, ids(db, "source = 'work-verified'").size(), "a fan-out is not a duplicate");
      assertEquals(List.of(earliestAgentLine), ids(db, "source = 'capacity-opened'"));

      UUID open = windowOf(db, "OPEN");
      assertThrowsUnique(
          db, open, "request-stalled", "p-rr-1", null, "for 3h 1m", stall);
      assertThrowsUnique(
          db, open, "capacity-opened", "p-9", "agent-1", "again", UUID.randomUUID());

      try (Statement sql = db.createStatement();
          ResultSet rows = sql.executeQuery("select delivery_enabled_at from project")) {
        assertTrue(rows.next());
        assertEquals(null, rows.getObject(1), "no backfill: null is no clamp");
      }
    }
  }

  private static void assertThrowsUnique(
      Connection db,
      UUID windowId,
      String source,
      String ref,
      String itemId,
      String label,
      UUID eventId) {
    SQLException refused = null;
    try {
      event(db, windowId, source, ref, itemId, label, eventId, T0.plusSeconds(3600));
    } catch (SQLException e) {
      refused = e;
    }
    assertTrue(refused != null && "23505".equals(refused.getSQLState()), source + " is unique");
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

  private static UUID window(Connection db, String projectId, String state, Instant opened)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into desk_trigger_window (id, project_id, opened_at, closes_at, state)"
                + " values (?, ?, ?, ?, ?)")) {
      insert.setObject(1, id);
      insert.setString(2, projectId);
      insert.setTimestamp(3, Timestamp.from(opened));
      insert.setTimestamp(4, Timestamp.from(opened.plusSeconds(900)));
      insert.setString(5, state);
      insert.executeUpdate();
    }
    return id;
  }

  private static UUID event(
      Connection db,
      UUID windowId,
      String source,
      String ref,
      String itemId,
      String label,
      UUID eventId,
      Instant admitted)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into desk_trigger_event (id, window_id, source, item_ref, item_id, label,"
                + " event_id, occurred_at, admitted_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setObject(1, id);
      insert.setObject(2, windowId);
      insert.setString(3, source);
      insert.setString(4, ref);
      insert.setString(5, itemId);
      insert.setString(6, label);
      insert.setObject(7, eventId);
      insert.setTimestamp(8, Timestamp.from(admitted));
      insert.setTimestamp(9, Timestamp.from(admitted));
      insert.executeUpdate();
    }
    return id;
  }

  private static List<UUID> ids(Connection db, String where) throws SQLException {
    List<UUID> ids = new ArrayList<>();
    try (Statement sql = db.createStatement();
        ResultSet rows = sql.executeQuery("select id from desk_trigger_event where " + where)) {
      while (rows.next()) {
        ids.add((UUID) rows.getObject(1));
      }
    }
    return ids;
  }

  private static UUID windowOf(Connection db, String state) throws SQLException {
    try (Statement sql = db.createStatement();
        ResultSet rows =
            sql.executeQuery("select id from desk_trigger_window where state = '" + state + "'")) {
      rows.next();
      return (UUID) rows.getObject(1);
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
