package eu.wohlben.qits.epics.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.testdb.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * <b>The verification door's own correctness, which is the point of it.</b> A door that reports a
 * broken estate as clean is worthless and a door that reports a correct estate as broken is worse,
 * so both directions are proven here: one estate migrated by V10 itself and asserted CLEAN with its
 * compared counts visible, and one estate with a named defect per category, each asserted to be
 * caught by the category that owns it and by no other.
 *
 * <p><b>Plain JUnit over {@code EmbeddedPg} + Flyway, deliberately not a {@code @QuarkusTest}.</b>
 * {@code UnifiedBackfillMigrationTest}'s shape, for its reason: a {@code @TestProfile} is a whole
 * Quarkus application at roughly 125 MB of retained metaspace inside a 4 GB CI step, and nothing
 * asserted here needs one — {@link MigrationVerification} is a pure function of a {@link
 * Connection}, which is exactly what makes this test cheap. The route and the CDI bridge are
 * covered one module up by {@code MigrationVerificationApiTest}, which adds no profile either.
 *
 * <p><b>Two seeded estates, each built once and compared once</b>, with every assertion reading the
 * same two reports. Running the comparison per test would be twenty statements per assertion for
 * nothing: the door is a pure read and the databases do not move between tests.
 *
 * <p><b>The good estate is produced by running V10 itself</b> rather than by hand-writing what a
 * correct copy looks like. A fixture built by hand would be a second implementation of the backfill,
 * free to be wrong in the same direction as the door and prove nothing.
 *
 * <h2>The broken estate needs the schema's own constraints out of the way, twice</h2>
 *
 * <p>Two of the defects this door checks for <b>cannot be seeded while the schema stands</b>: V9's
 * {@code fk_entity_membership_parent} forbids a membership naming a parent that is not an entity,
 * and V12's four repointed owner keys forbid a dossier page, asset or comment naming an id {@code
 * entity} does not hold. The test drops those constraints in its own database to write the rows, and
 * the door goes on checking both anyway — because <b>the door also runs against a database whose
 * constraints were repointed by V12, restored from a dump, or fixed by hand in a psql session</b>,
 * and "the schema forbids it" is a claim about one database's current DDL rather than about the rows
 * in front of it. A check whose value is that it is redundant with a constraint is exactly the check
 * that is worth keeping: the constraint is the mechanism, the check is the evidence.
 */
class MigrationVerificationTest {

  private static final String LOCATION = "classpath:db/epics/migration";
  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");
  private static final UUID CAUSE = UUID.fromString("55555555-5555-5555-5555-555555555555");

  private static VerificationReport good;
  private static VerificationReport broken;

  // ---- the two estates -------------------------------------------------------------------------

  @BeforeAll
  static void buildBothEstates() throws Exception {
    good = compare(correctlyMigratedEstate());
    broken = compare(estateWithOneDefectPerCategory());
  }

  /**
   * Two epics (one superseding the other), a ticket, two features, two tasks, and one row of every
   * outward referrer — then V10, V11 and V12 over the top. Nothing here is mutated afterwards, so
   * whatever the door says about it is what it says about a migration that went exactly right.
   */
  private static String correctlyMigratedEstate() throws Exception {
    String url = EmbeddedPg.url("qp_epics_verification_good");
    migrateTo(url, "9");
    try (Connection db = connect(url)) {
      epic(db, "e-1", "p-1", "Alpha", "alpha", "REFINING", null, "the plan", T0);
      epic(db, "e-2", "p-1", "Beta", "beta", "SUPERSEDED", "e-1", null, T0);
      ticket(db, "tk-1", "p-1", "Checkout fails", "checkout-fails", "BUG", "REPORTED", "ada",
          "grace", "what to do", "it occurs at checkout", T0);
      feature(db, "f-1", "e-1", "One", "one", null, null, T0.plusSeconds(1));
      feature(db, "f-2", "e-1", "Two", "two", "f-1", T0.plusSeconds(9), T0.plusSeconds(2));
      task(db, "t-1", "f-1", "repo-1", "Task one", "task-one", null, null, T0.plusSeconds(1));
      task(db, "t-2", "f-1", "repo-2", "Task two", "task-two", "t-1", null, T0.plusSeconds(2));

      dossierPage(db, "dp-1", "e-1", null);
      dossierPage(db, "dp-2", null, "tk-1");
      dossierAsset(db, "da-1", "e-1");
      ticketComment(db, "tc-1", "tk-1");
      auditEntry(db, "a-1", "EPIC", "e-1", "e-1", "CREATE");
      auditEntry(db, "a-2", "FEATURE", "f-1", "e-1", "CREATE");
    }
    migrateTo(url, "12");
    return url;
  }

  /**
   * The same shape, larger, correctly migrated by V10 — and then damaged, one defect per category,
   * each on a row of its own so no two findings can be the same row seen twice.
   *
   * <p>The damage is written in raw SQL against {@code entity} and {@code entity_membership}, which
   * is what a backfill bug would have left behind: the old tables are never touched, because they
   * are the yardstick and a test that moved them would be testing the door against a moved one.
   */
  private static String estateWithOneDefectPerCategory() throws Exception {
    String url = EmbeddedPg.url("qp_epics_verification_broken");
    migrateTo(url, "9");
    try (Connection db = connect(url)) {
      epic(db, "e-1", "p-1", "Alpha", "alpha", "REFINING", null, "alpha desc", T0);
      epic(db, "e-2", "p-1", "Beta", "beta", "REFINING", "e-1", null, T0);
      epic(db, "e-3", "p-1", "Gamma", "gamma", "IMPLEMENTATION", null, "gamma desc", T0);
      epic(db, "e-4", "p-1", "Delta", "delta", "REFINING", null, null, T0);
      epic(db, "e-collide", "p-1", "Shared", "shared", "REFINING", null, null, T0);
      epic(db, "e-edited", "p-1", "Edited", "edited", "REFINING", null, "original", T0);

      ticket(db, "tk-1", "p-1", "Ticket one", "ticket-one", "BUG", "REPORTED", null, null, null,
          "it occurs", T0);
      ticket(db, "tk-collide", "p-1", "Shared ticket", "shared", "BUG", "REPORTED", null, null,
          null, "it also occurs", T0);

      feature(db, "f-1", "e-1", "One", "one", null, null, T0.plusSeconds(1));
      feature(db, "f-2", "e-1", "Two", "two", null, null, T0.plusSeconds(2));
      feature(db, "f-3", "e-1", "Three", "three", null, null, T0.plusSeconds(3));
      feature(db, "f-x", "e-3", "Ex", "ex", null, null, T0.plusSeconds(1));

      task(db, "t-1", "f-1", "repo-1", "Task one", "task-one", null, null, T0.plusSeconds(1));
      task(db, "t-2", "f-1", "repo-2", "Task two", "task-two", null, null, T0.plusSeconds(2));
      task(db, "t-3", "f-1", "repo-3", "Task three", "task-three", null, null, T0.plusSeconds(3));
      task(db, "t-gone", "f-x", "repo-4", "Task gone", "task-gone", null, null, T0.plusSeconds(1));
    }
    migrateTo(url, "12");

    try (Connection db = connect(url)) {
      // A row the copy lost: no entity row and nothing saying it was ever removed.
      execute(db, "delete from entity where id = 't-gone'");

      // A row removed THROUGH the unified model. Same absence, and the audit log is what tells the
      // two apart — which is the doc's own rule for the forward direction.
      execute(db, "delete from entity where id = 't-3'");
      auditEntry(db, "a-del", "TASK", "t-3", "e-1", "DELETE");

      // One property per row, so a finding names exactly one defect.
      execute(db, "update entity set title = 'Retitled' where id = 'e-1'");
      execute(db, "update entity set status = 'ABANDONED', superseded_by_entity_id = null"
          + " where id = 'e-2'");
      execute(db, "update entity set description = 'moved' where id = 'e-3'");
      execute(db, "update entity set slug = 'delta-x' where id = 'e-4'");
      execute(db, "update entity set impetus = 'rewritten' where id = 'tk-1'");
      execute(db, "update entity set repository_id = 'repo-9' where id = 't-1'");

      // An ordinary EDIT since the cutover: the same kind of difference, with updated_at moved.
      execute(db, "update entity set title = 'Edited since', updated_at = updated_at +"
          + " interval '1 hour' where id = 'e-edited'");

      // An edge naming the wrong parent, and beside it a legitimate reparent (the edge's own
      // updated_at moved), so the discriminator is proven in both directions.
      execute(db, "update entity_membership set parent_id = 'e-3' where child_id = 'f-2'");
      execute(db, "update entity_membership set parent_id = 'e-3', updated_at = updated_at +"
          + " interval '1 hour' where child_id = 'f-3'");

      // Positions permuted: t-1 sorts after t-2 now, where (created_at, id) puts it first.
      execute(db, "update entity_membership set position = 5 where child_id = 't-1'");

      // Rows the unified model created after the cutover, which have no old row at all.
      entityRow(db, "e-new", "p-1", "EPIC", "New epic", "new-epic", "p-1", 900);
      entityRow(db, "orphan-child", "p-1", "FEATURE", "Orphan", "orphan", "nobody", 901);
      entityRow(db, "lonely-task", "p-1", "TASK", "Lonely", "lonely", "nobody", 902);

      // An edge whose parent is not an entity. The schema forbids it, so the constraint comes off
      // first — see the class javadoc for why the door checks it regardless.
      execute(db, "alter table entity_membership drop constraint fk_entity_membership_parent");
      execute(db, "insert into entity_membership (id, parent_id, child_id, position, created_at,"
          + " updated_at) values ('orphan-edge', 'nobody', 'orphan-child', 0, now(), now())");

      // Outward references that no longer resolve. V12's four keys forbid these too.
      execute(db, "alter table dossier_page drop constraint fk_dossier_page_owner_epic");
      execute(db, "alter table dossier_page drop constraint fk_dossier_page_owner_ticket");
      execute(db, "alter table dossier_asset drop constraint fk_dossier_asset_epic");
      execute(db, "alter table TicketComment drop constraint fk_ticket_comment_ticket");
      dossierPage(db, "dp-ok", "e-1", null);
      dossierPage(db, "dp-ghost-epic", "ghost-epic", null);
      dossierPage(db, "dp-ghost-ticket", null, "ghost-ticket");
      dossierAsset(db, "da-ghost", "ghost-epic");
      ticketComment(db, "tc-ghost", "ghost-ticket");

      // An audit entry naming a row an old table still holds and entity does not.
      auditEntry(db, "a-dangling", "TASK", "t-gone", "e-1", "UPDATE");
    }
    return url;
  }

  // ---- the clean direction ----------------------------------------------------------------------

  @Test
  void aCorrectlyMigratedEstateIsCleanAndSaysWhatItCompared() {
    assertEquals(VerificationReport.CLEAN, good.verdict());
    assertEquals(0, good.discrepancies());

    // "Clean" has to be disbelievable: every category is present, and each says what it looked at.
    for (VerificationCategory category : good.categories()) {
      assertNotNull(category.question(), category.name() + " must say what it asked");
      assertNotNull(category.compared(), category.name() + " must say what it compared");
      assertFalse(category.truncated(), category.name() + " should not be truncated here");
    }

    // The census is the evidence the rest rests on: 2 epics, 1 ticket, 2 features, 2 tasks, copied.
    VerificationCategory census = category(good, "archetype-census");
    assertEquals(7, census.comparedRows());
    assertEquals(4, census.sample().size());
    assertEquals("2", finding(census, "EPIC").oldValue());
    assertEquals("2", finding(census, "EPIC").newValue());
    assertEquals("1", finding(census, "TICKET").newValue());
    assertEquals("2", finding(census, "FEATURE").newValue());
    assertEquals("2", finding(census, "TASK").newValue());

    // A zero beside a compared count of zero is a vacuous pass. These are not that.
    assertEquals(7, category(good, "property-round-trip").comparedRows());
    assertEquals(7, category(good, "missing-entities").comparedRows());
    assertEquals(4, category(good, "membership-parents").comparedRows());
    assertEquals(4, category(good, "membership-order").comparedRows());
    assertEquals(4, category(good, "orphaned-memberships").comparedRows());
    assertEquals(4, category(good, "rootless-entities").comparedRows());
    assertEquals(4, category(good, "dangling-owner-references").comparedRows());
    assertEquals(2, category(good, "dangling-audit-references").comparedRows());
    assertEquals(7, category(good, "work-branches").comparedRows());

    for (VerificationCategory category : good.categories()) {
      if (category.kind() == VerificationKind.DISCREPANCY) {
        assertEquals(0, category.findings(), category.name() + " found something");
      }
    }
  }

  /** The caveats travel with the verdict, in words, on a clean answer as much as on a dirty one. */
  @Test
  void theScopeSaysForwardOnlyOnEveryAnswer() {
    for (VerificationReport report : List.of(good, broken)) {
      assertTrue(report.scope().direction().contains("FORWARD ONLY"));
      assertTrue(report.scope().direction().contains("REVERSE"));
      assertTrue(report.scope().createdSinceCutover().contains("entity >= old"));
      assertTrue(report.scope().deCollidedSlugs().contains("re-slugged"));
      assertFalse(report.scope().notChecked().isEmpty(), "an empty notChecked would be a lie");
    }
  }

  // ---- one case per category ---------------------------------------------------------------------

  @Test
  void anOldRowWithNoEntityIsCaughtAndADeletedOneIsNot() {
    VerificationCategory missing = category(broken, "missing-entities");
    assertEquals(VerificationKind.DISCREPANCY, missing.kind());
    assertEquals(1, missing.findings());
    assertEquals("t-gone", missing.sample().get(0).id());

    VerificationCategory deleted = category(broken, "expected-deleted-since-the-cutover");
    assertEquals(VerificationKind.EXPECTED, deleted.kind());
    assertEquals(1, deleted.findings());
    assertEquals("t-3", deleted.sample().get(0).id());
  }

  @Test
  void everyPropertyThatDoesNotRoundTripIsNamedWithBothValues() {
    VerificationCategory properties = category(broken, "property-round-trip");
    assertEquals(VerificationKind.DISCREPANCY, properties.kind());
    assertEquals(7, properties.findings());

    assertFinding(properties, "e-1", "title", "Alpha", "Retitled");
    assertFinding(properties, "e-2", "status", "REFINING", "ABANDONED");
    assertFinding(properties, "e-2", "superseded_by", "e-1", null);
    assertFinding(properties, "e-3", "description", "gamma desc", "moved");
    assertFinding(properties, "e-4", "slug", "delta", "delta-x");
    assertFinding(properties, "tk-1", "impetus", "it occurs", "rewritten");
    assertFinding(properties, "t-1", "repository_id", "repo-1", "repo-9");
  }

  /**
   * The fourth finding, which the epic did not name: the unified model has been written the whole
   * time, and a row edited since the copy legitimately disagrees with its frozen old row. {@code
   * updated_at} is the discriminator, and it is reported rather than suppressed.
   */
  @Test
  void aRowEditedSinceTheCutoverIsExpectedRatherThanADiscrepancy() {
    VerificationCategory changed = category(broken, "expected-changed-since-the-cutover");
    assertEquals(VerificationKind.EXPECTED, changed.kind());
    assertEquals(1, changed.findings());
    assertFinding(changed, "e-edited", "title", "Edited", "Edited since");

    // And it is in the failing category exactly once: not at all.
    assertTrue(
        category(broken, "property-round-trip").sample().stream()
            .noneMatch(found -> found.id().equals("e-edited")));
  }

  @Test
  void aMembershipNamingTheWrongParentIsCaughtAndARealReparentIsNot() {
    VerificationCategory parents = category(broken, "membership-parents");
    assertEquals(VerificationKind.DISCREPANCY, parents.kind());
    assertEquals(1, parents.findings());
    assertEquals("f-2", parents.sample().get(0).id());
    assertEquals("e-1", parents.sample().get(0).oldValue());
    assertEquals("e-3", parents.sample().get(0).newValue());

    VerificationCategory reparented = category(broken, "expected-reparented-since-the-cutover");
    assertEquals(1, reparented.findings());
    assertEquals("f-3", reparented.sample().get(0).id());
  }

  /**
   * Ordering is its own assertion and never folded into the property check — it is the easiest thing
   * in the backfill to lose silently, because every position is a plausible integer.
   */
  @Test
  void siblingsInTheWrongOrderAreTheirOwnCategory() {
    VerificationCategory order = category(broken, "membership-order");
    assertEquals(VerificationKind.DISCREPANCY, order.kind());
    assertEquals(2, order.findings());
    assertTrue(order.sample().stream().anyMatch(found -> found.id().equals("t-1")));
    assertTrue(order.sample().stream().anyMatch(found -> found.id().equals("t-2")));
    assertTrue(order.sample().get(0).detail().contains("f-1"));
  }

  @Test
  void anOrphanedMembershipAndARootlessDescendantAreCaught() {
    VerificationCategory orphans = category(broken, "orphaned-memberships");
    assertEquals(1, orphans.findings());
    assertEquals("orphan-edge", orphans.sample().get(0).id());
    assertTrue(orphans.sample().get(0).detail().contains("parent"));

    VerificationCategory rootless = category(broken, "rootless-entities");
    assertEquals(1, rootless.findings());
    assertEquals("lonely-task", rootless.sample().get(0).id());
    // EPIC and TICKET may be roots, so the eight non-root rows are what was compared.
    assertEquals(8, rootless.comparedRows());
  }

  @Test
  void everyOutwardReferenceThatNoLongerResolvesIsListed() {
    VerificationCategory owners = category(broken, "dangling-owner-references");
    assertEquals(4, owners.findings());
    assertTrue(owners.sample().stream()
        .anyMatch(found -> found.detail().startsWith("dossier_page.epic_id")));
    assertTrue(owners.sample().stream()
        .anyMatch(found -> found.detail().startsWith("dossier_page.ticket_id")));
    assertTrue(owners.sample().stream()
        .anyMatch(found -> found.detail().startsWith("dossier_asset.epic_id")));
    assertTrue(owners.sample().stream()
        .anyMatch(found -> found.detail().startsWith("ticketcomment.ticket_id")));

    VerificationCategory audit = category(broken, "dangling-audit-references");
    assertEquals(1, audit.findings());
    assertEquals("a-dangling", audit.sample().get(0).id());
    // The DELETE row for t-3 is NOT a finding: the log is deliberately allowed to outlive its row.
    assertTrue(audit.sample().stream().noneMatch(found -> found.id().equals("a-del")));
  }

  @Test
  void aWorkBranchThatNoLongerDerivesTheSameNameIsListed() {
    VerificationCategory branches = category(broken, "work-branches");
    assertEquals(2, branches.findings());
    assertFindingValues(branches, "e-4", "epic/delta", "epic/delta-x");
    assertFindingValues(branches, "f-2", "feature/alpha/two", "feature/gamma/two");
  }

  // ---- the three findings, explicitly --------------------------------------------------------------

  /** Finding 1, the half of it a test can state: the reverse direction is never asserted. */
  @Test
  void anEntityCreatedAfterTheCutoverIsNotADiscrepancy() {
    VerificationCategory created = category(broken, "entities-created-since-the-cutover");
    assertEquals(VerificationKind.INFORMATIONAL, created.kind());
    assertEquals(3, created.findings());
    assertTrue(created.sample().stream().anyMatch(found -> found.id().equals("e-new")));

    // It appears in no discrepancy category — the reverse assertion does not exist.
    for (VerificationCategory category : broken.categories()) {
      if (category.kind() == VerificationKind.DISCREPANCY) {
        assertTrue(
            category.sample().stream().noneMatch(found -> "e-new".equals(found.id())),
            "e-new reached " + category.name());
      }
    }
  }

  /** Finding 2: V10's own de-collision, reported as expected and never as a slug mismatch. */
  @Test
  void aDeCollidedSlugIsReportedAsExpectedAndNotAsAMismatch() {
    VerificationCategory deCollided = category(broken, "expected-de-collided-slugs");
    assertEquals(VerificationKind.EXPECTED, deCollided.kind());
    assertEquals(1, deCollided.findings());

    VerificationFinding found = deCollided.sample().get(0);
    assertEquals("tk-collide", found.id());
    assertEquals("shared", found.oldValue());
    assertEquals("shared-2", found.newValue());
    assertTrue(found.detail().contains("e-collide"), "it names the epic it collided with");
    assertTrue(found.detail().contains("ticket/shared"), "it names the branch that does not move");

    // The same row is NOT a slug discrepancy, and the ticket's other properties are still compared.
    assertTrue(
        category(broken, "property-round-trip").sample().stream()
            .noneMatch(f -> f.id().equals("tk-collide")));
    assertTrue(
        category(broken, "work-branches").sample().stream()
            .noneMatch(f -> f.id().equals("tk-collide")));
  }

  /** Finding 3: counts are a relation, not an equality, and the direction is what matters. */
  @Test
  void countsAreCleanInOneDirectionAndADiscrepancyInTheOther() {
    VerificationCategory counts = category(broken, "archetype-counts");
    assertEquals(VerificationKind.DISCREPANCY, counts.kind());

    // EPIC: six old rows, seven entity rows. A surplus is the ordinary state and is not a finding.
    VerificationCategory census = category(broken, "archetype-census");
    assertEquals("6", finding(census, "EPIC").oldValue());
    assertEquals("7", finding(census, "EPIC").newValue());
    assertTrue(counts.sample().stream().noneMatch(found -> found.id().equals("EPIC")));

    // TASK: four old rows, three entity rows. That direction is a defect.
    assertEquals("4", finding(census, "TASK").oldValue());
    assertEquals("3", finding(census, "TASK").newValue());
    assertEquals(1, counts.findings());
    assertEquals("TASK", counts.sample().get(0).id());

    assertEquals(VerificationReport.DISCREPANCIES, broken.verdict());
  }

  // ---- the pieces ------------------------------------------------------------------------------------

  private static VerificationReport compare(String url) throws Exception {
    try (Connection db = connect(url)) {
      return MigrationVerification.compare(db);
    }
  }

  private static VerificationCategory category(VerificationReport report, String name) {
    return report.categories().stream()
        .filter(category -> category.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no category named " + name));
  }

  private static VerificationFinding finding(VerificationCategory category, String id) {
    return category.sample().stream()
        .filter(found -> found.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError(category.name() + " has no finding for " + id));
  }

  private static void assertFinding(
      VerificationCategory category, String id, String property, String oldValue, String newValue) {
    VerificationFinding found =
        category.sample().stream()
            .filter(row -> row.id().equals(id) && property.equals(row.detail()))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError(category.name() + " missed " + id + "." + property));
    assertEquals(oldValue, found.oldValue(), id + "." + property + " old value");
    assertEquals(newValue, found.newValue(), id + "." + property + " new value");
  }

  private static void assertFindingValues(
      VerificationCategory category, String id, String oldValue, String newValue) {
    VerificationFinding found = finding(category, id);
    assertEquals(oldValue, found.oldValue());
    assertEquals(newValue, found.newValue());
  }

  private static void migrateTo(String url, String version) {
    Flyway.configure()
        .dataSource(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD)
        .locations(LOCATION)
        .target(MigrationVersion.fromVersion(version))
        .load()
        .migrate();
  }

  private static Connection connect(String url) throws SQLException {
    return DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
  }

  private static void execute(Connection db, String sql) throws SQLException {
    try (Statement statement = db.createStatement()) {
      statement.execute(sql);
    }
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
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into Epic (id, causation_id, project_id, title, slug, status,"
                + " superseded_by_epic_id, description, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, id);
      insert.setObject(2, CAUSE);
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
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into Ticket (id, causation_id, project_id, title, slug, type, status, assignee,"
                + " created_by, description, impetus, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, id);
      insert.setObject(2, CAUSE);
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
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into Feature (id, causation_id, epic_id, title, slug, description,"
                + " depends_on_feature_id, implemented_on, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, id);
      insert.setObject(2, CAUSE);
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
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into Task (id, causation_id, feature_id, repository_id, title, slug,"
                + " description, depends_on_task_id, implemented_at, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, id);
      insert.setObject(2, CAUSE);
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

  /** An entity row with no old row behind it — what a create through the unified model leaves. */
  private static void entityRow(
      Connection db,
      String id,
      String projectId,
      String archetype,
      String title,
      String slug,
      String slugScope,
      long number)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into entity (id, project_id, archetype, number, title, slug, slug_scope,"
                + " created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, now(), now())")) {
      insert.setString(1, id);
      insert.setString(2, projectId);
      insert.setString(3, archetype);
      insert.setLong(4, number);
      insert.setString(5, title);
      insert.setString(6, slug);
      insert.setString(7, slugScope);
      insert.executeUpdate();
    }
  }

  private static void dossierPage(Connection db, String id, String epicId, String ticketId)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into dossier_page (id, epic_id, ticket_id, slug, title, position, body,"
                + " version, created_at, updated_at) values (?, ?, ?, ?, ?, 0, 'body', 0,"
                + " now(), now())")) {
      insert.setString(1, id);
      insert.setString(2, epicId);
      insert.setString(3, ticketId);
      insert.setString(4, id);
      insert.setString(5, "Page " + id);
      insert.executeUpdate();
    }
  }

  private static void dossierAsset(Connection db, String id, String epicId) throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into dossier_asset (id, epic_id, kind, mime_type, label, bytes, created_at)"
                + " values (?, ?, 'IMAGE', 'image/png', 'a figure', ?, now())")) {
      insert.setString(1, id);
      insert.setString(2, epicId);
      insert.setBytes(3, new byte[] {1, 2, 3});
      insert.executeUpdate();
    }
  }

  private static void ticketComment(Connection db, String id, String ticketId) throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into TicketComment (id, ticket_id, author, body, created_at, updated_at)"
                + " values (?, ?, 'ada', 'a remark', now(), now())")) {
      insert.setString(1, id);
      insert.setString(2, ticketId);
      insert.executeUpdate();
    }
  }

  private static void auditEntry(
      Connection db, String id, String entityType, String entityId, String epicId, String operation)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into AuditEntry (id, entity_type, entity_id, epic_id, operation, changed_by,"
                + " changed_at, snapshot) values (?, ?, ?, ?, ?, 'ada', now(), '{}')")) {
      insert.setString(1, id);
      insert.setString(2, entityType);
      insert.setString(3, entityId);
      insert.setString(4, epicId);
      insert.setString(5, operation);
      insert.executeUpdate();
    }
  }
}
