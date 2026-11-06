package eu.wohlben.qits.entities.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.testdb.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * What V42 does (epic qits-1899, qits-1908): the six tables V41 left standing for the draining old
 * instance go — {@code campaign_membership_milestone}, {@code work_milestone}, {@code
 * milestone_criterion}, {@code milestone_group}, {@code milestone}, {@code campaign_start} — along
 * with {@code entity_membership}'s nine run-record columns, its {@code
 * ck_entity_membership_run_record_campaign_only} check, and {@code entity.depends_on_entity_id}
 * (and the foreign key that rode with it, {@code fk_entity_depends_on}). This is the "next
 * migration" V41's header named. Plain JUnit on a database of its own, migrated straight to V42.
 */
class RetiredSchemaDropMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  @Test
  void theSixTablesAndTheNineColumnsAndDependsOnAreGone() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v42_retired_schema_drop");
    migrateTo(url, "42");

    try (Connection db = connect(url)) {
      for (String table :
          new String[] {
            "campaign_membership_milestone",
            "work_milestone",
            "milestone_criterion",
            "milestone_group",
            "milestone",
            "campaign_start"
          }) {
        assertNull(text(db, "select to_regclass('" + table + "')::text"), table + " should be gone");
      }

      for (String column :
          new String[] {
            "claimed_at",
            "joined_running",
            "dispatched_at",
            "dispatch_workspace_id",
            "dispatch_branch",
            "dispatch_agent_launch",
            "dispatch_refusal",
            "dispatch_refused_at",
            "dispatch_error"
          }) {
        assertEquals(
            0,
            count(
                db,
                "select count(*) from information_schema.columns where table_name ="
                    + " 'entity_membership' and column_name = '"
                    + column
                    + "'"),
            column + " should be gone from entity_membership");
      }

      assertEquals(
          0,
          count(
              db,
              "select count(*) from information_schema.columns where table_name = 'entity'"
                  + " and column_name = 'depends_on_entity_id'"));
      assertEquals(
          0,
          count(
              db,
              "select count(*) from information_schema.table_constraints where constraint_name ="
                  + " 'ck_entity_membership_run_record_campaign_only'"));
      assertEquals(
          0,
          count(
              db,
              "select count(*) from information_schema.table_constraints where constraint_name ="
                  + " 'fk_entity_depends_on'"));
    }
  }

  @Test
  void aStructuralMembershipAndAnEntityStillInsertAndRead() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v42_structural_still_works");
    migrateTo(url, "42");

    try (Connection db = connect(url)) {
      update(
          db,
          "insert into entity (id, project_id, archetype, title, slug, slug_scope, number, status,"
              + " ticket_type, impetus, created_at, updated_at) values"
              + " ('e-1', 'p', 'EPIC', 'e-1', 'e-1', 'p', 1, 'REFINED', null, null, now(), now())");
      update(
          db,
          "insert into entity (id, project_id, archetype, title, slug, slug_scope, number, status,"
              + " ticket_type, impetus, created_at, updated_at) values"
              + " ('f-1', 'p', 'FEATURE', 'f-1', 'f-1', 'e-1', 2, 'REPORTED', null, null, now(),"
              + " now())");
      update(
          db,
          "insert into entity_membership (id, parent_id, child_id, position, created_at,"
              + " updated_at, kind) values ('f-1', 'e-1', 'f-1', 0, now(), now(), 'STRUCTURAL')");

      assertEquals(1, count(db, "select count(*) from entity where id = 'e-1'"));
      assertEquals(
          "e-1",
          text(db, "select parent_id from entity_membership where child_id = 'f-1'"));
    }
  }

  private static int count(Connection db, String query) throws SQLException {
    try (Statement sql = db.createStatement();
        ResultSet found = sql.executeQuery(query)) {
      assertTrue(found.next());
      return found.getInt(1);
    }
  }

  private static String text(Connection db, String query) throws SQLException {
    try (Statement sql = db.createStatement();
        ResultSet found = sql.executeQuery(query)) {
      assertTrue(found.next(), "no row for " + query);
      return found.getString(1);
    }
  }

  private static void update(Connection db, String sql) throws SQLException {
    try (Statement statement = db.createStatement()) {
      statement.executeUpdate(sql);
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
