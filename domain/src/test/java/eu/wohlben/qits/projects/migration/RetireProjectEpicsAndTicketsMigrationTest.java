package eu.wohlben.qits.projects.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.projects.testdb.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * What V31 does to a store that still holds the two retired desk rows (qits-404): every row for
 * {@code project.epics} and {@code project.tickets} is gone from {@code agent_surface_configuration}
 * and both attachment tables, and {@code project.work} — the desk that replaced them, V30's copy —
 * and its own attachments are untouched. Plain JUnit on a database of its own, like {@link
 * AgentSurfaceProjectWorkMigrationTest}.
 */
class RetireProjectEpicsAndTicketsMigrationTest {

  private static final String LOCATION = "classpath:db/projects/migration";

  private static final String ROW_COLUMNS =
      "harness, model, effort, remote_control, permission_mode, activity_tracking, system_prompt,"
          + " initial_prompt, updated_by";

  @Test
  void theTwoRetiredDesksAreGoneAndProjectWorkIsUntouched() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v31_retire_project_epics_and_tickets");
    migrateTo(url, "29");
    try (Connection db = connect(url)) {
      surface(db, "project.epics", "KIMI", "k2", "high", false, "PROMPT", false, "Steer the desk.",
          "Say hello.", "operator@example.com");
      attachment(db, "project.epics", "repository", 0, true, false, false, true, "a\nb");
      attachment(db, "project.epics", "observability", 1, false, true, false, false, "c");
      external(db, "project.epics", "weather", 0);
      external(db, "project.epics", "docs", 1);

      surface(db, "project.tickets", "CLAUDE", "", "", true, "SKIP_PERMISSIONS", true,
          "Tickets prompt.", "", null);
      attachment(db, "project.tickets", "repository", 0, true, false, false, false, "t");
      external(db, "project.tickets", "weather", 0);
    }

    // V30 runs before V31 and inserts project.work as a copy of project.epics — exercise the real
    // sequence rather than hand-inserting a project.work row.
    migrateTo(url, "30");

    List<String> workBefore;
    try (Connection db = connect(url)) {
      workBefore = snapshot(db, "project.work");
      assertEquals(1, count(db, "agent_surface_configuration", "project.work"), "V30 copied it in");
    }

    migrateTo(url, "31");

    try (Connection db = connect(url)) {
      // The two retired desks are gone from every table an attachment can live in.
      for (String retired : List.of("project.epics", "project.tickets")) {
        assertEquals(0, count(db, "agent_surface_configuration", retired), retired + " row gone");
        assertEquals(
            0, count(db, "agent_surface_mcp_attachment", retired), retired + " built-in attachments gone");
        assertEquals(
            0,
            count(db, "agent_surface_external_mcp_attachment", retired),
            retired + " catalog attachments gone");
      }

      // project.work — the desk that replaced them, and its attachments — is untouched.
      assertEquals(workBefore, snapshot(db, "project.work"), "project.work must not move");
      assertEquals(1, count(db, "agent_surface_configuration", "project.work"));
      assertEquals(2, count(db, "agent_surface_mcp_attachment", "project.work"));
      assertEquals(2, count(db, "agent_surface_external_mcp_attachment", "project.work"));
    }
  }

  /** A fresh estate holds neither retired row, so V31 has nothing to delete and nothing to break. */
  @Test
  void aFreshStoreIsUnaffected() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v31_retire_project_epics_and_tickets_fresh");
    migrateTo(url, "31");
    try (Connection db = connect(url)) {
      assertEquals(0, count(db, "agent_surface_configuration", "project.epics"));
      assertEquals(0, count(db, "agent_surface_configuration", "project.tickets"));
      assertEquals(0, count(db, "agent_surface_configuration", "project.work"));
    }
  }

  // --------------------------------------------------------------------------------- fixtures

  private static void surface(
      Connection db,
      String key,
      String harness,
      String model,
      String effort,
      boolean remoteControl,
      String permissionMode,
      boolean activityTracking,
      String systemPrompt,
      String initialPrompt,
      String updatedBy)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into agent_surface_configuration (surface_key, "
                + ROW_COLUMNS
                + ", created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " timestamptz '2026-01-01T00:00:00Z', timestamptz '2026-01-02T00:00:00Z')")) {
      insert.setString(1, key);
      insert.setString(2, harness);
      insert.setString(3, model);
      insert.setString(4, effort);
      insert.setBoolean(5, remoteControl);
      insert.setString(6, permissionMode);
      insert.setBoolean(7, activityTracking);
      insert.setString(8, systemPrompt);
      insert.setString(9, initialPrompt);
      insert.setString(10, updatedBy);
      insert.executeUpdate();
    }
  }

  private static void attachment(
      Connection db,
      String key,
      String server,
      int position,
      boolean project,
      boolean repository,
      boolean workspace,
      boolean readOnly,
      String tools)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into agent_surface_mcp_attachment (id, surface_key, server_key, position,"
                + " narrow_project, narrow_repository, narrow_workspace, read_only, allowed_tools)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, key + "/" + server);
      insert.setString(2, key);
      insert.setString(3, server);
      insert.setInt(4, position);
      insert.setBoolean(5, project);
      insert.setBoolean(6, repository);
      insert.setBoolean(7, workspace);
      insert.setBoolean(8, readOnly);
      insert.setString(9, tools);
      insert.executeUpdate();
    }
  }

  private static void external(Connection db, String key, String catalogKey, int position)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into agent_surface_external_mcp_attachment (id, surface_key, catalog_key,"
                + " position) values (?, ?, ?, ?)")) {
      insert.setString(1, key + "/x/" + catalogKey);
      insert.setString(2, key);
      insert.setString(3, catalogKey);
      insert.setInt(4, position);
      insert.executeUpdate();
    }
  }

  // --------------------------------------------------------------------------------- readers

  private static List<String> attachments(Connection db, String key) throws SQLException {
    return query(
        db,
        "select server_key, position, narrow_project, narrow_repository, narrow_workspace,"
            + " read_only, allowed_tools from agent_surface_mcp_attachment where surface_key = ?"
            + " order by position",
        key,
        7);
  }

  private static List<String> externals(Connection db, String key) throws SQLException {
    return query(
        db,
        "select catalog_key, position from agent_surface_external_mcp_attachment"
            + " where surface_key = ? order by position",
        key,
        2);
  }

  private static List<String> ids(Connection db, String table, String key) throws SQLException {
    return query(db, "select id from " + table + " where surface_key = ? order by position", key, 1);
  }

  /** The whole configuration as stored, timestamps and ids included — what "untouched" means. */
  private static List<String> snapshot(Connection db, String key) throws SQLException {
    List<String> all = new ArrayList<>();
    all.addAll(
        query(
            db,
            "select " + ROW_COLUMNS + ", created_at, updated_at from agent_surface_configuration"
                + " where surface_key = ?",
            key,
            11));
    all.addAll(ids(db, "agent_surface_mcp_attachment", key));
    all.addAll(attachments(db, key));
    all.addAll(ids(db, "agent_surface_external_mcp_attachment", key));
    all.addAll(externals(db, key));
    return all;
  }

  private static List<String> query(Connection db, String sql, String key, int columns)
      throws SQLException {
    List<String> rows = new ArrayList<>();
    try (PreparedStatement select = db.prepareStatement(sql)) {
      select.setString(1, key);
      try (ResultSet found = select.executeQuery()) {
        while (found.next()) {
          StringBuilder line = new StringBuilder();
          for (int i = 1; i <= columns; i++) {
            if (i > 1) {
              line.append('|');
            }
            line.append(found.getString(i));
          }
          rows.add(line.toString());
        }
      }
    }
    return rows;
  }

  private static int count(Connection db, String table, String key) throws SQLException {
    try (PreparedStatement select =
        db.prepareStatement("select count(*) from " + table + " where surface_key = ?")) {
      select.setString(1, key);
      try (ResultSet found = select.executeQuery()) {
        found.next();
        return found.getInt(1);
      }
    }
  }

  // --------------------------------------------------------------------------------- plumbing

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
