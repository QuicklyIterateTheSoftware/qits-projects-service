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
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * What V26 does (qits-887, qits-934): a table of ordered acceptance criteria, keyed by entity and
 * position, that goes with its entity, holds non-blank text only, and adds nothing to the rows
 * already there. Plain JUnit, Flyway driven directly on a database of its own.
 */
class AcceptanceCriterionMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  @Test
  void criteriaAreOrderedRowsOfTheirEntityAndGoWithIt() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v26_criteria");
    migrateTo(url, "25");
    try (Connection db = connect(url)) {
      entity(db, "e-before", "EPIC", 1, "REFINED");
    }

    migrateTo(url, "26");

    try (Connection db = connect(url)) {
      // No backfill: an entity that existed has no criteria.
      assertEquals(List.of(), criteriaOf(db, "e-before"));

      entity(db, "e-1", "EPIC", 2, "REPORTED");
      criterion(db, "e-1", 1, "The second.");
      criterion(db, "e-1", 0, "The first.");
      assertEquals(List.of("The first.", "The second."), criteriaOf(db, "e-1"));

      assertTrue(
          refuses(() -> criterion(db, "e-1", 0, "Another first.")),
          "one item per position: (entity_id, position) is the key");
      assertTrue(refuses(() -> criterion(db, "e-1", 2, "   ")), "a blank item is refused");
      assertTrue(refuses(() -> criterion(db, "e-1", 3, null)), "a null item is refused");
      assertTrue(
          refuses(() -> criterion(db, "nobody", 0, "Orphan.")),
          "an item belongs to an entity that exists");

      try (Statement sql = db.createStatement()) {
        sql.executeUpdate("delete from entity where id = 'e-1'");
      }
      assertEquals(List.of(), criteriaOf(db, "e-1"), "the criteria go with their entity");
    }
  }

  private static void criterion(Connection db, String entityId, int position, String text)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into entity_acceptance_criterion (entity_id, position, text) values (?, ?, ?)")) {
      insert.setString(1, entityId);
      insert.setInt(2, position);
      insert.setString(3, text);
      insert.executeUpdate();
    }
  }

  private static List<String> criteriaOf(Connection db, String entityId) throws SQLException {
    List<String> items = new ArrayList<>();
    try (Statement sql = db.createStatement();
        ResultSet found =
            sql.executeQuery(
                "select text from entity_acceptance_criterion where entity_id = '"
                    + entityId
                    + "' order by position")) {
      while (found.next()) {
        items.add(found.getString(1));
      }
    }
    return items;
  }

  // ---- the pieces ------------------------------------------------------------------------------

  private static boolean refuses(Write write) {
    try {
      write.run();
      return false;
    } catch (SQLException expected) {
      return true;
    }
  }

  private interface Write {
    void run() throws SQLException;
  }

  private static void entity(
      Connection db, String id, String archetype, long number, String status)
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
