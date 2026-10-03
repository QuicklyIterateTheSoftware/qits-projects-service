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
 * What V24 does (qits-763): every FEATURE and TASK row, which held no status until then, is given
 * the one its markers and its epic imply, and {@code ck_entity_status} stops admitting null. Each
 * branch of the rule has a row of its own — implemented, implementing, both, the nearest ancestor's
 * status in each of its three readings (kept, capped at REFINED, DROPPED), and no ancestor at all —
 * including the case the rule's "as it stood before" clause exists for: a task under a feature the
 * same statement gives a status to. Plain JUnit, Flyway driven directly on a database of its own.
 */
class FeatureAndTaskStatusMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-02-01T00:00:00Z");

  @Test
  void everyFeatureAndTaskTakesTheStatusItsMarkersAndItsEpicImply() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v24_backfill");
    migrateTo(url, "23");
    try (Connection db = connect(url)) {
      int n = 1;
      // An epic far along the walk: its unmarked pieces are capped at REFINED, its marked ones
      // read their markers.
      entity(db, "e-done", "EPIC", n++, "DONE", null, null);
      entity(db, "f-done", "FEATURE", n++, null, null, null);
      edge(db, "f-done", "e-done", 0);
      entity(db, "t-implemented", "TASK", n++, null, null, T1);
      edge(db, "t-implemented", "f-done", 0);
      entity(db, "t-implementing", "TASK", n++, null, T1, null);
      edge(db, "t-implementing", "f-done", 1);
      entity(db, "t-both", "TASK", n++, null, T0, T1);
      edge(db, "t-both", "f-done", 2);
      entity(db, "t-capped", "TASK", n++, null, null, null);
      edge(db, "t-capped", "f-done", 3);

      // A draft: its pieces are drafts — and a task does not inherit the IMPLEMENTED its feature's
      // own marker earns in the same statement: the ancestor is read as it stood before V24.
      entity(db, "e-reported", "EPIC", n++, "REPORTED", null, null);
      entity(db, "f-reported", "FEATURE", n++, null, null, null);
      edge(db, "f-reported", "e-reported", 0);
      entity(db, "f-marked", "FEATURE", n++, null, null, T1);
      edge(db, "f-marked", "e-reported", 1);
      entity(db, "t-under-marked", "TASK", n++, null, null, null);
      edge(db, "t-under-marked", "f-marked", 0);

      // A refined plan: REFINED is kept, not capped.
      entity(db, "e-refined", "EPIC", n++, "REFINED", null, null);
      entity(db, "f-refined", "FEATURE", n++, null, null, null);
      edge(db, "f-refined", "e-refined", 0);
      entity(db, "t-refined", "TASK", n++, null, null, null);
      edge(db, "t-refined", "f-refined", 0);

      // An epic on IMPLEMENTING, the cap's nearest case.
      entity(db, "e-implementing", "EPIC", n++, "IMPLEMENTING", null, null);
      entity(db, "f-under-implementing", "FEATURE", n++, null, null, null);
      edge(db, "f-under-implementing", "e-implementing", 0);

      // A plan decided against: its pieces were too — unless a marker says the work was done.
      entity(db, "e-dropped", "EPIC", n++, "DROPPED", null, null);
      entity(db, "f-dropped", "FEATURE", n++, null, null, null);
      edge(db, "f-dropped", "e-dropped", 0);
      entity(db, "t-dropped", "TASK", n++, null, null, null);
      edge(db, "t-dropped", "f-dropped", 0);
      entity(db, "t-dropped-but-done", "TASK", n++, null, null, T1);
      edge(db, "t-dropped-but-done", "f-dropped", 1);

      // No ancestor with a status: a feature standing alone, and a task whose only edge is a
      // campaign's — a campaign gathers work, it does not contain it, so its status is not read.
      entity(db, "f-orphan", "FEATURE", n++, null, null, null);
      entity(db, "c-done", "CAMPAIGN", n++, "DONE", null, null);
      entity(db, "t-gathered", "TASK", n++, null, null, null);
      campaignEdge(db, "m-1", "c-done", "t-gathered", 0);

      entity(db, "k-verified", "TICKET", n++, "VERIFIED", null, null);
    }

    migrateTo(url, "24");

    try (Connection db = connect(url)) {
      assertEquals("REFINED", statusOf(db, "f-done"), "an unmarked piece is capped at REFINED");
      assertEquals("IMPLEMENTED", statusOf(db, "t-implemented"));
      assertEquals("IMPLEMENTING", statusOf(db, "t-implementing"));
      assertEquals("IMPLEMENTED", statusOf(db, "t-both"), "implemented_at ranks over implementing_at");
      assertEquals("REFINED", statusOf(db, "t-capped"), "the epic two hops up, capped");

      assertEquals("REPORTED", statusOf(db, "f-reported"));
      assertEquals("IMPLEMENTED", statusOf(db, "f-marked"));
      assertEquals(
          "REPORTED",
          statusOf(db, "t-under-marked"),
          "the nearest ancestor holding a status BEFORE V24 is the epic, not the marked feature");

      assertEquals("REFINED", statusOf(db, "f-refined"));
      assertEquals("REFINED", statusOf(db, "t-refined"));
      assertEquals("REFINED", statusOf(db, "f-under-implementing"));

      assertEquals("DROPPED", statusOf(db, "f-dropped"));
      assertEquals("DROPPED", statusOf(db, "t-dropped"));
      assertEquals("IMPLEMENTED", statusOf(db, "t-dropped-but-done"), "a marker wins over DROPPED");

      assertEquals("REPORTED", statusOf(db, "f-orphan"));
      assertEquals("REPORTED", statusOf(db, "t-gathered"), "a campaign edge is not an ancestor");

      // Nothing else moved, and nobody's row reads as edited today.
      assertEquals("DONE", statusOf(db, "e-done"));
      assertEquals("DROPPED", statusOf(db, "e-dropped"));
      assertEquals("DONE", statusOf(db, "c-done"));
      assertEquals("VERIFIED", statusOf(db, "k-verified"));
      assertEquals(0, count(db, "select count(*) from entity where status is null"));
      assertEquals(0, count(db, "select count(*) from entity where updated_at <> created_at"));
    }
  }

  @Test
  void theConstraintRefusesNullAndTakesEveryWordOnAFeatureAndATask() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v24_vocabulary");
    migrateTo(url, "24");
    try (Connection db = connect(url)) {
      assertTrue(
          refuses(() -> entity(db, "f-none", "FEATURE", 1, null, null, null)),
          "after V24 a feature without a status must be refused");
      assertTrue(
          refuses(() -> entity(db, "e-none", "EPIC", 2, null, null, null)),
          "and so must any other kind");
      int number = 10;
      for (String word :
          new String[] {
            "REPORTED",
            "REFINED",
            "IMPLEMENTING",
            "IMPLEMENTED",
            "VERIFYING",
            "VERIFIED",
            "DONE",
            "DROPPED"
          }) {
        entity(db, "t-" + word, "TASK", number++, word, null, null);
        assertEquals(word, statusOf(db, "t-" + word));
      }
      long n = number;
      assertTrue(
          refuses(() -> entity(db, "t-in-progress", "TASK", n, "IN_PROGRESS", null, null)),
          "ck_entity_status must still refuse a word the lifecycle does not spell");
    }
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
      Connection db,
      String id,
      String archetype,
      long number,
      String status,
      OffsetDateTime implementingAt,
      OffsetDateTime implementedAt)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into entity"
                + " (id, project_id, archetype, title, slug, slug_scope, number, status,"
                + " implementing_at, implemented_at, created_at, updated_at)"
                + " values (?, 'proj-1', ?, ?, ?, 'proj-1', ?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, id);
      insert.setString(2, archetype);
      insert.setString(3, id);
      insert.setString(4, id);
      insert.setLong(5, number);
      insert.setString(6, status);
      insert.setObject(7, implementingAt);
      insert.setObject(8, implementedAt);
      insert.setObject(9, T0);
      insert.setObject(10, T0);
      insert.executeUpdate();
    }
  }

  /** A STRUCTURAL edge, whose id is the child's (V10's rule). */
  private static void edge(Connection db, String childId, String parentId, int position)
      throws SQLException {
    insertEdge(db, childId, "STRUCTURAL", parentId, childId, position);
  }

  private static void campaignEdge(
      Connection db, String id, String campaignId, String childId, int position)
      throws SQLException {
    insertEdge(db, id, "CAMPAIGN", campaignId, childId, position);
  }

  private static void insertEdge(
      Connection db, String id, String kind, String parentId, String childId, int position)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into entity_membership"
                + " (id, parent_id, child_id, position, created_at, updated_at, kind)"
                + " values (?, ?, ?, ?, now(), now(), ?)")) {
      insert.setString(1, id);
      insert.setString(2, parentId);
      insert.setString(3, childId);
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

  private static int count(Connection db, String query) throws SQLException {
    try (Statement sql = db.createStatement();
        ResultSet found = sql.executeQuery(query)) {
      assertTrue(found.next());
      return found.getInt(1);
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
