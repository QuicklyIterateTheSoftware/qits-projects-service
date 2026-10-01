package eu.wohlben.qits.projects.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * What V33 does to stored surfaces that predate the {@code qits} server (qits-630): each one gets
 * the bare {@code qits} attachment appended after the servers it already holds, and nothing it held
 * moves. Plain JUnit on a database of its own, like {@link AgentSurfaceProjectWorkMigrationTest}.
 */
class AgentSurfaceQitsServerMigrationTest {

  private static final String LOCATION = "classpath:db/projects/migration";

  private static final String V33 = "/db/projects/migration/V33__agent_surface_qits_server.sql";

  @Test
  void everyStoredSurfaceGetsQitsAppendedAndKeepsWhatItHad() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v33_agent_surface_qits_server");
    migrateTo(url, "32");
    try (Connection db = connect(url)) {
      surface(db, "workspace.chat");
      attachment(db, "workspace.chat", "repository", 0, "a\nb");
      attachment(db, "workspace.chat", "observability", 1, "c");
      // An operator turned every server off here; qits is still on, because nobody could have
      // turned THAT one off — the store refused the key until this release.
      surface(db, "epic.agent");
    }

    migrateTo(url, "33");

    try (Connection db = connect(url)) {
      assertEquals(
          List.of(
              "repository|0|f|f|f|f|a\nb",
              "observability|1|f|f|f|f|c",
              "qits|2|f|f|f|f|"),
          attachments(db, "workspace.chat"),
          "appended last, bare, and the existing order kept");
      assertEquals(List.of("qits|0|f|f|f|f|"), attachments(db, "epic.agent"));

      // Idempotent: the body again adds nothing.
      runV33(db);
      assertEquals(3, attachments(db, "workspace.chat").size());
      assertEquals(1, attachments(db, "epic.agent").size());
    }
  }

  /** A fresh estate holds no rows; the boot seed writes them from defaults that already carry qits. */
  @Test
  void aFreshStoreGetsNothingFromTheMigration() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v33_agent_surface_qits_server_fresh");
    migrateTo(url, "33");
    try (Connection db = connect(url);
        Statement statement = db.createStatement();
        ResultSet found = statement.executeQuery("select count(*) from agent_surface_mcp_attachment")) {
      found.next();
      assertEquals(0, found.getInt(1));
    }
  }

  // --------------------------------------------------------------------------------- fixtures

  private static void surface(Connection db, String key) throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into agent_surface_configuration (surface_key, harness, model, effort,"
                + " remote_control, permission_mode, activity_tracking, system_prompt,"
                + " initial_prompt, created_at, updated_at) values (?, 'CLAUDE', '', '', true,"
                + " 'SKIP_PERMISSIONS', true, '', '', now(), now())")) {
      insert.setString(1, key);
      insert.executeUpdate();
    }
  }

  private static void attachment(Connection db, String key, String server, int position, String tools)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into agent_surface_mcp_attachment (id, surface_key, server_key, position,"
                + " narrow_project, narrow_repository, narrow_workspace, read_only, allowed_tools)"
                + " values (?, ?, ?, ?, false, false, false, false, ?)")) {
      insert.setString(1, key + "/" + server);
      insert.setString(2, key);
      insert.setString(3, server);
      insert.setInt(4, position);
      insert.setString(5, tools);
      insert.executeUpdate();
    }
  }

  private static List<String> attachments(Connection db, String key) throws SQLException {
    List<String> rows = new ArrayList<>();
    try (PreparedStatement select =
        db.prepareStatement(
            "select server_key, position, narrow_project, narrow_repository, narrow_workspace,"
                + " read_only, allowed_tools from agent_surface_mcp_attachment where surface_key = ?"
                + " order by position")) {
      select.setString(1, key);
      try (ResultSet found = select.executeQuery()) {
        while (found.next()) {
          StringBuilder line = new StringBuilder();
          for (int i = 1; i <= 7; i++) {
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

  // --------------------------------------------------------------------------------- plumbing

  private static void runV33(Connection db) throws Exception {
    String sql;
    try (InputStream in = AgentSurfaceQitsServerMigrationTest.class.getResourceAsStream(V33)) {
      assertTrue(in != null, V33 + " is on the classpath");
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (Statement statement = db.createStatement()) {
      statement.execute(sql);
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
