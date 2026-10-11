package eu.wohlben.qits.projects.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * What V41 does to a database that already has release requests (qits-1158): every row is numbered
 * per repository in creation order, named {@code <repo_name>-rr-<n>} (the current alias where the
 * row recorded no name, the uuid where there is none at all), keeps {@code release/<uuid>} as its
 * stored backing branch, and the counter starts above the highest number.
 */
class ReleaseRequestQualifiedIdMigrationTest {

  private static final String LOCATION = "classpath:db/projects/migration";

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  @Test
  void existingRequestsAreNumberedNamedAndKeepTheirBranch() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v41_release_request_qualified_id");
    migrateTo(url, "40");
    try (Connection db = connect(url)) {
      try (Statement sql = db.createStatement()) {
        sql.executeUpdate("insert into project (id, name, slug) values ('p-1', 'P', 'p')");
        sql.executeUpdate("insert into repository (id, project_id) values ('repo-b', 'p-1')");
        sql.executeUpdate(
            "insert into repository_name (id, project_id, repository_id, name)"
                + " values ('n-1', 'p-1', 'repo-b', 'beta-service')");
      }
      // Two in repo-a, the second created first; a tie on created_at broken by id.
      request(db, "a-2", "repo-a", "alpha-service", 2);
      request(db, "a-1", "repo-a", "alpha-service", 1);
      request(db, "a-3", "repo-a", "alpha-renamed", 2);
      // No recorded name: the current alias stands in.
      request(db, "b-1", "repo-b", null, 1);
      // No name anywhere: the uuid itself.
      request(db, "c-1", "repo-c", null, 1);
    }

    migrateTo(url, "41");

    try (Connection db = connect(url)) {
      Map<String, String> rows = new LinkedHashMap<>();
      try (Statement sql = db.createStatement();
          ResultSet found =
              sql.executeQuery(
                  "select id, number, qualified_id, backing_branch from release_request"
                      + " order by id")) {
        while (found.next()) {
          rows.put(
              found.getString(1),
              found.getLong(2) + " " + found.getString(3) + " " + found.getString(4));
        }
      }
      assertEquals("1 alpha-service-rr-1 release/a-1", rows.get("a-1"), "oldest first");
      assertEquals("2 alpha-service-rr-2 release/a-2", rows.get("a-2"));
      assertEquals(
          "3 alpha-renamed-rr-3 release/a-3", rows.get("a-3"), "the name the row was made under");
      assertEquals("1 beta-service-rr-1 release/b-1", rows.get("b-1"), "the alias stands in");
      assertEquals("1 c-1 release/c-1", rows.get("c-1"), "no name at all: the uuid");

      assertEquals(4, nextNumber(db, "repo-a"), "seeded above the highest number");
      assertEquals(2, nextNumber(db, "repo-b"));

      SQLException duplicate =
          assertThrows(SQLException.class, () -> request(db, "a-4", "repo-a", "x", 3, 1L));
      assertTrue(
          duplicate.getMessage().contains("uq_release_request_repo_number"),
          duplicate.getMessage());
    }
  }

  private static void request(Connection db, String id, String repoId, String repoName, int minute)
      throws SQLException {
    request(db, id, repoId, repoName, minute, null);
  }

  private static void request(
      Connection db, String id, String repoId, String repoName, int minute, Long number)
      throws SQLException {
    boolean numbered = number != null;
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into release_request (id, repo_id, repo_name, summary, state, created_at,"
                + " updated_at, armed_at, retryable"
                + (numbered ? ", number, qualified_id, backing_branch" : "")
                + ") values (?, ?, ?, 's', 'PENDING', ?, ?, ?, false"
                + (numbered ? ", ?, ?, ?" : "")
                + ")")) {
      OffsetDateTime at = T0.plusMinutes(minute);
      insert.setString(1, id);
      insert.setString(2, repoId);
      insert.setString(3, repoName);
      insert.setObject(4, at);
      insert.setObject(5, at);
      insert.setObject(6, at);
      if (numbered) {
        insert.setLong(7, number);
        insert.setString(8, id);
        insert.setString(9, "release/" + id);
      }
      insert.executeUpdate();
    }
  }

  private static long nextNumber(Connection db, String repoId) throws SQLException {
    try (PreparedStatement query =
        db.prepareStatement(
            "select next_number from release_request_number_sequence where repo_id = ?")) {
      query.setString(1, repoId);
      try (ResultSet found = query.executeQuery()) {
        assertTrue(found.next(), "a counter row for " + repoId);
        return found.getLong(1);
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
