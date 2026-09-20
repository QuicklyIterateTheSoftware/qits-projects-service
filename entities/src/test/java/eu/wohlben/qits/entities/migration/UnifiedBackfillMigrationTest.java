package eu.wohlben.qits.entities.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.testdb.EmbeddedPg;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What V10 does to the rows that were already there — the copy itself, which no other test can
 * reach, because every suite around it starts from an empty database and so proves only that the
 * statements parse.
 *
 * <p>It is a plain JUnit test and deliberately not a {@code @QuarkusTest}: a {@code @TestProfile} is
 * a whole Quarkus application at roughly 125 MB of retained metaspace inside a 4 GB CI step, and
 * nothing asserted here needs one. The shape is {@code TicketLifecycleMigrationTest}'s — stand at
 * V9, write rows in the old tables, run V10, read the new ones — on databases of its own, with the
 * module's embedded postgres so it costs no second server.
 */
class UnifiedBackfillMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  /** The migration's own text, executed a second time by the idempotence test. */
  private static final String V10_RESOURCE = "db/epics/migration/V10__backfill_unified.sql";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  private static final UUID CAUSE_EPIC = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID CAUSE_TICKET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID CAUSE_FEATURE = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID CAUSE_TASK = UUID.fromString("44444444-4444-4444-4444-444444444444");

  private static String copied;

  // ---- the copy ---------------------------------------------------------------------------------

  /**
   * One seeded estate for every assertion about the copy: two epics in one project (one superseding
   * the other), a ticket in a second project, three features under one epic and three tasks under
   * one feature — the siblings deliberately interleaved so an ordering that reads ids or insertion
   * order instead of {@code (created_at, id)} gets the positions wrong.
   */
  @BeforeAll
  static void backfillASeededEstate() throws Exception {
    copied = EmbeddedPg.url("qp_epics_v10_migration");
    migrateTo(copied, "9");

    try (Connection db = connect(copied)) {
      // Two epics, the second superseded by the first. proj-1.
      epic(db, "e-1", "proj-1", "Alpha epic", "alpha-epic", "IMPLEMENTATION", null, "the plan", T0);
      epic(db, "e-super", "proj-1", "Old epic", "old-epic", "SUPERSEDED", "e-1", "discarded", T0);

      // A ticket, in a DIFFERENT project, so "everything inherited proj-1" cannot pass by accident.
      ticket(
          db,
          "tk-1",
          "proj-2",
          "Checkout fails",
          "checkout-fails",
          "BUG",
          "REFINED",
          "ada",
          "grace",
          "what to do about it",
          "it occurs at checkout",
          T0);

      // Three features under e-1. Ids sort OPPOSITE to created_at for the first of them, and the
      // last two tie on created_at so only the id breaks them:
      //   f-c  T0+1s   -> position 0
      //   f-a  T0+3s   -> position 1  (tie with f-b, id wins)
      //   f-b  T0+3s   -> position 2
      feature(db, "f-c", "e-1", "Third by name", "third-by-name", null, T0.plusSeconds(3), T0.plusSeconds(1));
      feature(db, "f-a", "e-1", "First by name", "first-by-name", "f-c", null, T0.plusSeconds(3));
      feature(db, "f-b", "e-1", "Second by name", "second-by-name", null, null, T0.plusSeconds(3));

      // Three tasks under f-c, interleaved the same way.
      task(db, "t-c", "f-c", "repo-1", "Third task", "third-task", null, T0.plusSeconds(9), T0.plusSeconds(1));
      task(db, "t-a", "f-c", "repo-2", "First task", "first-task", "t-c", null, T0.plusSeconds(5));
      task(db, "t-b", "f-c", "repo-3", "Second task", "second-task", null, null, T0.plusSeconds(5));
    }

    migrateTo(copied, "10");
  }

  @Test
  void eachOldRowLandsUnderItsOwnIdWithItsArchetype() throws Exception {
    try (Connection db = connect(copied)) {
      assertEquals("EPIC", one(db, "select archetype from entity where id = 'e-1'"));
      assertEquals("TICKET", one(db, "select archetype from entity where id = 'tk-1'"));
      assertEquals("FEATURE", one(db, "select archetype from entity where id = 'f-a'"));
      assertEquals("TASK", one(db, "select archetype from entity where id = 't-a'"));

      // The id is the old id, which is the single most load-bearing property of the copy.
      assertEquals("e-1", one(db, "select id from entity where id = 'e-1'"));
      assertEquals("t-a", one(db, "select id from entity where id = 't-a'"));
    }
  }

  @Test
  void aFeatureInheritsItsEpicsProjectAndATaskWalksTwoLevelsUpForIt() throws Exception {
    try (Connection db = connect(copied)) {
      assertEquals("proj-1", one(db, "select project_id from entity where id = 'e-1'"));
      // The ticket's own project, which is not the epic tree's.
      assertEquals("proj-2", one(db, "select project_id from entity where id = 'tk-1'"));
      // A feature has no project column today: it inherits the epic's.
      assertEquals("proj-1", one(db, "select project_id from entity where id = 'f-a'"));
      // A task walks task -> feature -> epic.
      assertEquals("proj-1", one(db, "select project_id from entity where id = 't-a'"));
    }
  }

  @Test
  void slugScopeIsTheProjectForARootAndTheParentForAChild() throws Exception {
    try (Connection db = connect(copied)) {
      assertEquals("proj-1", one(db, "select slug_scope from entity where id = 'e-1'"));
      assertEquals("proj-2", one(db, "select slug_scope from entity where id = 'tk-1'"));
      assertEquals("e-1", one(db, "select slug_scope from entity where id = 'f-a'"));
      assertEquals("f-c", one(db, "select slug_scope from entity where id = 't-a'"));
    }
  }

  @Test
  void everyMergedColumnLands() throws Exception {
    try (Connection db = connect(copied)) {
      // The epic's own properties, including the self-reference to its successor.
      assertEquals("Alpha epic", one(db, "select title from entity where id = 'e-1'"));
      assertEquals("alpha-epic", one(db, "select slug from entity where id = 'e-1'"));
      assertEquals("the plan", one(db, "select description from entity where id = 'e-1'"));
      assertEquals("IMPLEMENTATION", one(db, "select status from entity where id = 'e-1'"));
      assertEquals(
          "e-1", one(db, "select superseded_by_entity_id from entity where id = 'e-super'"));
      assertEquals(
          CAUSE_EPIC.toString(), one(db, "select causation_id from entity where id = 'e-1'"));

      // The ticket's: type becomes ticket_type, and the four stamped fields travel.
      assertEquals("BUG", one(db, "select ticket_type from entity where id = 'tk-1'"));
      assertEquals("REFINED", one(db, "select status from entity where id = 'tk-1'"));
      assertEquals("it occurs at checkout", one(db, "select impetus from entity where id = 'tk-1'"));
      assertEquals("ada", one(db, "select assignee from entity where id = 'tk-1'"));
      assertEquals("grace", one(db, "select created_by from entity where id = 'tk-1'"));
      assertEquals(
          CAUSE_TICKET.toString(), one(db, "select causation_id from entity where id = 'tk-1'"));

      // feature.implemented_on and task.implemented_at are ONE column now.
      assertNotNull(one(db, "select implemented_at from entity where id = 'f-c'"));
      assertNotNull(one(db, "select implemented_at from entity where id = 't-c'"));
      assertNull(one(db, "select implemented_at from entity where id = 'f-a'"));

      // depends_on_feature_id and depends_on_task_id are ONE column now, and it is not nesting.
      assertEquals("f-c", one(db, "select depends_on_entity_id from entity where id = 'f-a'"));
      assertEquals("t-c", one(db, "select depends_on_entity_id from entity where id = 't-a'"));

      // The task's repository, and a feature's absent status.
      assertEquals("repo-2", one(db, "select repository_id from entity where id = 't-a'"));
      assertNull(one(db, "select status from entity where id = 'f-a'"));
      assertNull(one(db, "select status from entity where id = 't-a'"));
      assertEquals(
          CAUSE_FEATURE.toString(), one(db, "select causation_id from entity where id = 'f-a'"));
      assertEquals(
          CAUSE_TASK.toString(), one(db, "select causation_id from entity where id = 't-a'"));

      // The timestamps are the old rows', not this migration's.
      assertEquals(
          one(db, "select created_at from Feature where id = 'f-a'"),
          one(db, "select created_at from entity where id = 'f-a'"));
    }
  }

  @Test
  void bothRelationKindsBecomeMembershipsOrderedByCreatedAtThenId() throws Exception {
    try (Connection db = connect(copied)) {
      // The edge's id IS the child's, which is what makes a retry a no-op.
      assertEquals("f-a", one(db, "select id from entity_membership where child_id = 'f-a'"));
      assertEquals("e-1", one(db, "select parent_id from entity_membership where child_id = 'f-a'"));
      assertEquals("f-c", one(db, "select parent_id from entity_membership where child_id = 't-a'"));

      // Dense, zero-based, and in (created_at, id) order — the sort the two repositories apply.
      assertEquals("0", positionOf(db, "f-c"));
      assertEquals("1", positionOf(db, "f-a"));
      assertEquals("2", positionOf(db, "f-b"));
      assertEquals("0", positionOf(db, "t-c"));
      assertEquals("1", positionOf(db, "t-a"));
      assertEquals("2", positionOf(db, "t-b"));

      // The edge carries the CHILD's causation and timestamps.
      assertEquals(
          CAUSE_FEATURE.toString(),
          one(db, "select causation_id from entity_membership where child_id = 'f-a'"));
      assertEquals(
          one(db, "select created_at from Feature where id = 'f-a'"),
          one(db, "select created_at from entity_membership where child_id = 'f-a'"));
    }
  }

  @Test
  void theCountsAreTheSumOfTheFourOldTables() throws Exception {
    try (Connection db = connect(copied)) {
      long epics = count(db, "Epic");
      long tickets = count(db, "Ticket");
      long features = count(db, "Feature");
      long tasks = count(db, "Task");

      assertEquals(epics + tickets + features + tasks, count(db, "entity"));
      assertEquals(features + tasks, count(db, "entity_membership"));

      // And the four old tables are untouched: nothing was moved, only read.
      assertEquals(2, epics);
      assertEquals(1, tickets);
      assertEquals(3, features);
      assertEquals(3, tasks);
    }
  }

  // ---- the de-collision -------------------------------------------------------------------------

  @Test
  void aCollidingEpicKeepsItsSlugAndTheTicketIsReSluggedUnderTheFortyCharacterCap()
      throws Exception {
    // 40 characters, and character 38 of it is a dash — so the trimmed head has a trailing dash to
    // strip, which is the half of Slugs.unique's arithmetic that is easy to leave out.
    String dashy = "aaaaaaaaaa-bbbbbbbbbb-cccccccccc-dddd-ee";
    // Two 40-character slugs that differ only past the trim point, so both would compute the SAME
    // candidate if the pass did not count what it has already assigned.
    String twinX = "abcdefghij-klmnopqrst-uvwxyzabcd-efghijX";
    String twinY = "abcdefghij-klmnopqrst-uvwxyzabcd-efghijY";
    String twinHead = "abcdefghij-klmnopqrst-uvwxyzabcd-efghi";

    String url = EmbeddedPg.url("qp_epics_v10_migration_slugs");
    migrateTo(url, "9");

    try (Connection db = connect(url)) {
      epic(db, "ep-1", "proj-1", "Checkout", "checkout", "REFINING", null, null, T0);
      epic(db, "ep-2", "proj-1", "Dashy", dashy, "REFINING", null, null, T0);
      epic(db, "ep-3", "proj-1", "Twin X", twinX, "REFINING", null, null, T0);
      epic(db, "ep-4", "proj-1", "Twin Y", twinY, "REFINING", null, null, T0);

      ticket(db, "tk-1", "proj-1", "Checkout", "checkout", "BUG", "REPORTED", null, null, null, null, T0);
      ticket(db, "tk-2", "proj-1", "Dashy", dashy, "BUG", "REPORTED", null, null, null, null, T0);
      // tk-3 is older than tk-4, so it takes -2 and tk-4 has to take -3.
      ticket(db, "tk-3", "proj-1", "Twin X", twinX, "BUG", "REPORTED", null, null, null, null, T0);
      ticket(
          db, "tk-4", "proj-1", "Twin Y", twinY, "BUG", "REPORTED", null, null, null, null,
          T0.plusSeconds(10));
      // A ticket that collides with nothing keeps the slug it has.
      ticket(db, "tk-5", "proj-1", "Alone", "alone", "BUG", "REPORTED", null, null, null, null, T0);
    }

    migrateTo(url, "10");

    try (Connection db = connect(url)) {
      // The epic keeps its slug — it is the scope of every feature under it and a segment of every
      // branch name beneath it.
      assertEquals("checkout", one(db, "select slug from entity where id = 'ep-1'"));
      // The ticket's ENTITY row moves.
      assertEquals("checkout-2", one(db, "select slug from entity where id = 'tk-1'"));
      // The Ticket TABLE row does not: this migration writes to none of the four old tables.
      assertEquals("checkout", one(db, "select slug from Ticket where id = 'tk-1'"));

      // The cap: a 40-character base trims, loses the dash the trim landed on, and stays <= 40.
      String capped = one(db, "select slug from entity where id = 'tk-2'");
      assertEquals("aaaaaaaaaa-bbbbbbbbbb-cccccccccc-dddd-2", capped);
      assertTrue(capped.length() <= 40, "a re-slug must still fit the 40-character cap");
      assertFalse(capped.contains("--"), "the trimmed head must not keep its trailing dash");

      // Two tickets whose bases trim to one head land on different values.
      String x = one(db, "select slug from entity where id = 'tk-3'");
      String y = one(db, "select slug from entity where id = 'tk-4'");
      assertEquals(twinHead + "-2", x);
      assertEquals(twinHead + "-3", y);
      assertNotEquals(x, y);
      assertTrue(x.length() <= 40 && y.length() <= 40);

      // And a ticket nobody collides with is copied verbatim.
      assertEquals("alone", one(db, "select slug from entity where id = 'tk-5'"));
    }
  }

  // ---- idempotence ------------------------------------------------------------------------------

  /**
   * Driven directly rather than through Flyway, because Flyway will never re-run a V10 that
   * succeeded — the case this guards is the deployment that half-applied and was retried by hand.
   * The whole file's text goes through one {@code Statement.execute}; autocommit is OFF and the
   * commit is explicit, because the working table is {@code on commit drop} and autocommit would
   * drop it between statements.
   */
  @Test
  void runningTheWholeMigrationASecondTimeChangesNothing() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v10_migration_idem");
    migrateTo(url, "9");

    try (Connection db = connect(url)) {
      epic(db, "e-1", "proj-1", "Alpha", "alpha", "REFINING", null, null, T0);
      // A collision, so the re-slug pass runs on the replay too.
      ticket(db, "tk-1", "proj-1", "Alpha", "alpha", "BUG", "REPORTED", null, null, null, null, T0);
      feature(db, "f-b", "e-1", "B", "b", null, null, T0.plusSeconds(2));
      feature(db, "f-a", "e-1", "A", "a", null, null, T0.plusSeconds(1));
    }

    migrateTo(url, "10");

    long entities;
    long memberships;
    try (Connection db = connect(url)) {
      entities = count(db, "entity");
      memberships = count(db, "entity_membership");
      assertEquals(4, entities);
      assertEquals(2, memberships);
      assertEquals("alpha-2", one(db, "select slug from entity where id = 'tk-1'"));
      assertEquals("0", positionOf(db, "f-a"));
      assertEquals("1", positionOf(db, "f-b"));
    }

    replayV10(url);

    try (Connection db = connect(url)) {
      assertEquals(entities, count(db, "entity"));
      assertEquals(memberships, count(db, "entity_membership"));
      assertEquals("alpha-2", one(db, "select slug from entity where id = 'tk-1'"));
      assertEquals("0", positionOf(db, "f-a"));
      assertEquals("1", positionOf(db, "f-b"));
    }
  }

  // ---- the pieces --------------------------------------------------------------------------------

  private static void replayV10(String url) throws Exception {
    String sql;
    try (InputStream in =
        UnifiedBackfillMigrationTest.class.getClassLoader().getResourceAsStream(V10_RESOURCE)) {
      assertNotNull(in, "V10 is not on the test classpath at " + V10_RESOURCE);
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (Connection db = connect(url)) {
      db.setAutoCommit(false);
      try (Statement statement = db.createStatement()) {
        statement.execute(sql);
      }
      db.commit();
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

  private static void epic(
      Connection db,
      String id,
      String projectId,
      String title,
      String slug,
      String status,
      String supersededBy,
      String description,
      OffsetDateTime createdAt)
      throws Exception {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into Epic (id, causation_id, project_id, title, slug, status,"
                + " superseded_by_epic_id, description, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, id);
      insert.setObject(2, CAUSE_EPIC);
      insert.setString(3, projectId);
      insert.setString(4, title);
      insert.setString(5, slug);
      insert.setString(6, status);
      insert.setString(7, supersededBy);
      insert.setString(8, description);
      insert.setObject(9, createdAt);
      insert.setObject(10, createdAt);
      insert.executeUpdate();
    }
  }

  private static void ticket(
      Connection db,
      String id,
      String projectId,
      String title,
      String slug,
      String type,
      String status,
      String assignee,
      String createdBy,
      String description,
      String impetus,
      OffsetDateTime createdAt)
      throws Exception {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into Ticket (id, causation_id, project_id, title, slug, type, status, assignee,"
                + " created_by, description, impetus, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, id);
      insert.setObject(2, CAUSE_TICKET);
      insert.setString(3, projectId);
      insert.setString(4, title);
      insert.setString(5, slug);
      insert.setString(6, type);
      insert.setString(7, status);
      insert.setString(8, assignee);
      insert.setString(9, createdBy);
      insert.setString(10, description);
      insert.setString(11, impetus);
      insert.setObject(12, createdAt);
      insert.setObject(13, createdAt);
      insert.executeUpdate();
    }
  }

  private static void feature(
      Connection db,
      String id,
      String epicId,
      String title,
      String slug,
      String dependsOn,
      OffsetDateTime implementedOn,
      OffsetDateTime createdAt)
      throws Exception {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into Feature (id, causation_id, epic_id, title, slug, description,"
                + " depends_on_feature_id, implemented_on, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, id);
      insert.setObject(2, CAUSE_FEATURE);
      insert.setString(3, epicId);
      insert.setString(4, title);
      insert.setString(5, slug);
      insert.setString(6, "the " + slug);
      insert.setString(7, dependsOn);
      insert.setObject(8, implementedOn);
      insert.setObject(9, createdAt);
      insert.setObject(10, createdAt);
      insert.executeUpdate();
    }
  }

  private static void task(
      Connection db,
      String id,
      String featureId,
      String repositoryId,
      String title,
      String slug,
      String dependsOn,
      OffsetDateTime implementedAt,
      OffsetDateTime createdAt)
      throws Exception {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into Task (id, causation_id, feature_id, repository_id, title, slug,"
                + " description, depends_on_task_id, implemented_at, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, id);
      insert.setObject(2, CAUSE_TASK);
      insert.setString(3, featureId);
      insert.setString(4, repositoryId);
      insert.setString(5, title);
      insert.setString(6, slug);
      insert.setString(7, "the " + slug);
      insert.setString(8, dependsOn);
      insert.setObject(9, implementedAt);
      insert.setObject(10, createdAt);
      insert.setObject(11, createdAt);
      insert.executeUpdate();
    }
  }

  private static String positionOf(Connection db, String childId) throws Exception {
    // Qualified: `position` is a col_name keyword in postgres and a bare reference to it in a
    // select list is parsed as the POSITION(x IN y) function.
    return one(
        db, "select em.position from entity_membership em where em.child_id = '" + childId + "'");
  }

  private static long count(Connection db, String table) throws Exception {
    return Long.parseLong(one(db, "select count(*) from " + table));
  }

  /** The first column of the single row the query answers, as a string, or null. */
  private static String one(Connection db, String query) throws Exception {
    try (Statement sql = db.createStatement();
        ResultSet found = sql.executeQuery(query)) {
      assertTrue(found.next(), "no row for: " + query);
      return found.getString(1);
    }
  }
}
