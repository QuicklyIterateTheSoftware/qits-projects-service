package eu.wohlben.qits.projects.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.testdb.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * What V62 does to a database whose desks front-desk runners hold (epic qits-1481, qits-1551):
 * every placed desk is copied into {@code legacy_placement} FIRST — the runner, its engine id and
 * host name where its capabilities said them, and its name — and only then do {@code
 * front_desk.runner_id} and {@code desk_runner} go. A desk gains {@code lease_id}, {@code
 * target_id}, {@code adopted_volume} and {@code lease_detail}, reads no runner report any more, and
 * keeps its tokens. Plain JUnit on a database of its own, like the other migration tests here.
 */
class FrontDeskLeasesMigrationTest {

  private static final String LOCATION = "classpath:db/projects/migration";

  @Test
  void everyPlacedDeskIsCopiedBeforeTheRunnerTablesGo() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v62_front_desk_leases");
    migrateTo(url, "61");
    UUID reporting = UUID.randomUUID();
    UUID silent = UUID.randomUUID();
    try (Connection db = connect(url)) {
      runner(db, reporting, "lab", "{\"engineId\":\"ENGINE-1\",\"hostname\":\"lab-box\"}");
      // What qits-projects-desk-runner-daemon actually reported: no engine id, no host name.
      runner(db, silent, "desk-proof", "{\"version\":\"1\",\"docker\":true,\"arch\":\"amd64\"}");
      project(db, "p-reporting");
      project(db, "p-silent");
      project(db, "p-unplaced");
      desk(db, "p-reporting", reporting, "RUNNING", "the runner could not launch it");
      desk(db, "p-silent", silent, "STOPPED", "EDGE_PLANE_UNCONFIGURED: no domain");
      desk(db, "p-unplaced", null, null, null);
    }

    migrateTo(url, "62");

    try (Connection db = connect(url)) {
      try (Statement sql = db.createStatement();
          ResultSet rows =
              sql.executeQuery(
                  "select project_id, old_runner_id, engine_id, hostname, runner_name"
                      + " from legacy_placement order by project_id")) {
        assertTrue(rows.next());
        assertEquals("p-reporting", rows.getString(1));
        assertEquals(reporting, rows.getObject(2));
        assertEquals("ENGINE-1", rows.getString(3));
        assertEquals("lab-box", rows.getString(4));
        assertEquals("lab", rows.getString(5));
        assertTrue(rows.next());
        assertEquals("p-silent", rows.getString(1));
        assertEquals(silent, rows.getObject(2));
        assertNull(rows.getString(3));
        assertNull(rows.getString(4));
        assertEquals("desk-proof", rows.getString(5));
        assertFalse(rows.next(), "a desk no runner held is not copied");
      }
      try (Statement sql = db.createStatement();
          ResultSet rows =
              sql.executeQuery(
                  "select project_id, lease_id, target_id, adopted_volume, lease_detail,"
                      + " reported_state, failure_detail, token_id from front_desk"
                      + " order by project_id")) {
        assertTrue(rows.next());
        assertEquals("p-reporting", rows.getString(1));
        assertNull(rows.getObject(2), "no desk runs under a lease yet");
        assertNull(rows.getObject(3));
        assertNull(rows.getString(4));
        assertNull(rows.getString(5));
        assertNull(rows.getString(6), "a runner's report is no lease's state");
        assertNull(rows.getString(7), "a runner's launch failure goes with the runner");
        assertEquals("tok-p-reporting", rows.getString(8), "the token stays on the desk");
        assertTrue(rows.next());
        assertEquals("EDGE_PLANE_UNCONFIGURED: no domain", rows.getString(7), "a service failure stays");
      }
      assertFalse(hasColumn(db, "front_desk", "runner_id"), "runner_id is gone");
      assertTrue(hasColumn(db, "front_desk", "lease_id"));
      assertTrue(hasColumn(db, "front_desk", "target_id"));
      assertFalse(hasTable(db, "desk_runner"), "the runner registry is gone");

      // The entry goes with its project.
      try (Statement sql = db.createStatement()) {
        sql.executeUpdate("delete from project where id = 'p-silent'");
        try (ResultSet left =
            sql.executeQuery("select count(*) from legacy_placement where project_id = 'p-silent'")) {
          left.next();
          assertEquals(0, left.getInt(1));
        }
      }
    }
  }

  private static void runner(Connection db, UUID id, String name, String capabilities)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into desk_runner (id, name, capabilities) values (?, ?, ?::jsonb)")) {
      insert.setObject(1, id);
      insert.setString(2, name);
      insert.setString(3, capabilities);
      insert.executeUpdate();
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

  private static void desk(
      Connection db, String projectId, UUID runnerId, String reported, String failure)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into front_desk (project_id, runner_id, desired, token_id, token_value,"
                + " token_subject, reported_state, failure_detail)"
                + " values (?, ?, 'RUNNING', ?, 'qits_tok_x', 'tok-sub', ?, ?)")) {
      insert.setString(1, projectId);
      insert.setObject(2, runnerId);
      insert.setString(3, "tok-" + projectId);
      insert.setString(4, reported);
      insert.setString(5, failure);
      insert.executeUpdate();
    }
  }

  private static boolean hasColumn(Connection db, String table, String column)
      throws SQLException {
    try (PreparedStatement query =
        db.prepareStatement(
            "select 1 from information_schema.columns where table_name = ? and column_name = ?")) {
      query.setString(1, table);
      query.setString(2, column);
      try (ResultSet found = query.executeQuery()) {
        return found.next();
      }
    }
  }

  private static boolean hasTable(Connection db, String table) throws SQLException {
    try (PreparedStatement query =
        db.prepareStatement("select 1 from information_schema.tables where table_name = ?")) {
      query.setString(1, table);
      try (ResultSet found = query.executeQuery()) {
        return found.next();
      }
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
