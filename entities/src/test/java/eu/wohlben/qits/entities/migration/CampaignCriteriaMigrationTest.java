package eu.wohlben.qits.entities.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * What V19 does (qits-413): every edge standing at V18 gets an empty run record; a STRUCTURAL edge
 * may never carry one; a criterion's latch must carry its evidence, and STATE_AT_START is evidence
 * for ENTITY_STATUS alone; and the three new tables cascade from what owns them. Plain JUnit on a
 * database of its own, starting from V18 and asserting the post-state.
 */
class CampaignCriteriaMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  @Test
  void edgesStandingAtV18GetAnEmptyRunRecordAndAStructuralEdgeKeepsItEmpty() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v19_run_record");
    migrateTo(url, "18");
    try (Connection db = connect(url)) {
      fixture(db);
    }
    migrateTo(url, "19");
    try (Connection db = connect(url)) {
      assertEquals(
          2,
          count(
              db,
              "select count(*) from entity_membership where claimed_at is null"
                  + " and not joined_running and dispatch_error is null"));
      assertThrows(
          SQLException.class,
          () -> update(db, "update entity_membership set joined_running = true where id = 'f-1'"));
      assertThrows(
          SQLException.class,
          () -> update(db, "update entity_membership set claimed_at = now() where id = 'f-1'"));
      update(
          db,
          "update entity_membership set claimed_at = now(), joined_running = true,"
              + " dispatch_error = 'x' where id = 'm-1'");
    }
  }

  @Test
  void aLatchCarriesItsEvidence() throws Exception {
    try (Connection db = migrated("qp_epics_v19_evidence")) {
      fixture(db);
      update(
          db,
          "insert into campaign_criterion_group (id, membership_id, position, created_at)"
              + " values ('g-1', 'm-1', 0, now())");
      criterion(db, "k-open", "ENTITY_STATUS", "null, null, null, null");
      criterion(db, "k-event", "SCM_RELEASE", "now(), gen_random_uuid(), 'SCMRelease', null");
      criterion(db, "k-start", "ENTITY_STATUS", "now(), null, 'STATE_AT_START', null");
      criterion(db, "k-yes", "APPROVAL", "now(), null, null, 'xion'");
      assertThrows(
          SQLException.class,
          () -> criterion(db, "k-bare", "DEPLOYMENT_ACTIVE", "now(), null, null, null"));
      assertThrows(
          SQLException.class,
          () -> criterion(db, "k-start-2", "SCM_RELEASE", "now(), null, 'STATE_AT_START', null"));
      assertThrows(
          SQLException.class, () -> criterion(db, "k-nobody", "APPROVAL", "now(), null, null, null"));
      assertThrows(
          SQLException.class, () -> criterion(db, "k-kind", "WEATHER", "null, null, null, null"));
      assertEquals(4, count(db, "select count(*) from campaign_criterion"));
    }
  }

  @Test
  void theNewTablesCascadeFromWhatOwnsThem() throws Exception {
    try (Connection db = migrated("qp_epics_v19_cascade")) {
      fixture(db);
      update(
          db,
          "insert into campaign_criterion_group (id, membership_id, position, created_at)"
              + " values ('g-1', 'm-1', 0, now())");
      criterion(db, "k-1", "APPROVAL", "null, null, null, null");
      update(
          db,
          "insert into campaign_start (campaign_id, first_started_at, started_at, started_by, active)"
              + " values ('c-1', now(), now(), 'xion', true)");

      update(db, "delete from entity_membership where id = 'm-1'");
      assertEquals(0, count(db, "select count(*) from campaign_criterion_group"));
      assertEquals(0, count(db, "select count(*) from campaign_criterion"));

      update(db, "delete from entity where id = 'c-1'");
      assertEquals(0, count(db, "select count(*) from campaign_start"));
    }
  }

  // ---- fixtures ---------------------------------------------------------------------------------

  /** An epic holding a feature (STRUCTURAL f-1), and a campaign gathering the epic (CAMPAIGN m-1). */
  private static void fixture(Connection db) throws SQLException {
    update(
        db,
        "insert into entity (id, project_id, archetype, title, slug, slug_scope, number, status,"
            + " created_at, updated_at) values"
            + " ('e-1', 'p', 'EPIC', 'e', 'e', 'p', 1, 'REPORTED', now(), now()),"
            + " ('f-1', 'p', 'FEATURE', 'f', 'f', 'e-1', 2, null, now(), now()),"
            + " ('c-1', 'p', 'CAMPAIGN', 'c', 'c', 'p', 3, 'REPORTED', now(), now())");
    update(
        db,
        "insert into entity_membership (id, parent_id, child_id, position, created_at, updated_at,"
            + " kind) values ('f-1', 'e-1', 'f-1', 0, now(), now(), 'STRUCTURAL'),"
            + " ('m-1', 'c-1', 'e-1', 0, now(), now(), 'CAMPAIGN')");
  }

  /** {@code latch} is {@code satisfied_at, evidence_event_id, evidence_signature, approved_by}. */
  private static void criterion(Connection db, String id, String kind, String latch)
      throws SQLException {
    update(
        db,
        "insert into campaign_criterion (id, group_id, position, kind, predicate, satisfied_at,"
            + " evidence_event_id, evidence_signature, approved_by, created_at) values ('"
            + id
            + "', 'g-1', 0, '"
            + kind
            + "', '{}', "
            + latch
            + ", now())");
  }

  private static void update(Connection db, String sql) throws SQLException {
    try (Statement statement = db.createStatement()) {
      statement.executeUpdate(sql);
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
    migrateTo(url, "19");
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
