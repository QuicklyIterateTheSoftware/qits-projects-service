package eu.wohlben.qits.entities.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * What V17 does (qits-411): {@code ck_entity_archetype} and {@code ck_audit_entity_type} both admit
 * {@code CAMPAIGN}, both still refuse a word nobody declared, and no row that stood before the
 * migration is touched. Plain JUnit on a database of its own, starting from V16 and asserting the
 * post-state, like every migration test here.
 */
class CampaignArchetypeMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  @Test
  void aCampaignIsRefusedAtV16AndAdmittedAtV17WithNoRowRewritten() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v17_campaign_archetype");
    migrateTo(url, "16");
    try (Connection db = connect(url)) {
      entity(db, "e-standing", "EPIC", 1, "REFINED");
      assertThrows(SQLException.class, () -> entity(db, "c-early", "CAMPAIGN", 2, "REPORTED"));
      assertThrows(SQLException.class, () -> audit(db, "a-early", "CAMPAIGN", "c-early"));
    }

    migrateTo(url, "17");

    try (Connection db = connect(url)) {
      assertEquals("EPIC/REFINED", archetypeAndStatusOf(db, "e-standing"), "no row is rewritten");

      entity(db, "c-1", "CAMPAIGN", 3, "REPORTED");
      audit(db, "a-1", "CAMPAIGN", "c-1");
      assertEquals("CAMPAIGN/REPORTED", archetypeAndStatusOf(db, "c-1"));

      // The vocabulary is still closed: the constraint was widened by one word, not dropped.
      assertThrows(SQLException.class, () -> entity(db, "p-1", "PROGRAMME", 4, null));
      assertThrows(SQLException.class, () -> audit(db, "a-2", "PROGRAMME", "p-1"));
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

  private static void audit(Connection db, String id, String entityType, String entityId)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into auditentry"
                + " (id, entity_type, entity_id, epic_id, operation, changed_by, changed_at)"
                + " values (?, ?, ?, ?, 'CREATE', 'tester', ?)")) {
      insert.setString(1, id);
      insert.setString(2, entityType);
      insert.setString(3, entityId);
      insert.setString(4, entityId);
      insert.setObject(5, T0);
      insert.executeUpdate();
    }
  }

  private static String archetypeAndStatusOf(Connection db, String id) throws SQLException {
    try (Statement sql = db.createStatement();
        ResultSet found =
            sql.executeQuery("select archetype, status from entity where id = '" + id + "'")) {
      assertTrue(found.next(), "no entity row " + id);
      return found.getString(1) + "/" + found.getString(2);
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
