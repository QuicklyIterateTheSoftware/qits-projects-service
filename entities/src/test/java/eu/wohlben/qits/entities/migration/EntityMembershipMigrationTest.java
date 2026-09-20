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
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * What V9 actually asserts about the two new tables, as the database enforces it.
 *
 * <p><b>Why this exists separately from the suites around it.</b> Every other test in this module
 * starts from a database migrated to head by the application's own datasource, and therefore proves
 * only that the DDL applies. The interesting half of this migration is the <em>constraints</em> —
 * the merged slug scope, the one-parent-per-child rule, and the two closed vocabularies — and none
 * of them can be observed except by trying to break them. So this drives Flyway directly, on a
 * database of its own, and stops at V9 — the embedded postgres is the module's, so it costs no
 * second server and it boots no Quarkus application.
 */
class EntityMembershipMigrationTest {

  /** Its own database, for the reason every (module, datasource) pair here has one. */
  private static final String DATABASE = "qp_epics_v9_migration";

  private static final String LOCATION = "classpath:db/epics/migration";

  @Test
  void bothTablesExistWithTheColumnsTheEntitiesMap() throws Exception {
    try (Connection db = migrated("shape")) {
      insertEntity(db, "e-1", "EPIC", "plan", "proj-1", "REFINING");
      insertEntity(db, "f-1", "FEATURE", "part", "e-1", null);
      insertMembership(db, "m-1", "e-1", "f-1", 0);

      assertEquals(1, count(db, "select count(*) from entity_membership where parent_id = 'e-1'"));

      // The properties that were four tables' columns are one row's columns now, nullable except
      // where every archetype needs them.
      try (Statement sql = db.createStatement();
          ResultSet row =
              sql.executeQuery(
                  "select ticket_type, impetus, assignee, created_by, superseded_by_entity_id,"
                      + " repository_id, implemented_at, depends_on_entity_id, causation_id"
                      + " from entity where id = 'f-1'")) {
        assertTrue(row.next());
        for (int column = 1; column <= 9; column++) {
          assertEquals(null, row.getObject(column), "column " + column + " should be null");
        }
      }
    }
  }

  // ---- the merged slug scope -------------------------------------------------------------------

  @Test
  void twoRowsCannotShareASlugWithinOneScope() throws Exception {
    // uq_entity_slug_scope_slug IS today's three constraints at once. Inside one scope — here an
    // epic holding two features — a repeated slug would name the same branch twice.
    try (Connection db = migrated("slug_same_scope")) {
      insertEntity(db, "e-1", "EPIC", "plan", "proj-1", "REFINING");
      insertEntity(db, "f-1", "FEATURE", "login", "e-1", null);

      assertRefused(() -> insertEntity(db, "f-2", "FEATURE", "login", "e-1", null));
    }
  }

  @Test
  void theSameSlugInTwoScopesIsTwoAddressesThatNeverMeet() throws Exception {
    // Two epics may each hold a feature called "login", which is exactly what
    // uq_feature_epic_slug allowed and what a single (project_id, slug) constraint would have
    // forbidden — the wrong shape this column exists to avoid.
    try (Connection db = migrated("slug_two_scopes")) {
      insertEntity(db, "e-1", "EPIC", "first", "proj-1", "REFINING");
      insertEntity(db, "e-2", "EPIC", "second", "proj-1", "REFINING");
      insertEntity(db, "f-1", "FEATURE", "login", "e-1", null);
      insertEntity(db, "f-2", "FEATURE", "login", "e-2", null);

      assertEquals(2, count(db, "select count(*) from entity where slug = 'login'"));
    }
  }

  @Test
  void aRootsScopeIsItsProjectSoTwoProjectsMayHoldTheSameSlug() throws Exception {
    try (Connection db = migrated("slug_roots")) {
      insertEntity(db, "e-1", "EPIC", "checkout", "proj-1", "REFINING");
      insertEntity(db, "e-2", "EPIC", "checkout", "proj-2", "REFINING");

      assertEquals(2, count(db, "select count(*) from entity where slug = 'checkout'"));
      assertRefused(() -> insertEntity(db, "e-3", "EPIC", "checkout", "proj-1", "REFINING"));
    }
  }

  // ---- one parent per child --------------------------------------------------------------------

  @Test
  void aChildCannotHaveASecondParent() throws Exception {
    // The hierarchy is a tree, and uq_entity_membership_one_parent_per_child is what says so. The
    // campaign work relaxes this into a partial index on a kind column; until then a second edge
    // for one child is refused by the database rather than by a convention.
    try (Connection db = migrated("one_parent")) {
      insertEntity(db, "e-1", "EPIC", "first", "proj-1", "REFINING");
      insertEntity(db, "e-2", "EPIC", "second", "proj-1", "REFINING");
      insertEntity(db, "f-1", "FEATURE", "part", "e-1", null);
      insertMembership(db, "m-1", "e-1", "f-1", 0);

      assertRefused(() -> insertMembership(db, "m-2", "e-2", "f-1", 0));
    }
  }

  @Test
  void aParentMayHoldManyChildrenAndTheirPositionsAreTheirOrder() throws Exception {
    try (Connection db = migrated("positions")) {
      insertEntity(db, "e-1", "EPIC", "plan", "proj-1", "REFINING");
      insertEntity(db, "f-1", "FEATURE", "one", "e-1", null);
      insertEntity(db, "f-2", "FEATURE", "two", "e-1", null);
      insertMembership(db, "m-1", "e-1", "f-1", 0);
      insertMembership(db, "m-2", "e-1", "f-2", 1);

      assertEquals(2, count(db, "select count(*) from entity_membership where parent_id = 'e-1'"));
    }
  }

  @Test
  void deletingEitherEndTakesTheEdgeWithIt() throws Exception {
    // The safety net rather than the mechanism — the services still tear subtrees down in-service
    // so every removed row gets its own audit entry. An edge to a row that is gone is not a fact.
    try (Connection db = migrated("cascade")) {
      insertEntity(db, "e-1", "EPIC", "plan", "proj-1", "REFINING");
      insertEntity(db, "f-1", "FEATURE", "part", "e-1", null);
      insertMembership(db, "m-1", "e-1", "f-1", 0);

      execute(db, "delete from entity where id = 'e-1'");

      assertEquals(0, count(db, "select count(*) from entity_membership"));
    }
  }

  // ---- the two closed vocabularies -------------------------------------------------------------

  @Test
  void theArchetypeCheckSpellsTheFourWordsAndRefusesAnythingElse() throws Exception {
    try (Connection db = migrated("archetype_check")) {
      for (String archetype : new String[] {"EPIC", "TICKET", "FEATURE", "TASK"}) {
        insertEntity(db, "x-" + archetype, archetype, archetype.toLowerCase(), "proj-1", null);
      }
      // The kind this model is being merged in order to allow is not declared yet, and the
      // constraint is what makes adding it a visible migration rather than a silent new row.
      assertRefused(() -> insertEntity(db, "x-c", "CAMPAIGN", "campaign", "proj-1", null));
    }
  }

  @Test
  void theStatusCheckIsTheUnionOfBothLifecyclesAndNullIsOrdinary() throws Exception {
    try (Connection db = migrated("status_check")) {
      // Both vocabularies pass, because a single check constraint cannot discriminate on the
      // archetype — that is control/Archetypes' job, and this is the reason it has one.
      insertEntity(db, "e-1", "EPIC", "plan", "proj-1", "REFINING");
      insertEntity(db, "t-1", "TICKET", "bug", "proj-1", "REPORTED");
      // An epic word on a ticket row is accepted HERE and refused by the registry. The split is
      // deliberate: the constraint spells the vocabulary, the service spells the rule.
      insertEntity(db, "t-2", "TICKET", "other", "proj-1", "SUPERSEDED");
      // A feature carries none at all, which is the ordinary state of most rows in this table.
      insertEntity(db, "f-1", "FEATURE", "part", "e-1", null);

      assertRefused(() -> insertEntity(db, "x-1", "EPIC", "nonsense", "proj-1", "IN_PROGRESS"));
    }
  }

  // ---- the self-references ---------------------------------------------------------------------

  @Test
  void deletingADependedOnRowClearsThePointerRatherThanLeavingItDangling() throws Exception {
    // V1's rule for depends_on_feature_id/depends_on_task_id, unchanged on the merged column.
    try (Connection db = migrated("depends_on")) {
      insertEntity(db, "e-1", "EPIC", "plan", "proj-1", "REFINING");
      insertEntity(db, "f-1", "FEATURE", "first", "e-1", null);
      insertEntity(db, "f-2", "FEATURE", "second", "e-1", null);
      execute(db, "update entity set depends_on_entity_id = 'f-1' where id = 'f-2'");

      execute(db, "delete from entity where id = 'f-1'");

      try (Statement sql = db.createStatement();
          ResultSet row =
              sql.executeQuery("select depends_on_entity_id from entity where id = 'f-2'")) {
        assertTrue(row.next());
        assertEquals(null, row.getString(1));
      }
    }
  }

  // ---- the pieces ------------------------------------------------------------------------------

  private static Connection migrated(String suffix) throws Exception {
    String url = EmbeddedPg.url(DATABASE + "_" + suffix);
    Flyway.configure()
        .dataSource(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD)
        .locations(LOCATION)
        .target(MigrationVersion.fromVersion("9"))
        .load()
        .migrate();
    return DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
  }

  /**
   * One row of {@code entity}. {@code slugScope} is the project id for a root and the parent entity
   * id for a nested row — the rule the service maintains and this index enforces.
   */
  private static void insertEntity(
      Connection db, String id, String archetype, String slug, String slugScope, String status)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into entity"
                + " (id, project_id, archetype, title, slug, slug_scope, status,"
                + "  created_at, updated_at)"
                + " values (?, 'proj-1', ?, ?, ?, ?, ?, now(), now())")) {
      insert.setString(1, id);
      insert.setString(2, archetype);
      insert.setString(3, slug + " title");
      insert.setString(4, slug);
      insert.setString(5, slugScope);
      insert.setString(6, status);
      insert.executeUpdate();
    }
  }

  private static void insertMembership(
      Connection db, String id, String parentId, String childId, int position) throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into entity_membership"
                + " (id, parent_id, child_id, position, created_at, updated_at)"
                + " values (?, ?, ?, ?, now(), now())")) {
      insert.setString(1, id);
      insert.setString(2, parentId);
      insert.setString(3, childId);
      insert.setInt(4, position);
      insert.executeUpdate();
    }
  }

  private static void execute(Connection db, String sql) throws SQLException {
    try (Statement statement = db.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  private static long count(Connection db, String query) throws SQLException {
    try (Statement sql = db.createStatement();
        ResultSet found = sql.executeQuery(query)) {
      assertTrue(found.next());
      return found.getLong(1);
    }
  }

  /**
   * A write the database must refuse. The connection is in autocommit, so a refused statement
   * aborts nothing around it and the case can go on asserting afterwards — postgres would otherwise
   * fail every later statement of an errored transaction, turning one wrong assertion into a
   * cascade of misleading ones.
   */
  private static void assertRefused(Write write) {
    boolean refused = false;
    try {
      write.run();
    } catch (SQLException expected) {
      refused = true;
    }
    assertTrue(refused, "the constraint must refuse this write");
  }

  @FunctionalInterface
  private interface Write {
    void run() throws SQLException;
  }
}
