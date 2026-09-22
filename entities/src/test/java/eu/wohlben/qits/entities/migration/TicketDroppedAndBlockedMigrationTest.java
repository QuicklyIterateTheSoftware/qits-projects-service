package eu.wohlben.qits.entities.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * What V14 does to a database that already has rows in it — the half of the migration no other
 * suite can reach, because every suite around it starts at head on an empty database and therefore
 * proves only that the DDL parses.
 *
 * <p>Two claims live only here. The first is that the widening was <b>necessary</b>: {@code
 * TicketStatus.DROPPED} existing in Java buys nothing while {@code ck_entity_status} still spells
 * V9's nine words, and standing at V13 and being refused is the only way to say so — a suite
 * migrated to head can no longer observe the refusal it was written to remove. The second is that
 * {@code blocked} is <b>born false on rows that predate it</b>, which is a statement about a row
 * written before the column existed and so is unassertable anywhere the column was always there.
 *
 * <p>Plain JUnit and deliberately not a {@code @QuarkusTest}, this module's standing reasoning for
 * a migration test: a {@code @TestProfile} is a whole Quarkus application at roughly 125 MB of
 * retained metaspace inside a 4 GB CI step, and nothing asserted here needs one. Flyway is driven
 * directly, on a database of its own per case, while the application's own datasource is migrated
 * to head at boot like always.
 */
class TicketDroppedAndBlockedMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  @Test
  void theWordDroppedIsRefusedAtThirteenAndStoredAtFourteen() throws Exception {
    // Its own database, for the reason every (module, datasource) pair here has one, and its own
    // again per case so one case's refused write cannot be read as another's.
    String url = EmbeddedPg.url("qp_epics_v14_dropped");

    migrateTo(url, "13");

    try (Connection db = connect(url)) {
      // The state this migration exists to end: the enum has the word, the column does not, and
      // every attempt to persist a dropped ticket dies in the database rather than in a rule.
      assertTrue(
          refuses(() -> entity(db, "e-early", "TICKET", "Dropped too soon", 1, "DROPPED")),
          "ck_entity_status as V9 wrote it must not accept DROPPED");
    }

    migrateTo(url, "14");

    try (Connection db = connect(url)) {
      entity(db, "e-dropped", "TICKET", "Not worth doing", 2, "DROPPED");
      assertEquals("DROPPED", statusOf(db, "e-dropped"));
    }
  }

  @Test
  void theConstraintWidenedByExactlyOneWordAndDidNotBecomePermissive() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v14_vocabulary");
    migrateTo(url, "14");

    try (Connection db = connect(url)) {
      // A drop-and-re-add is the one shape where a vocabulary can silently be lost — a re-added
      // constraint that forgot its list, or was re-added over a column that is now anything at
      // all, passes every test that only writes words it expects to work.
      assertTrue(
          refuses(() -> entity(db, "e-nonsense", "TICKET", "Typo", 1, "DROPED")),
          "a word nothing declares is still not a status");
      assertTrue(
          refuses(() -> entity(db, "e-old", "TICKET", "The old vocabulary", 2, "OPEN")),
          "V7 retired OPEN and V14 does not bring it back");

      // And the nine V9 spelled are all still there, so the widening did not narrow anything on
      // its way past: both lifecycles are written into one constraint and a re-add that dropped
      // an epic word would be invisible to a ticket-shaped test.
      int number = 10;
      for (String word :
          new String[] {
            "REFINING", "IMPLEMENTATION", "IMPLEMENTED", "SUPERSEDED", "ABANDONED",
            "REPORTED", "REFINED", "VERIFIED", "DONE"
          }) {
        entity(db, "e-" + word, "EPIC", word, number++, word);
        assertEquals(word, statusOf(db, "e-" + word));
      }
    }
  }

  @Test
  void aRowWrittenBeforeTheColumnExistedReadsUnblocked() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v14_blocked");

    migrateTo(url, "13");

    try (Connection db = connect(url)) {
      entity(db, "e-elder", "TICKET", "Filed before the flag", 1, "REFINED");
    }

    migrateTo(url, "14");

    try (Connection db = connect(url)) {
      // The no-backfill claim, and the whole reason V14 keeps its default where the projects
      // lineage's `gating` drops its own: nobody has said this ticket is blocked, and false is
      // what was true of every existing row before the column was there to say it.
      assertFalse(blockedOf(db, "e-elder"));
      // The status is untouched beside it — the two halves of this file are independent, and a
      // widening that rewrote a row would be the migration overruling somebody's decision.
      assertEquals("REFINED", statusOf(db, "e-elder"));
    }
  }

  // ---- the pieces ------------------------------------------------------------------------------

  /** A write the database is expected to refuse, with autocommit leaving the session usable. */
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

  /**
   * One {@code entity} row, named by everything V9 and V11 made {@code not null}. The number is the
   * caller's to keep distinct: {@code uq_entity_project_number} is live from V11 on, and a
   * collision there would read as the status constraint refusing a word it accepts perfectly well.
   */
  private static void entity(
      Connection db, String id, String archetype, String title, long number, String status)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into entity"
                + " (id, project_id, archetype, title, slug, slug_scope, number, status,"
                + " created_at, updated_at)"
                + " values (?, 'proj-1', ?, ?, ?, 'proj-1', ?, ?, ?, ?)")) {
      insert.setString(1, id);
      insert.setString(2, archetype);
      insert.setString(3, title);
      insert.setString(4, id);
      insert.setLong(5, number);
      insert.setString(6, status);
      insert.setObject(7, T0);
      insert.setObject(8, T0);
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

  private static boolean blockedOf(Connection db, String id) throws Exception {
    try (Statement sql = db.createStatement();
        ResultSet found =
            sql.executeQuery("select blocked from entity where id = '" + id + "'")) {
      assertTrue(found.next(), "no entity row " + id);
      return found.getBoolean(1);
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
