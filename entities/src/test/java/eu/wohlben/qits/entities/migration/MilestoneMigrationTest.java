package eu.wohlben.qits.entities.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.testdb.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
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
 * What V38 does (epic qits-615, qits-1293): every campaign membership's condition arrives as one
 * milestone named {@code Before "<member>" in "<campaign>"}, its groups and criteria keeping their
 * ids, latches and evidence; the group's date is its last criterion's latch when every one has
 * latched, the milestone's the earliest of its holding groups; and both the membership and the
 * member entity wait on it. A membership with no condition gets nothing. Plain JUnit on a database
 * of its own, from V37 to V38.
 */
class MilestoneMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final String T1 = "2026-09-27 10:00:00+00";
  private static final String T2 = "2026-09-28 11:00:00+00";
  private static final String T3 = "2026-09-29 12:00:00+00";
  private static final String EVENT = "11111111-2222-3333-4444-555555555555";

  @Test
  void everyConditionArrivesAsOneMilestoneWithItsIdsLatchesEvidenceAndDates() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v38_milestone");
    migrateTo(url, "37");
    try (Connection db = connect(url)) {
      fixture(db);
    }
    migrateTo(url, "38");
    try (Connection db = connect(url)) {
      // m-held: two OR'd groups. g-a holds (both latched, the later at T3); g-b holds since T2
      // (one approval). The milestone holds since the earlier, T2, through g-b.
      String held = milestoneOf(db, "m-held");
      assertEquals(
          "Before \"Held\" in \"The order\"",
          text(db, "select display_name from milestone where id = '" + held + "'"));
      assertEquals("p", text(db, "select project_id from milestone where id = '" + held + "'"));
      assertEquals(
          T2, instant(db, "select satisfied_at from milestone where id = '" + held + "'"));
      assertEquals(
          List.of("g-a", "g-b"),
          column(db, "select id from milestone_group where milestone_id = '" + held + "' order by position"));
      assertEquals(T3, instant(db, "select satisfied_at from milestone_group where id = 'g-a'"));
      assertEquals(T2, instant(db, "select satisfied_at from milestone_group where id = 'g-b'"));

      // the criteria, with their latches and evidence, verbatim
      assertEquals(
          "g-a|ENTITY_STATUS|true|" + EVENT + "|EntityTransitioned|EntityTransitioned: #1 → VERIFIED",
          text(
              db,
              "select group_id || '|' || kind || '|' || seeded || '|' || evidence_event_id || '|'"
                  + " || evidence_signature || '|' || evidence_summary from milestone_criterion"
                  + " where id = 'k-event'"));
      assertEquals(T1, instant(db, "select satisfied_at from milestone_criterion where id = 'k-event'"));
      assertEquals(
          "STATE_AT_START|#1 was already DONE when the campaign started",
          text(
              db,
              "select evidence_signature || '|' || evidence_summary from milestone_criterion"
                  + " where id = 'k-start'"));
      assertEquals(T3, instant(db, "select satisfied_at from milestone_criterion where id = 'k-start'"));
      assertEquals(
          "xion|ship it",
          text(
              db,
              "select approved_by || '|' || approval_note from milestone_criterion"
                  + " where id = 'k-yes'"));
      assertEquals(
          "{\"entityId\":\"e-target\",\"status\":\"VERIFIED\"}",
          text(db, "select predicate from milestone_criterion where id = 'k-event'"));

      // m-open: one group, one latched and one open criterion — nothing holds
      String open = milestoneOf(db, "m-open");
      assertNull(instant(db, "select satisfied_at from milestone where id = '" + open + "'"));
      assertNull(instant(db, "select satisfied_at from milestone_group where id = 'g-c'"));
      assertNull(instant(db, "select satisfied_at from milestone_criterion where id = 'k-open'"));
      assertEquals(T1, instant(db, "select satisfied_at from milestone_criterion where id = 'k-half'"));

      // both attachments: the membership waits on it as its generated condition, the member too
      assertEquals(
          "true",
          text(
              db,
              "select generated_condition::text from campaign_membership_milestone"
                  + " where membership_id = 'm-held'"));
      assertEquals(
          held,
          text(db, "select milestone_id from work_milestone where entity_id = 'e-held'"));
      assertEquals(
          open,
          text(db, "select milestone_id from work_milestone where entity_id = 'e-open'"));

      // no condition, no milestone; and the campaign-era tables are untouched
      assertEquals(
          0, count(db, "select count(*) from campaign_membership_milestone where membership_id = 'm-free'"));
      assertEquals(2, count(db, "select count(*) from milestone"));
      assertEquals(3, count(db, "select count(*) from campaign_criterion_group"));
      assertEquals(5, count(db, "select count(*) from campaign_criterion"));
    }
  }

  @Test
  void aLatchCarriesItsEvidenceAndCurrentStateIsEvidenceForEntityStatusAlone() throws Exception {
    try (Connection db = migrated("qp_epics_v38_evidence")) {
      update(db, "insert into milestone (id, project_id, display_name, created_at) values ('ms', 'p', 'm', now())");
      update(db, "insert into milestone_group (id, milestone_id, position, created_at) values ('g', 'ms', 0, now())");
      criterion(db, "k-state", "ENTITY_STATUS", "now(), null, 'CURRENT_STATE', null");
      criterion(db, "k-start", "ENTITY_STATUS", "now(), null, 'STATE_AT_START', null");
      criterion(db, "k-event", "SCM_RELEASE", "now(), gen_random_uuid(), 'ReleaseRequestStateChanged', null");
      criterion(db, "k-yes", "APPROVAL", "now(), null, null, 'xion'");
      assertThrows(
          SQLException.class,
          () -> criterion(db, "k-bad", "DEPLOYMENT_ACTIVE", "now(), null, 'CURRENT_STATE', null"));
      assertThrows(
          SQLException.class, () -> criterion(db, "k-nobody", "APPROVAL", "now(), null, null, null"));
      assertThrows(
          SQLException.class, () -> criterion(db, "k-kind", "WEATHER", "null, null, null, null"));
      assertEquals(4, count(db, "select count(*) from milestone_criterion"));
    }
  }

  @Test
  void v39DropsBothCampaignCriterionTablesAndLeavesWhatV38MigratedUntouched() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v39_retire");
    migrateTo(url, "37");
    try (Connection db = connect(url)) {
      fixture(db);
    }
    migrateTo(url, "38");
    String held;
    try (Connection db = connect(url)) {
      // the same facts the first test pins at V38, read before V39 runs
      held = milestoneOf(db, "m-held");
      assertEquals(
          "Before \"Held\" in \"The order\"",
          text(db, "select display_name from milestone where id = '" + held + "'"));
      assertEquals(2, count(db, "select count(*) from milestone"));
      assertEquals(3, count(db, "select count(*) from milestone_group"));
      assertEquals(5, count(db, "select count(*) from milestone_criterion"));
      assertEquals(3, count(db, "select count(*) from campaign_criterion_group"));
      assertEquals(5, count(db, "select count(*) from campaign_criterion"));
    }

    migrateTo(url, "39");

    try (Connection db = connect(url)) {
      // both campaign-era tables are gone outright, not merely empty
      assertNull(text(db, "select to_regclass('campaign_criterion')::text"));
      assertNull(text(db, "select to_regclass('campaign_criterion_group')::text"));
      assertEquals(
          0,
          count(
              db,
              "select count(*) from information_schema.tables where table_name in"
                  + " ('campaign_criterion', 'campaign_criterion_group')"));

      // the milestone rows V38 migrated are untouched by the drop
      assertEquals(
          "Before \"Held\" in \"The order\"",
          text(db, "select display_name from milestone where id = '" + held + "'"));
      assertEquals(2, count(db, "select count(*) from milestone"));
      assertEquals(3, count(db, "select count(*) from milestone_group"));
      assertEquals(5, count(db, "select count(*) from milestone_criterion"));
      assertEquals(
          "true",
          text(
              db,
              "select generated_condition::text from campaign_membership_milestone"
                  + " where membership_id = 'm-held'"));
      assertEquals(
          held,
          text(db, "select milestone_id from work_milestone where entity_id = 'e-held'"));
    }
  }

  @Test
  void theNewTablesCascadeFromWhatOwnsThem() throws Exception {
    try (Connection db = migrated("qp_epics_v38_cascade")) {
      entities(db);
      update(db, "insert into milestone (id, project_id, display_name, created_at) values ('ms', 'p', 'm', now())");
      update(db, "insert into milestone_group (id, milestone_id, position, created_at) values ('g', 'ms', 0, now())");
      criterion(db, "k", "APPROVAL", "null, null, null, null");
      update(db, "insert into work_milestone (entity_id, milestone_id, created_at) values ('e-held', 'ms', now())");
      update(
          db,
          "insert into campaign_membership_milestone (membership_id, milestone_id, created_at)"
              + " values ('m-held', 'ms', now())");

      update(db, "delete from entity_membership where id = 'm-held'");
      assertEquals(0, count(db, "select count(*) from campaign_membership_milestone"));
      update(db, "delete from entity where id = 'e-held'");
      assertEquals(0, count(db, "select count(*) from work_milestone"));
      assertEquals(1, count(db, "select count(*) from milestone"), "the milestone outlives its waiters");

      update(db, "delete from milestone where id = 'ms'");
      assertEquals(0, count(db, "select count(*) from milestone_group"));
      assertEquals(0, count(db, "select count(*) from milestone_criterion"));
    }
  }

  // ---- fixtures ---------------------------------------------------------------------------------

  /** A campaign gathering a target and three members: two with a condition, one without. */
  private static void entities(Connection db) throws SQLException {
    update(
        db,
        "insert into entity (id, project_id, archetype, title, slug, slug_scope, number, status,"
            + " created_at, updated_at) values"
            + " ('e-target', 'p', 'TICKET', 'Target', 'target', 'p', 1, 'DONE', now(), now()),"
            + " ('e-held', 'p', 'TICKET', 'Held', 'held', 'p', 2, 'REFINED', now(), now()),"
            + " ('e-open', 'p', 'TICKET', 'Open', 'open', 'p', 3, 'REFINED', now(), now()),"
            + " ('e-free', 'p', 'TICKET', 'Free', 'free', 'p', 4, 'REFINED', now(), now()),"
            + " ('c-1', 'p', 'CAMPAIGN', 'The order', 'the-order', 'p', 5, 'REPORTED', now(), now())");
    update(
        db,
        "insert into entity_membership (id, parent_id, child_id, position, created_at, updated_at,"
            + " kind) values ('m-target', 'c-1', 'e-target', 0, now(), now(), 'CAMPAIGN'),"
            + " ('m-held', 'c-1', 'e-held', 1, now(), now(), 'CAMPAIGN'),"
            + " ('m-open', 'c-1', 'e-open', 2, now(), now(), 'CAMPAIGN'),"
            + " ('m-free', 'c-1', 'e-free', 3, now(), now(), 'CAMPAIGN')");
  }

  private static void fixture(Connection db) throws SQLException {
    entities(db);
    update(
        db,
        "insert into campaign_criterion_group (id, membership_id, position, created_at) values"
            + " ('g-a', 'm-held', 0, '" + T1 + "'),"
            + " ('g-b', 'm-held', 1, '" + T1 + "'),"
            + " ('g-c', 'm-open', 0, '" + T1 + "')");
    update(
        db,
        "insert into campaign_criterion (id, group_id, position, kind, predicate, seeded,"
            + " satisfied_at, evidence_event_id, evidence_signature, evidence_summary, approved_by,"
            + " approval_note, created_at) values"
            + " ('k-event', 'g-a', 0, 'ENTITY_STATUS', '{\"entityId\":\"e-target\",\"status\":\"VERIFIED\"}',"
            + "  true, '" + T1 + "', '" + EVENT + "', 'EntityTransitioned',"
            + "  'EntityTransitioned: #1 → VERIFIED', null, null, '" + T1 + "'),"
            + " ('k-start', 'g-a', 1, 'ENTITY_STATUS', '{\"entityId\":\"e-target\",\"status\":\"DONE\"}',"
            + "  false, '" + T3 + "', null, 'STATE_AT_START',"
            + "  '#1 was already DONE when the campaign started', null, null, '" + T1 + "'),"
            + " ('k-yes', 'g-b', 0, 'APPROVAL', '{}', false, '" + T2 + "', null, null, null,"
            + "  'xion', 'ship it', '" + T1 + "'),"
            + " ('k-half', 'g-c', 0, 'APPROVAL', '{}', false, '" + T1 + "', null, null, null,"
            + "  'xion', null, '" + T1 + "'),"
            + " ('k-open', 'g-c', 1, 'SCM_RELEASE', '{\"repositoryName\":\"qits-qits\"}', false,"
            + "  null, null, null, null, null, null, '" + T1 + "')");
  }

  /** {@code latch} is {@code satisfied_at, evidence_event_id, evidence_signature, approved_by}. */
  private static void criterion(Connection db, String id, String kind, String latch)
      throws SQLException {
    update(
        db,
        "insert into milestone_criterion (id, group_id, position, kind, predicate, satisfied_at,"
            + " evidence_event_id, evidence_signature, approved_by, created_at) values ('"
            + id
            + "', 'g', 0, '"
            + kind
            + "', '{}', "
            + latch
            + ", now())");
  }

  private static String milestoneOf(Connection db, String membershipId) throws SQLException {
    return text(
        db,
        "select milestone_id from campaign_membership_milestone where membership_id = '"
            + membershipId
            + "'");
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

  private static String text(Connection db, String query) throws SQLException {
    try (Statement sql = db.createStatement();
        ResultSet found = sql.executeQuery(query)) {
      assertTrue(found.next(), "no row for " + query);
      return found.getString(1);
    }
  }

  /** The instant as {@code yyyy-MM-dd HH:mm:ss+00}, or null. */
  private static String instant(Connection db, String query) throws SQLException {
    try (Statement sql = db.createStatement();
        ResultSet found = sql.executeQuery(query)) {
      assertTrue(found.next(), "no row for " + query);
      OffsetDateTime value = found.getObject(1, OffsetDateTime.class);
      if (value == null) {
        return null;
      }
      return value
          .withOffsetSameInstant(java.time.ZoneOffset.UTC)
          .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
          + "+00";
    }
  }

  private static List<String> column(Connection db, String query) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Statement sql = db.createStatement();
        ResultSet found = sql.executeQuery(query)) {
      while (found.next()) {
        values.add(found.getString(1));
      }
    }
    return values;
  }

  private static Connection migrated(String database) throws Exception {
    String url = EmbeddedPg.url(database);
    migrateTo(url, "38");
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
