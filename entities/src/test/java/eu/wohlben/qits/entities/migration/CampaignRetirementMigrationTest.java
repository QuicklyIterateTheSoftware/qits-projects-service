package eu.wohlben.qits.entities.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * What V41 does (epic qits-1899, qits-1908): the CAMPAIGN rows go with everything hanging off them —
 * their gathering edges, their start, their thread, their tags, their milestone waits — while the
 * work they gathered, the tree it hangs in and the audit history stay; a pointer at a campaign is
 * set null; and from then on neither the archetype nor the edge kind admits CAMPAIGN, while {@code
 * ck_audit_entity_type} still does. Nothing is dropped yet: the retired tables and columns stay for
 * the draining old instance, and go one release later (AGENTS.md, "Expand/contract"). Plain JUnit on
 * a database of its own, from V40 to V41.
 */
class CampaignRetirementMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  @Test
  void theCampaignsGoWithWhatHangsOffThemAndTheWorkTheyGatheredStays() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v41_campaign_retirement");
    migrateTo(url, "40");
    try (Connection db = connect(url)) {
      fixture(db);
    }

    migrateTo(url, "41");

    try (Connection db = connect(url)) {
      assertEquals(0, count(db, "select count(*) from entity where archetype = 'CAMPAIGN'"));
      assertEquals(0, count(db, "select count(*) from entity_membership where kind = 'CAMPAIGN'"));
      assertEquals(0, count(db, "select count(*) from campaign_start"));
      assertEquals(0, count(db, "select count(*) from entity_comment where entity_id = 'c-1'"));
      assertEquals(0, count(db, "select count(*) from entity_tag where entity_id = 'c-1'"));
      assertEquals(0, count(db, "select count(*) from work_milestone where entity_id = 'c-1'"));
      assertEquals(0, count(db, "select count(*) from campaign_membership_milestone"));

      // The work the campaign gathered stays, in its tree, with its own thread and waits.
      assertEquals(4, count(db, "select count(*) from entity"));
      assertEquals(
          "e-1", text(db, "select parent_id from entity_membership where child_id = 'f-1'"));
      assertEquals(1, count(db, "select count(*) from entity_comment where entity_id = 't-1'"));
      assertEquals(1, count(db, "select count(*) from work_milestone where entity_id = 't-1'"));
      assertEquals(1, count(db, "select count(*) from milestone"));

      // A pointer at a campaign is set null by its own foreign key, as a delete always did.
      assertNull(text(db, "select depends_on_entity_id from entity where id = 't-1'"));
      assertNull(text(db, "select superseded_by_entity_id from entity where id = 't-2'"));

      // The history stays, and still admits the word it is written in.
      assertEquals(1, count(db, "select count(*) from auditentry where entity_id = 'c-1'"));
      update(
          db,
          "insert into auditentry (id, entity_type, entity_id, epic_id, operation, changed_at)"
              + " values ('a-2', 'CAMPAIGN', 'c-2', 'c-2', 'DELETE', now())");
    }
  }

  @Test
  void neitherTheArchetypeNorTheEdgeKindAdmitsACampaignAnyMore() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v41_vocabulary");
    migrateTo(url, "41");
    try (Connection db = connect(url)) {
      assertThrows(
          SQLException.class,
          () -> entity(db, "c-9", "CAMPAIGN", 9, "REPORTED"),
          "ck_entity_archetype no longer names CAMPAIGN");
      for (String archetype : new String[] {"EPIC", "TICKET", "ISSUE"}) {
        entity(db, archetype.toLowerCase() + "-1", archetype, archetypeNumber(archetype),
            archetype.equals("ISSUE") ? "OPEN" : "REPORTED");
      }
      assertThrows(
          SQLException.class,
          () -> edge(db, "m-9", "epic-1", "ticket-1", "CAMPAIGN"),
          "ck_entity_membership_kind no longer names CAMPAIGN");
      assertNull(text(db, "select to_regclass('uq_entity_membership_campaign_child')::text"));
      // A tree edge inserted naming no retired column still lands: every one is nullable or
      // defaulted until the next migration drops them.
      entity(db, "f-9", "FEATURE", 7, "REPORTED");
      edge(db, "f-9", "epic-1", "f-9", "STRUCTURAL");
      assertEquals(
          "false", text(db, "select joined_running::text from entity_membership where id = 'f-9'"));
      // Not dropped yet: the draining old instance still reads them.
      assertNotNull(text(db, "select to_regclass('milestone')::text"));
      assertNotNull(text(db, "select to_regclass('campaign_start')::text"));
      assertEquals(
          1,
          count(
              db,
              "select count(*) from information_schema.columns where table_name = 'entity'"
                  + " and column_name = 'depends_on_entity_id'"));
    }
  }

  // ---- fixtures ---------------------------------------------------------------------------------

  /**
   * An epic with a feature, two tickets, and a campaign gathering the feature and the first ticket:
   * started, commented on, tagged, waiting on a milestone, named by the first ticket's dependency
   * and the second ticket's drop, and audited.
   */
  private static void fixture(Connection db) throws SQLException {
    entity(db, "e-1", "EPIC", 1, "REFINED");
    entity(db, "f-1", "FEATURE", 2, "REFINED");
    entity(db, "t-1", "TICKET", 3, "REFINED");
    entity(db, "t-2", "TICKET", 4, "DROPPED");
    entity(db, "c-1", "CAMPAIGN", 5, "REFINED");
    edge(db, "f-1", "e-1", "f-1", "STRUCTURAL");
    update(
        db,
        "insert into entity_membership (id, parent_id, child_id, position, created_at, updated_at,"
            + " kind, claimed_at, joined_running) values"
            + " ('m-1', 'c-1', 'f-1', 0, now(), now(), 'CAMPAIGN', now(), true),"
            + " ('m-2', 'c-1', 't-1', 1, now(), now(), 'CAMPAIGN', null, false)");
    update(
        db,
        "insert into campaign_start (campaign_id, first_started_at, started_at, started_by, active)"
            + " values ('c-1', now(), now(), 'xion', true)");
    update(
        db,
        "insert into entity_comment (id, entity_id, author, body, created_at, updated_at) values"
            + " ('k-1', 'c-1', 'xion', 'the order', now(), now()),"
            + " ('k-2', 't-1', 'xion', 'the work', now(), now())");
    update(db, "insert into entity_tag (entity_id, tag) values ('c-1', 'RESERVED_FOR_HUMAN')");
    update(
        db,
        "insert into milestone (id, project_id, display_name, created_at)"
            + " values ('ms', 'p', 'm', now())");
    update(
        db,
        "insert into work_milestone (entity_id, milestone_id, created_at) values"
            + " ('c-1', 'ms', now()), ('t-1', 'ms', now())");
    update(
        db,
        "insert into campaign_membership_milestone (membership_id, milestone_id, created_at)"
            + " values ('m-2', 'ms', now())");
    update(db, "update entity set depends_on_entity_id = 'c-1' where id = 't-1'");
    update(
        db,
        "update entity set superseded_by_entity_id = 'c-1', outcome = 'RESOLVED_ELSEWHERE'"
            + " where id = 't-2'");
    update(
        db,
        "insert into auditentry (id, entity_type, entity_id, epic_id, operation, changed_at)"
            + " values ('a-1', 'CAMPAIGN', 'c-1', 'c-1', 'CREATE', now())");
  }

  private static int archetypeNumber(String archetype) {
    return switch (archetype) {
      case "EPIC" -> 1;
      case "TICKET" -> 2;
      default -> 3;
    };
  }

  private static void entity(Connection db, String id, String archetype, int number, String status)
      throws SQLException {
    update(
        db,
        "insert into entity (id, project_id, archetype, title, slug, slug_scope, number, status,"
            + " ticket_type, impetus, created_at, updated_at) values ('"
            + id
            + "', 'p', '"
            + archetype
            + "', '"
            + id
            + "', '"
            + id
            + "', 'p', "
            + number
            + ", '"
            + status
            + "', "
            + (archetype.equals("TICKET") ? "'BUG', 'it came up'" : "null, null")
            + ", now(), now())");
  }

  private static void edge(Connection db, String id, String parent, String child, String kind)
      throws SQLException {
    update(
        db,
        "insert into entity_membership (id, parent_id, child_id, position, created_at, updated_at,"
            + " kind) values ('"
            + id
            + "', '"
            + parent
            + "', '"
            + child
            + "', 0, now(), now(), '"
            + kind
            + "')");
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
