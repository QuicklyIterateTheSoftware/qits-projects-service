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
 * What V20 does (qits-551): {@code TicketComment} becomes {@code entity_comment} with {@code
 * entity_id}, every remark written before it is still there on the same entity, and the audit word
 * {@code TICKET_COMMENT} becomes {@code COMMENT} — the rows already written included, and the old
 * word refused from then on. Plain JUnit on a database of its own, starting from V19 and asserting
 * the post-state, like every migration test here.
 */
class EntityCommentMigrationTest {

  private static final String LOCATION = "classpath:db/epics/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  @Test
  void aTicketsRemarkAndItsAuditRowSurviveUnderTheNewNames() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v20_entity_comment");
    migrateTo(url, "19");
    try (Connection db = connect(url)) {
      entity(db, "t-1", "TICKET", 1);
      try (Statement sql = db.createStatement()) {
        sql.executeUpdate(
            "insert into TicketComment (id, ticket_id, author, body, created_at, updated_at)"
                + " values ('c-1', 't-1', 'alice', 'seen it', now(), now())");
        sql.executeUpdate(
            "insert into auditentry (id, entity_type, entity_id, epic_id, operation, changed_by,"
                + " changed_at, snapshot)"
                + " values ('a-1', 'TICKET_COMMENT', 'c-1', 't-1', 'CREATE', 'alice', now(),"
                + " '{\"ticketId\":\"t-1\"}')");
      }
    }

    migrateTo(url, "20");

    try (Connection db = connect(url)) {
      assertEquals(
          "t-1|alice|seen it",
          one(db, "select entity_id || '|' || author || '|' || body from entity_comment"));
      assertEquals("COMMENT", one(db, "select entity_type from auditentry where id = 'a-1'"));
      // The snapshot is what the row looked like when it was written, and stays so.
      assertEquals("{\"ticketId\":\"t-1\"}", one(db, "select snapshot from auditentry"));
      assertEquals("0", one(db, "select count(*) from pg_class where relname = 'ticketcomment'"));
    }
  }

  @Test
  void anyEntityTakesARemarkAndTheCascadeAndTheVocabularyFollowTheRename() throws Exception {
    String url = EmbeddedPg.url("qp_epics_v20_any_entity");
    migrateTo(url, "20");
    try (Connection db = connect(url)) {
      entity(db, "e-1", "EPIC", 1);
      try (Statement sql = db.createStatement()) {
        sql.executeUpdate(
            "insert into entity_comment (id, entity_id, author, body, created_at, updated_at)"
                + " values ('c-1', 'e-1', 'bob', 'on the epic', now(), now())");
        // The FK is the safety net under the in-service cascade, and still cascades.
        sql.executeUpdate("delete from entity where id = 'e-1'");
      }
      assertEquals("0", one(db, "select count(*) from entity_comment"));

      try (Statement sql = db.createStatement()) {
        sql.executeUpdate(
            "insert into auditentry (id, entity_type, entity_id, epic_id, operation, changed_at)"
                + " values ('a-1', 'COMMENT', 'c-1', 'e-1', 'DELETE', now())");
      }
      assertThrows(
          SQLException.class,
          () -> {
            try (Statement sql = db.createStatement()) {
              sql.executeUpdate(
                  "insert into auditentry (id, entity_type, entity_id, epic_id, operation,"
                      + " changed_at) values ('a-2', 'TICKET_COMMENT', 'c-1', 'e-1', 'DELETE',"
                      + " now())");
            }
          },
          "the retired word is refused");
      assertEquals(
          "fk_entity_comment_entity",
          one(
              db,
              "select conname from pg_constraint where conrelid = 'entity_comment'::regclass"
                  + " and contype = 'f'"));
      assertEquals(
          "1",
          one(
              db,
              "select count(*) from pg_indexes where indexname = 'idx_entity_comment_entity'"));
    }
  }

  // ---- fixtures ---------------------------------------------------------------------------------

  private static void entity(Connection db, String id, String archetype, long number)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into entity"
                + " (id, project_id, archetype, title, slug, slug_scope, number, status,"
                + " ticket_type, impetus, created_at, updated_at)"
                + " values (?, 'proj-1', ?, ?, ?, 'proj-1', ?, 'REPORTED', ?, ?, ?, ?)")) {
      insert.setString(1, id);
      insert.setString(2, archetype);
      insert.setString(3, id);
      insert.setString(4, id);
      insert.setLong(5, number);
      insert.setString(6, "TICKET".equals(archetype) ? "BUG" : null);
      insert.setString(7, "TICKET".equals(archetype) ? "it occurs" : null);
      insert.setObject(8, T0);
      insert.setObject(9, T0);
      insert.executeUpdate();
    }
  }

  private static String one(Connection db, String query) throws SQLException {
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
