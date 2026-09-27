package eu.wohlben.qits.projects.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.testdb.EmbeddedPg;
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
 * What V29 does to a database that already has refinement rows (qits-395): {@code epic_id} becomes
 * {@code entity_id} with every value, every other column and every child row where it was, and the
 * one-room-per-entity rule survives the rename under a name that no longer says epic. Plain JUnit on
 * a database of its own, like the entities module's migration tests.
 */
class RefinementEntityKeyMigrationTest {

  private static final String LOCATION = "classpath:db/projects/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  @Test
  void theKeyIsRenamedAndNothingMoves() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v29_refinement_entity_key");
    migrateTo(url, "28");
    long first;
    try (Connection db = connect(url)) {
      first = refinement(db, "epic_id", "epic-1", "refining/one");
      refinement(db, "epic_id", "epic-2", "refining/two");
      try (Statement sql = db.createStatement()) {
        sql.executeUpdate(
            "insert into refinement_prompt_draft (refinement_id_fk, content, updated_at)"
                + " values ("
                + first
                + ", 'the draft', now())");
      }
    }

    migrateTo(url, "29");

    try (Connection db = connect(url)) {
      try (Statement sql = db.createStatement();
          ResultSet rows =
              sql.executeQuery(
                  "select id, entity_id, branch, commissioned_client_id from refinement order by id")) {
        assertTrue(rows.next());
        assertEquals(first, rows.getLong(1));
        assertEquals("epic-1", rows.getString(2), "an entity id is the epic id it always was");
        assertEquals("refining/one", rows.getString(3));
        assertEquals("client-epic-1", rows.getString(4), "the credential pair stays on the row");
        assertTrue(rows.next());
        assertEquals("epic-2", rows.getString(2));
        assertFalse(rows.next());
      }
      assertFalse(hasColumn(db, "epic_id"), "the old name is gone");
      try (Statement sql = db.createStatement();
          ResultSet draft =
              sql.executeQuery(
                  "select content from refinement_prompt_draft where refinement_id_fk = " + first)) {
        assertTrue(draft.next(), "the child row survives the rename");
        assertEquals("the draft", draft.getString(1));
      }

      // A ticket's room goes in under the new name, and a second room for one entity does not.
      refinement(db, "entity_id", "ticket-7", "refining/ticket-seven");
      SQLException duplicate =
          assertThrows(
              SQLException.class,
              () -> refinement(db, "entity_id", "epic-1", "refining/one-again"));
      assertTrue(
          duplicate.getMessage().contains("refinement_entity_id_key"), duplicate.getMessage());
    }
  }

  private static long refinement(Connection db, String keyColumn, String entityId, String branch)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into refinement"
                + " ("
                + keyColumn
                + ", project_id, repository_id, branch, parent, label, commissioned_client_id,"
                + " commissioned_client_secret, created_at)"
                + " values (?, 'proj-1', 'repo-1', ?, 'main', ?, ?, 'secret', ?) returning id")) {
      insert.setString(1, entityId);
      insert.setString(2, branch);
      insert.setString(3, branch.replace('/', '-'));
      insert.setString(4, "client-" + entityId);
      insert.setObject(5, T0);
      try (ResultSet id = insert.executeQuery()) {
        id.next();
        return id.getLong(1);
      }
    }
  }

  private static boolean hasColumn(Connection db, String column) throws SQLException {
    try (PreparedStatement query =
        db.prepareStatement(
            "select 1 from information_schema.columns"
                + " where table_name = 'refinement' and column_name = ?")) {
      query.setString(1, column);
      try (ResultSet found = query.executeQuery()) {
        return found.next();
      }
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
