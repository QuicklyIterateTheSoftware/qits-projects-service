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
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What V11 does to the rows that were already there: the numbering of an estate V10 has already
 * copied in, and the counter the allocator picks up from.
 *
 * <p>No other test can reach this. Every suite around it starts from an empty database — {@code
 * entity} has no rows when V11 runs there — so they prove that the statements parse and nothing
 * more. Two claims are only assertable here:
 *
 * <ul>
 *   <li><b>The backfill is deterministic.</b> The same estate, inserted in a different physical
 *       order into a different database, is numbered identically. An ordering that read whatever the
 *       heap returned would pass on one of these two databases and fail on the other — and in
 *       production would differ between the test database and the live one, which is a defect nobody
 *       would notice for months.
 *   <li><b>The allocator starts above the highest backfilled value.</b> {@code
 *       entity_number_sequence} is seeded {@code max(number) + 1} per project, so the first create
 *       after the deployment cannot collide with a row the deployment itself numbered.
 * </ul>
 *
 * <p>Plain JUnit and deliberately not a {@code @QuarkusTest}, this module's standing reasoning for
 * a migration test: a {@code @TestProfile} is a whole Quarkus application at roughly 125 MB of
 * retained metaspace inside a 4 GB CI step, and nothing asserted here needs one.
 */
class EntityNumberMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  /** The same estate, numbered on two databases whose insertion orders are opposite. */
  private static String inOrder;

  private static String reversed;

  /** A third copy, so the constraint test's writes cannot disturb the two being compared. */
  private static String constrained;

  /**
   * One estate, deliberately shaped so that every wrong ordering is caught:
   *
   * <ul>
   *   <li>{@code created_at} disagrees with the ids' alphabetical order, so a sort on the id alone
   *       is wrong;
   *   <li>two rows in one project <b>tie</b> on {@code created_at}, so only the {@code (created_at,
   *       id)} pair is total and a sort on the timestamp alone is non-deterministic;
   *   <li>a second project interleaves with the first, so a global rather than per-project partition
   *       is wrong;
   *   <li>the four archetypes are mixed, because the number names a <b>node</b> and a partition by
   *       archetype would be wrong.
   * </ul>
   */
  @BeforeAll
  static void numberASeededEstate() throws Exception {
    inOrder = EmbeddedPg.url("qp_epics_v11_migration_a");
    reversed = EmbeddedPg.url("qp_epics_v11_migration_b");

    constrained = EmbeddedPg.url("qp_epics_v11_migration_c");

    seed(inOrder, false);
    seed(reversed, true);
    seed(constrained, false);
  }

  // ---- the numbering -----------------------------------------------------------------------

  /** {@code (created_at, id)} — V10's own total order, ties included. */
  @Test
  void everyRowIsNumberedByCreationTimeWithTheIdBreakingTies() throws Exception {
    try (Connection db = connect(inOrder)) {
      assertEquals("1", one(db, "select number from entity where id = 'e-d'"));
      // e-a and e-b tie on created_at; the id is what separates them, and it separates them the
      // same way on every database and after every VACUUM.
      assertEquals("2", one(db, "select number from entity where id = 'e-a'"));
      assertEquals("3", one(db, "select number from entity where id = 'e-b'"));
      assertEquals("4", one(db, "select number from entity where id = 'f-c'"));
      assertEquals("5", one(db, "select number from entity where id = 't-e'"));
    }
  }

  /**
   * Per project. A second project's rows start at 1 again and are not displaced by the first's,
   * which is what makes the numbers small enough to write by hand.
   */
  @Test
  void eachProjectIsNumberedFromOneAndTheSecondDoesNotContinueTheFirst() throws Exception {
    try (Connection db = connect(inOrder)) {
      assertEquals("1", one(db, "select number from entity where id = 'x-z'"));
      assertEquals("2", one(db, "select number from entity where id = 'x-y'"));
    }
  }

  /**
   * The claim the whole fixture exists for. The identical estate inserted in the opposite order gets
   * the identical numbers — so the backfill reads the columns it says it reads and not the order the
   * rows happen to come back in.
   */
  @Test
  void theSameEstateIsNumberedIdenticallyWhateverOrderItWasInsertedIn() throws Exception {
    assertEquals(numbering(inOrder), numbering(reversed));
    // And it is not vacuously equal: the estate really is numbered.
    assertEquals(7, numbering(inOrder).size());
  }

  // ---- the allocator's floor -----------------------------------------------------------------

  /**
   * {@code entity_number_sequence} is seeded above the highest number the backfill wrote, per
   * project, so the first create after the deployment cannot collide with a row the deployment
   * itself numbered.
   */
  @Test
  void theCounterStartsAboveTheHighestBackfilledNumberInEachProject() throws Exception {
    try (Connection db = connect(inOrder)) {
      assertEquals(
          "5", one(db, "select max(number) from entity where project_id = 'proj-1'"));
      assertEquals(
          "6",
          one(db, "select next_number from entity_number_sequence where project_id = 'proj-1'"));

      assertEquals("2", one(db, "select max(number) from entity where project_id = 'proj-2'"));
      assertEquals(
          "3",
          one(db, "select next_number from entity_number_sequence where project_id = 'proj-2'"));
    }
  }

  /** A project with no entity row gets no counter row; the allocator mints it at 1 on first use. */
  @Test
  void aProjectWithNoRowsHasNoCounterAtAll() throws Exception {
    try (Connection db = connect(inOrder)) {
      assertEquals("0", one(db, "select count(*) from entity_number_sequence where project_id = 'proj-nothing'"));
      assertEquals("2", one(db, "select count(*) from entity_number_sequence"));
    }
  }

  // ---- the constraint -------------------------------------------------------------------------

  /**
   * Uniqueness is {@code (project_id, number)} and it is enforced <b>in the database</b>, so a
   * second writer cannot make two rows share a number however it was reached. The same number in a
   * different project is fine, which is what "per project" means.
   */
  @Test
  void theDatabaseRefusesASecondRowOnOneNumberInOneProjectAndPermitsItInAnother() throws Exception {
    try (Connection db = connect(constrained)) {
      assertThrows(
          SQLException.class,
          () -> entity(db, "clash", "proj-1", "EPIC", "Clash", "clash", "proj-1", T0, 1L));

      // proj-2's run reaches 2; number 5 is free there even though proj-1 holds it.
      entity(db, "fine", "proj-2", "EPIC", "Fine", "fine", "proj-2", T0, 5L);
      assertEquals("5", one(db, "select number from entity where id = 'fine'"));
    }
  }

  /** The column is {@code not null}, so a writer that forgets the number is a failure, not a gap. */
  @Test
  void theColumnIsNotNullable() throws Exception {
    try (Connection db = connect(inOrder)) {
      assertEquals(
          "NO",
          one(
              db,
              "select is_nullable from information_schema.columns"
                  + " where table_name = 'entity' and column_name = 'number'"));
    }
  }

  /** The constraint is named, so the next change to it is a drop rather than a guess. */
  @Test
  void theUniquenessIsANamedConstraint() throws Exception {
    try (Connection db = connect(inOrder)) {
      assertTrue(
          Integer.parseInt(
                  one(
                      db,
                      "select count(*) from pg_constraint"
                          + " where conname = 'uq_entity_project_number'"))
              > 0);
    }
  }

  // ---- fixtures --------------------------------------------------------------------------------

  /**
   * Stands a database at V10 — where {@code entity} exists and carries no number — writes the estate
   * into it, then runs V11 over it. {@code backwards} inserts the very same rows in the reverse
   * order, which is the only difference between the two databases.
   */
  private static void seed(String url, boolean backwards) throws Exception {
    migrateTo(url, "10");

    try (Connection db = connect(url)) {
      List<Row> estate =
          List.of(
              // proj-1, four archetypes, ids deliberately not in created_at order, e-a/e-b tied.
              new Row("e-d", "proj-1", "EPIC", "d", T0.plusSeconds(1)),
              new Row("e-a", "proj-1", "EPIC", "a", T0.plusSeconds(3)),
              new Row("e-b", "proj-1", "TICKET", "b", T0.plusSeconds(3)),
              new Row("f-c", "proj-1", "FEATURE", "c", T0.plusSeconds(5)),
              new Row("t-e", "proj-1", "TASK", "e", T0.plusSeconds(7)),
              // proj-2, interleaved in time with proj-1.
              new Row("x-z", "proj-2", "EPIC", "z", T0.plusSeconds(2)),
              new Row("x-y", "proj-2", "TICKET", "y", T0.plusSeconds(9)));

      List<Row> order = new ArrayList<>(estate);
      if (backwards) {
        java.util.Collections.reverse(order);
      }
      for (Row row : order) {
        entity(db, row.id(), row.projectId(), row.archetype(), row.id() + " title", row.slug(),
            row.projectId(), row.createdAt(), null);
      }
    }

    migrateTo(url, "11");
  }

  private record Row(
      String id, String projectId, String archetype, String slug, OffsetDateTime createdAt) {}

  /** Every {@code id -> number} pair on a database, in id order so two databases compare. */
  private static List<String> numbering(String url) throws Exception {
    try (Connection db = connect(url);
        Statement sql = db.createStatement();
        ResultSet found =
            sql.executeQuery("select id, project_id, number from entity order by id")) {
      List<String> pairs = new ArrayList<>();
      while (found.next()) {
        pairs.add(found.getString(1) + "=" + found.getString(2) + "-" + found.getLong(3));
      }
      return pairs;
    }
  }

  /**
   * One {@code entity} row. {@code number} is null at V10 (the column does not exist yet) and is
   * supplied only by the constraint test, which runs after V11.
   */
  private static void entity(
      Connection db,
      String id,
      String projectId,
      String archetype,
      String title,
      String slug,
      String slugScope,
      OffsetDateTime createdAt,
      Long number)
      throws SQLException {
    String columns =
        "id, project_id, archetype, title, slug, slug_scope, created_at, updated_at"
            + (number == null ? "" : ", number");
    String values = "?, ?, ?, ?, ?, ?, ?, ?" + (number == null ? "" : ", ?");
    try (PreparedStatement insert =
        db.prepareStatement("insert into entity (" + columns + ") values (" + values + ")")) {
      insert.setString(1, id);
      insert.setString(2, projectId);
      insert.setString(3, archetype);
      insert.setString(4, title);
      insert.setString(5, slug);
      insert.setString(6, slugScope);
      insert.setObject(7, createdAt);
      insert.setObject(8, createdAt);
      if (number != null) {
        insert.setLong(9, number);
      }
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

  private static String one(Connection db, String query) throws Exception {
    try (Statement sql = db.createStatement();
        ResultSet found = sql.executeQuery(query)) {
      assertTrue(found.next(), "no row for: " + query);
      return found.getString(1);
    }
  }
}
