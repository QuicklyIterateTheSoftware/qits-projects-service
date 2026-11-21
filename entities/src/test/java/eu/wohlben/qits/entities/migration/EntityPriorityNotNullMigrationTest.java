package eu.wohlben.qits.entities.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * What V44 does (qits-2496): a row with no priority gets 0, a ranked row keeps its number, a row
 * inserted without naming the column gets 0, and a null is refused.
 */
class EntityPriorityNotNullMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  @Test
  void everyRowHoldsAPriorityZeroByDefault() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v44_priority_not_null");
    migrateTo(url, "43");
    try (Connection db = connect(url)) {
      entity(db, "t-unranked", "TICKET", 1, "REPORTED");
      entity(db, "t-ranked", "TICKET", 2, "REPORTED");
      entity(db, "t-negative", "TICKET", 3, "REPORTED");
      try (Statement sql = db.createStatement()) {
        sql.executeUpdate("update entity set priority = 5 where id = 't-ranked'");
        sql.executeUpdate("update entity set priority = -1 where id = 't-negative'");
      }
    }

    migrateTo(url, "44");

    try (Connection db = connect(url)) {
      assertEquals(0, priorityOf(db, "t-unranked"), "an unranked row gets 0");
      assertEquals(5, priorityOf(db, "t-ranked"), "a ranked row keeps its number");
      assertEquals(-1, priorityOf(db, "t-negative"), "a negative row keeps its number");

      entity(db, "t-new", "TICKET", 4, "REPORTED");
      assertEquals(0, priorityOf(db, "t-new"), "a new row starts at 0");

      try (Statement sql = db.createStatement()) {
        assertThrows(
            java.sql.SQLException.class,
            () -> sql.executeUpdate("update entity set priority = null where id = 't-new'"),
            "a null priority is refused");
      }
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

  private static Object priorityOf(Connection db, String id) throws Exception {
    try (Statement sql = db.createStatement();
        ResultSet found = sql.executeQuery("select priority from entity where id = '" + id + "'")) {
      assertTrue(found.next(), "no entity row " + id);
      return found.getObject(1);
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
