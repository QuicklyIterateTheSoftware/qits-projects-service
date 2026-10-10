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
 * What V42 does to stored surfaces (qits-1174): every attachment to a retired server goes, {@code
 * qits} takes over the read-only mark of a retired server, positions close up, and a surface where
 * {@code qits} was switched off (no row) keeps it off. Plain JUnit on a database of its own, like {@link
 * AgentSurfaceQitsServerMigrationTest}.
 */
class AgentSurfaceRetireServiceServersMigrationTest {

  private static final String LOCATION = "classpath:db/projects/migration";

  private static final String V42 =
      "/db/projects/migration/V42__agent_surface_retire_service_servers.sql";

  @Test
  void retiredServersGoAndASwitchedOffQitsStaysOff() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v42_agent_surface_retire_service_servers");
    migrateTo(url, "40");
    try (Connection db = connect(url)) {
      // As V33 left a workspace surface: the pair, then qits.
      surface(db, "workspace.chat");
      attachment(db, "workspace.chat", "repository", 0, false, "a\nb");
      attachment(db, "workspace.chat", "observability", 1, false, "c");
      attachment(db, "workspace.chat", "qits", 2, false, "");
      // An unattended surface: the retired server read-only marked, qits not.
      surface(db, "ticket.dispatch");
      attachment(db, "ticket.dispatch", "repository", 0, true, "a");
      attachment(db, "ticket.dispatch", "qits", 1, false, "");
      // Somebody switched qits off and kept repository: qits stays off.
      surface(db, "project.work");
      attachment(db, "project.work", "repository", 0, false, "a");
      attachment(db, "project.work", "actions", 1, false, "d");
      // Nothing attached at all.
      surface(db, "epic.agent");
    }

    migrateTo(url, "42");

    try (Connection db = connect(url)) {
      assertEquals(List.of("qits|0|t|t|f|f|"), attachments(db, "workspace.chat"));
      assertEquals(
          List.of("qits|0|t|t|f|t|"),
          attachments(db, "ticket.dispatch"),
          "qits takes over the read-only mark of the retired server");
      assertEquals(List.of(), attachments(db, "project.work"), "a switched-off qits stays off");
      assertEquals(List.of(), attachments(db, "epic.agent"), "nothing is added");

      // Idempotent: the body again changes nothing.
      runV42(db);
      assertEquals(List.of("qits|0|t|t|f|t|"), attachments(db, "ticket.dispatch"));
      assertEquals(List.of(), attachments(db, "project.work"));
    }
  }

  /** A fresh estate holds no rows; the boot seed writes them from defaults that carry only qits. */
  @Test
  void aFreshStoreGetsNothingFromTheMigration() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v42_agent_surface_retire_service_servers_fresh");
    migrateTo(url, "42");
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

  private static void attachment(
      Connection db, String key, String server, int position, boolean readOnly, String tools)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into agent_surface_mcp_attachment (id, surface_key, server_key, position,"
                + " narrow_project, narrow_repository, narrow_workspace, read_only, allowed_tools)"
                + " values (?, ?, ?, ?, true, true, false, ?, ?)")) {
      insert.setString(1, key + "/" + server);
      insert.setString(2, key);
      insert.setString(3, server);
      insert.setInt(4, position);
      insert.setBoolean(5, readOnly);
      insert.setString(6, tools);
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

  private static void runV42(Connection db) throws Exception {
    String sql;
    try (InputStream in = AgentSurfaceRetireServiceServersMigrationTest.class.getResourceAsStream(V42)) {
      assertTrue(in != null, V42 + " is on the classpath");
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
