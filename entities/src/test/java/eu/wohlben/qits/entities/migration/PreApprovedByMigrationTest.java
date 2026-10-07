package eu.wohlben.qits.entities.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * What V27 does to a database that already has rows (qits-1075): every existing row has no
 * pre-approval, a row inserted afterwards without naming the column has none either, and a name
 * written to it reads back. Plain JUnit on a database of its own, like every migration test here.
 */
class PreApprovedByMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  @Test
  void existingRowsHaveNoPreApprovalAndANameReadsBack() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v27_pre_approved_by");
    migrateTo(url, "26");
    try (Connection db = connect(url)) {
      entity(db, "e-standing", "EPIC", 1, "REFINED");
      entity(db, "t-standing", "TICKET", 2, "REPORTED");
    }

    migrateTo(url, "27");

    try (Connection db = connect(url)) {
      assertNull(preApprovedByOf(db, "e-standing"), "nothing is invented for an existing epic");
      assertNull(preApprovedByOf(db, "t-standing"), "nor for an existing ticket");

      entity(db, "t-new", "TICKET", 3, "REPORTED");
      assertNull(preApprovedByOf(db, "t-new"), "a new row starts with no pre-approval");

      try (Statement sql = db.createStatement()) {
        sql.executeUpdate("update entity set pre_approved_by = 'dana' where id = 't-new'");
      }
      assertEquals("dana", preApprovedByOf(db, "t-new"));
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

  private static String preApprovedByOf(Connection db, String id) throws Exception {
    try (Statement sql = db.createStatement();
        ResultSet found =
            sql.executeQuery("select pre_approved_by from entity where id = '" + id + "'")) {
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
