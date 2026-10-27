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
 * What V32 does (qits-1685): every open feature and task below a DONE epic becomes DONE, a DROPPED
 * one stays, and the plan of an epic that is not DONE, or a campaign's member, is not touched.
 */
class ClosePiecesOfDoneWorkMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  @Test
  void theOpenPiecesOfADoneEpicAreClosedAndNothingElseMoves() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v32_close_pieces");
    migrateTo(url, "31");
    try (Connection db = connect(url)) {
      entity(db, "e-done", "EPIC", 1, "DONE");
      entity(db, "f-verified", "FEATURE", 2, "VERIFIED");
      entity(db, "t-implemented", "TASK", 3, "IMPLEMENTED");
      entity(db, "f-dropped", "FEATURE", 4, "DROPPED");
      entity(db, "f-done", "FEATURE", 5, "DONE");
      entity(db, "e-open", "EPIC", 6, "VERIFIED");
      entity(db, "f-open", "FEATURE", 7, "VERIFIED");
      entity(db, "c-done", "CAMPAIGN", 8, "DONE");
      entity(db, "e-member", "EPIC", 9, "VERIFIED");
      edge(db, "m-1", "e-done", "f-verified", 0, "STRUCTURAL");
      edge(db, "m-2", "f-verified", "t-implemented", 0, "STRUCTURAL");
      edge(db, "m-3", "e-done", "f-dropped", 1, "STRUCTURAL");
      edge(db, "m-4", "e-done", "f-done", 2, "STRUCTURAL");
      edge(db, "m-5", "e-open", "f-open", 0, "STRUCTURAL");
      edge(db, "m-6", "c-done", "e-member", 0, "CAMPAIGN");
    }

    migrateTo(url, "32");

    try (Connection db = connect(url)) {
      assertEquals("DONE", statusOf(db, "f-verified"));
      assertEquals("DONE", statusOf(db, "t-implemented"), "a task two levels down closes too");
      assertEquals("DROPPED", statusOf(db, "f-dropped"), "decided against, so left");
      assertEquals("DONE", statusOf(db, "f-done"));
      assertEquals("VERIFIED", statusOf(db, "f-open"), "its epic is not DONE");
      assertEquals("VERIFIED", statusOf(db, "e-member"), "a campaign gathers, it does not contain");
    }
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

  private static void edge(
      Connection db, String id, String parent, String child, int position, String kind)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into entity_membership"
                + " (id, parent_id, child_id, position, created_at, updated_at, kind)"
                + " values (?, ?, ?, ?, now(), now(), ?)")) {
      insert.setString(1, id);
      insert.setString(2, parent);
      insert.setString(3, child);
      insert.setInt(4, position);
      insert.setString(5, kind);
      insert.executeUpdate();
    }
  }

  private static String statusOf(Connection db, String id) throws Exception {
    try (Statement sql = db.createStatement();
        ResultSet found =
            sql.executeQuery("select status from entity where id = '" + id + "'")) {
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
