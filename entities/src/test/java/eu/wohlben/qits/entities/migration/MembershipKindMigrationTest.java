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
 * What V18 does (qits-412): {@code entity_membership} gains a {@code kind}; every edge that stood at
 * V17 is STRUCTURAL; the one-parent rule holds for STRUCTURAL edges only (a partial unique index of
 * the old constraint's name); and a child joins a given campaign once. Plain JUnit on a database of
 * its own, starting from V17 and asserting the post-state, like every migration test here.
 */
class MembershipKindMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  @Test
  void everyEdgeStandingAtV17IsStructuralAfterV18() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v18_kind_backfill");
    migrateTo(url, "17");
    try (Connection db = connect(url)) {
      tree(db);
    }

    migrateTo(url, "18");

    try (Connection db = connect(url)) {
      assertEquals(2, count(db, "select count(*) from entity_membership"));
      assertEquals(
          2, count(db, "select count(*) from entity_membership where kind = 'STRUCTURAL'"));
      // The column default is what a writer that says nothing gets — the tree.
      edge(db, "t-2", null, "f-1", "t-2", 1);
      assertEquals("STRUCTURAL", kindOf(db, "t-2"));
      assertThrows(SQLException.class, () -> edge(db, "x-1", "SIDEWAYS", "e-1", "c-1", 0));
    }
  }

  @Test
  void aSecondStructuralParentIsStillRefused() throws Exception {
    try (Connection db = migrated("qp_epics_v18_one_parent")) {
      tree(db);
      entity(db, "e-2", "EPIC", 5);
      assertThrows(
          SQLException.class, () -> edge(db, "f-1-again", "STRUCTURAL", "e-2", "f-1", 0));
      assertEquals(1, count(db, "select count(*) from entity_membership where child_id = 'f-1'"));
    }
  }

  @Test
  void aStructuralAndACampaignEdgeForOneChildCoexist() throws Exception {
    try (Connection db = migrated("qp_epics_v18_coexist")) {
      tree(db);
      entity(db, "c-2", "CAMPAIGN", 5);
      edge(db, "m-1", "CAMPAIGN", "c-1", "f-1", 0);
      edge(db, "m-2", "CAMPAIGN", "c-2", "f-1", 0);
      assertEquals(3, count(db, "select count(*) from entity_membership where child_id = 'f-1'"));
      assertEquals(
          1,
          count(
              db,
              "select count(*) from entity_membership where child_id = 'f-1'"
                  + " and kind = 'STRUCTURAL'"));
    }
  }

  @Test
  void aChildJoinsAGivenCampaignOnce() throws Exception {
    try (Connection db = migrated("qp_epics_v18_campaign_once")) {
      tree(db);
      edge(db, "m-1", "CAMPAIGN", "c-1", "f-1", 0);
      assertThrows(SQLException.class, () -> edge(db, "m-2", "CAMPAIGN", "c-1", "f-1", 1));
      assertEquals(
          1, count(db, "select count(*) from entity_membership where kind = 'CAMPAIGN'"));
    }
  }

  // ---- fixtures ---------------------------------------------------------------------------------

  /** An epic holding a feature holding a task, plus a campaign — two STRUCTURAL-to-be edges. */
  private static void tree(Connection db) throws SQLException {
    entity(db, "e-1", "EPIC", 1);
    entity(db, "f-1", "FEATURE", 2);
    entity(db, "t-1", "TASK", 3);
    entity(db, "t-2", "TASK", 4);
    entity(db, "c-1", "CAMPAIGN", 6);
    try (Statement sql = db.createStatement()) {
      sql.executeUpdate(
          "insert into entity_membership (id, parent_id, child_id, position, created_at, updated_at)"
              + " values ('f-1', 'e-1', 'f-1', 0, now(), now()),"
              + " ('t-1', 'f-1', 't-1', 0, now(), now())");
    }
  }

  private static void entity(Connection db, String id, String archetype, long number)
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
      insert.setString(
          6, "FEATURE".equals(archetype) || "TASK".equals(archetype) ? null : "REPORTED");
      insert.setObject(7, T0);
      insert.setObject(8, T0);
      insert.executeUpdate();
    }
  }

  /** An edge of {@code kind}, or of the column's default when {@code kind} is null. */
  private static void edge(
      Connection db, String id, String kind, String parentId, String childId, int position)
      throws SQLException {
    String sql =
        kind == null
            ? "insert into entity_membership (id, parent_id, child_id, position, created_at,"
                + " updated_at) values (?, ?, ?, ?, now(), now())"
            : "insert into entity_membership (id, parent_id, child_id, position, created_at,"
                + " updated_at, kind) values (?, ?, ?, ?, now(), now(), ?)";
    try (PreparedStatement insert = db.prepareStatement(sql)) {
      insert.setString(1, id);
      insert.setString(2, parentId);
      insert.setString(3, childId);
      insert.setInt(4, position);
      if (kind != null) {
        insert.setString(5, kind);
      }
      insert.executeUpdate();
    }
  }

  private static String kindOf(Connection db, String id) throws SQLException {
    try (Statement sql = db.createStatement();
        ResultSet found =
            sql.executeQuery("select kind from entity_membership where id = '" + id + "'")) {
      assertTrue(found.next(), "no edge " + id);
      return found.getString(1);
    }
  }

  private static int count(Connection db, String query) throws SQLException {
    try (Statement sql = db.createStatement();
        ResultSet found = sql.executeQuery(query)) {
      assertTrue(found.next());
      return found.getInt(1);
    }
  }

  private static Connection migrated(String database) throws Exception {
    String url = EmbeddedPg.url(database);
    migrateTo(url, "18");
    return connect(url);
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
