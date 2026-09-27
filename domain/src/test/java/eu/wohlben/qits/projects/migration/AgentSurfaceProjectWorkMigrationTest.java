package eu.wohlben.qits.projects.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.testdb.EmbeddedPg;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * What V30 does to a store that already holds the desk rows (qits-403): {@code project.work} is
 * INSERTED as a copy of {@code project.epics} — row, built-in attachments and catalog attachments —
 * and both old rows stay exactly as they were. Plain JUnit on a database of its own, like {@link
 * RefinementEntityKeyMigrationTest}.
 */
class AgentSurfaceProjectWorkMigrationTest {

  private static final String LOCATION = "classpath:db/projects/migration";

  private static final String V30 =
      "/db/projects/migration/V30__agent_surface_project_work.sql";

  private static final String ROW_COLUMNS =
      "harness, model, effort, remote_control, permission_mode, activity_tracking, system_prompt,"
          + " initial_prompt, updated_by";

  @Test
  void theOneDeskIsInsertedAsACopyOfTheEpicsDeskAndTheOldRowsStay() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v30_agent_surface_project_work");
    migrateTo(url, "29");
    try (Connection db = connect(url)) {
      // A non-default epics desk: every column moved off its shipped value.
      surface(db, "project.epics", "KIMI", "k2", "high", false, "PROMPT", false, "Steer the desk.",
          "Say hello.", "operator@example.com");
      attachment(db, "project.epics", "repository", 0, true, false, false, true, "a\nb");
      attachment(db, "project.epics", "observability", 1, false, true, false, false, "c");
      external(db, "project.epics", "weather", 0);
      external(db, "project.epics", "docs", 1);
      // The tickets desk, which must NOT be what the one desk copies.
      surface(db, "project.tickets", "CLAUDE", "", "", true, "SKIP_PERMISSIONS", true,
          "Tickets prompt.", "", null);
      attachment(db, "project.tickets", "repository", 0, true, false, false, false, "t");
    }
    List<String> epicsBefore;
    List<String> ticketsBefore;
    try (Connection db = connect(url)) {
      epicsBefore = snapshot(db, "project.epics");
      ticketsBefore = snapshot(db, "project.tickets");
    }

    migrateTo(url, "30");

    try (Connection db = connect(url)) {
      assertEquals(
          row(db, "project.epics"), row(db, "project.work"), "every column copied from the epics desk");
      assertEquals(
          List.of(
              "repository|0|t|f|f|t|a\nb",
              "observability|1|f|t|f|f|c"),
          attachments(db, "project.work"),
          "the built-in servers copied, narrowing, order and pre-approvals intact");
      assertEquals(List.of("weather|0", "docs|1"), externals(db, "project.work"));
      assertNotEquals(
          ids(db, "agent_surface_mcp_attachment", "project.epics"),
          ids(db, "agent_surface_mcp_attachment", "project.work"),
          "fresh ids, not the epics desk's");

      assertEquals(epicsBefore, snapshot(db, "project.epics"), "an insert, not an update");
      assertEquals(ticketsBefore, snapshot(db, "project.tickets"), "the tickets desk is untouched");

      // Idempotent: the body again inserts nothing and moves nothing.
      List<String> workAfter = snapshot(db, "project.work");
      runV30(db);
      assertEquals(workAfter, snapshot(db, "project.work"));
      assertEquals(1, count(db, "agent_surface_configuration", "project.work"));
      assertEquals(2, count(db, "agent_surface_mcp_attachment", "project.work"));
      assertEquals(2, count(db, "agent_surface_external_mcp_attachment", "project.work"));
    }
  }

  /** A {@code project.work} row somebody already wrote is left alone, attachments and all. */
  @Test
  void anExistingOneDeskRowIsNeverOverwrittenOrMergedInto() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v30_agent_surface_project_work_present");
    migrateTo(url, "29");
    try (Connection db = connect(url)) {
      surface(db, "project.epics", "KIMI", "k2", "high", false, "PROMPT", false, "Epics.", "", null);
      attachment(db, "project.epics", "repository", 0, true, false, false, false, "a");
      external(db, "project.epics", "weather", 0);
      surface(db, "project.work", "CLAUDE", "", "", true, "SKIP_PERMISSIONS", true, "Mine.", "",
          "someone");
      attachment(db, "project.work", "observability", 0, false, true, true, false, "o");
    }
    List<String> before;
    try (Connection db = connect(url)) {
      before = snapshot(db, "project.work");
    }

    migrateTo(url, "30");

    try (Connection db = connect(url)) {
      assertEquals(before, snapshot(db, "project.work"));
      assertEquals(List.of(), externals(db, "project.work"), "nothing merged in from the epics desk");
    }
  }

  /** A fresh estate holds no epics row, so V30 inserts nothing and leaves the seed to write it. */
  @Test
  void aFreshStoreGetsNothingFromTheMigration() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v30_agent_surface_project_work_fresh");
    migrateTo(url, "30");
    try (Connection db = connect(url)) {
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

  private static List<String> row(Connection db, String key) throws SQLException {
    return query(
        db,
        "select " + ROW_COLUMNS + " from agent_surface_configuration where surface_key = ?",
        key,
        9);
  }

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

  private static void runV30(Connection db) throws Exception {
    String sql;
    try (InputStream in = AgentSurfaceProjectWorkMigrationTest.class.getResourceAsStream(V30)) {
      assertTrue(in != null, V30 + " is on the classpath");
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (Statement statement = db.createStatement()) {
      statement.execute(sql);
    }
    assertFalse(db.isClosed());
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
