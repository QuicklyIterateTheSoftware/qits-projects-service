package eu.wohlben.qits.projects.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.projects.testdb.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * What V73 does (qits-1869): {@code branch_delivery} gains a nullable {@code deleted_sha}, and the
 * qits-qits wrapper's WITHDRAWN rows of the listed ticket branches get the tip their deletion
 * carried — only those: another step, another repository or an unlisted branch keeps null. Plain
 * JUnit on a database of its own, like the other migration tests here.
 */
class BranchDeliveryDeletedShaMigrationTest {

  private static final String LOCATION = "classpath:db/projects/migration";

  private static final String WRAPPER = "5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397";

  @Test
  void theListedWithdrawnWrapperRowsGetTheirTipAndNothingElseDoes() throws Exception {
    String url = EmbeddedPg.url("qp_projects_v73_branch_delivery_deleted_sha");
    migrateTo(url, "72");
    try (Connection db = connect(url)) {
      row(db, WRAPPER, "ticket/qits-1767", "WITHDRAWN");
      row(db, WRAPPER, "ticket/qits-1772", "JOINED");
      row(db, "another-repo", "ticket/qits-1768", "WITHDRAWN");
      row(db, WRAPPER, "ticket/qits-1000", "WITHDRAWN");
    }

    migrateTo(url, "73");

    try (Connection db = connect(url)) {
      assertEquals(
          "435ca77d29d4b6384c4db3a598c08d66d5c0c993",
          deletedSha(db, WRAPPER, "ticket/qits-1767"));
      assertNull(deletedSha(db, WRAPPER, "ticket/qits-1772"), "not WITHDRAWN");
      assertNull(deletedSha(db, "another-repo", "ticket/qits-1768"), "not the wrapper");
      assertNull(deletedSha(db, WRAPPER, "ticket/qits-1000"), "not listed");
    }
  }

  private static void row(Connection db, String repoId, String branch, String step)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into branch_delivery (project_id, repo_id, repo_name, branch, step, changed_at)"
                + " values ('qits', ?, 'qits-qits', ?, ?, ?)")) {
      insert.setString(1, repoId);
      insert.setString(2, branch);
      insert.setString(3, step);
      insert.setTimestamp(4, Timestamp.from(Instant.parse("2026-11-01T10:00:00Z")));
      insert.executeUpdate();
    }
  }

  private static String deletedSha(Connection db, String repoId, String branch)
      throws SQLException {
    try (PreparedStatement select =
        db.prepareStatement(
            "select deleted_sha from branch_delivery where repo_id = ? and branch = ?")) {
      select.setString(1, repoId);
      select.setString(2, branch);
      try (ResultSet rows = select.executeQuery()) {
        rows.next();
        return rows.getString(1);
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
