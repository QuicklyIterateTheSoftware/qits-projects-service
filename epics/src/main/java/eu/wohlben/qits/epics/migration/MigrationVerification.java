package eu.wohlben.qits.epics.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The migration verification door's whole comparison: the four frozen old tables against the
 * unified model, on whatever database the {@link Connection} is open on.</b>
 *
 * <p>It is a pure function of a connection — no CDI, no entity manager, no Quarkus — so it can be
 * driven against a live database from a request thread, against an embedded postgres from a plain
 * JUnit test, and against a half-migrated database from a psql session, all with the identical code
 * path. {@code MigrationVerificationService} is the three lines that bridge CDI to it;
 * {@code service/…/epics/api/MigrationVerificationController} is the route. Neither holds a rule.
 *
 * <h2>TEMPORARY. Deleted by V13, with the tables it reads.</h2>
 *
 * <p>See {@code package-info.java}. This class's comparison target is {@code Epic}, {@code Ticket},
 * {@code Feature} and {@code Task}; V13 drops them, and on that day this file compares nothing and
 * goes.
 *
 * <h2>The three things a door written the obvious way gets wrong</h2>
 *
 * <p>Each of these would make a <em>correct</em> migration report as broken, which is the worst
 * failure this door has: a false alarm on the evidence somebody is about to act on either stops a
 * cleanup that should happen or, worse, teaches the reader to discount the whole document.
 *
 * <ol>
 *   <li><b>FORWARD ONLY.</b> The epic that commissioned this door asked for both directions — every
 *       old row has an entity, <em>and</em> no entity has an id no old row had. The second half was
 *       true when it was written and has been false since {@code FeatureService} and {@code
 *       TaskService} stopped writing legacy rows; with the epic/ticket mirror retired by V12 it is
 *       false for all four archetypes. <b>The old tables are a frozen snapshot of the estate as V10
 *       found it, not a live mirror.</b> So the reverse assertion is not made, anywhere, and the
 *       omission is stated on the wire ({@link VerificationScope#direction()}) and not only here: a
 *       person reading a clean result has to know which half of the comparison it is.
 *   <li><b>The V10 epic/ticket slug de-collision.</b> {@code slug_scope} makes an epic and a ticket
 *       in one project share a scope where two tables did not, so V10 re-slugged the <em>entity</em>
 *       row of every ticket whose slug collided with an epic's — and deliberately left the {@code
 *       Ticket} row alone. Those rows' slugs differ on purpose. They are detected here exactly the
 *       way V10 identified them (a ticket whose slug equals some epic's slug in the same project,
 *       read off the <b>old</b> tables, so the predicate cannot drift with the new model) and
 *       reported under their own name, {@code expected-de-collided-slugs}, carrying both values —
 *       <b>not silently dropped</b>, because the difference means a branch already cut at {@code
 *       ticket/&lt;old-slug&gt;} no longer derives and a person has to know.
 *   <li><b>Anything created since the cutover has no old row at all.</b> Counts are therefore
 *       legitimately unequal, and the relation asserted is {@code entity &gt;= old} per archetype
 *       rather than equality. The surplus is its own informational category with the ids in it.
 * </ol>
 *
 * <h2>The fourth thing, which the epic did not name and which matters as much</h2>
 *
 * <p><b>The model has been written the whole time.</b> A row edited through the new model since V10
 * legitimately disagrees with its frozen old row; a row <em>deleted</em> through the new model
 * legitimately has no entity row at all. Both look exactly like a backfill defect in SQL, and a door
 * that ignored them would report every day's ordinary work as a migration failure.
 *
 * <ul>
 *   <li><b>{@code updated_at} is the discriminator for an edit.</b> V10 copies the column verbatim,
 *       and every write through the new model bumps it ({@code @UpdateTimestamp}), so {@code
 *       entity.updated_at &gt; old.updated_at} is exactly "this row was written after the copy". A
 *       property mismatch on such a row is {@code expected-changed-since-the-cutover}; a property
 *       mismatch on a row whose timestamps still agree is a discrepancy. <b>What that costs, stated
 *       rather than hidden:</b> a genuine backfill defect on a row that was later edited is excused
 *       by this rule and lands in the expected category rather than the failing one. It is reported
 *       with both values either way, so the cost is a misfiling that a reader can see, not a
 *       suppression they cannot.
 *   <li><b>A DELETE audit row is the discriminator for a deletion</b> — the doc's own wording:
 *       "every old row resolves to an entity, or to the DELETE audit row that says it was removed
 *       through the new model". {@code auditentry} is append-only and deliberately not foreign-keyed
 *       back (V1), so it outlives the row, which is what makes it able to answer this at all.
 *   <li><b>Ordering is compared as RELATIVE ORDER, never as absolute position</b>, and that is not a
 *       weakening. {@code EntityMembershipRepository.closeGapAfter} renumbers a removal's tail with a
 *       bulk HQL update, which does not bump {@code updated_at} — so absolute positions drift with
 *       nothing to discriminate on the moment anything is deleted, while the <em>order</em> of the
 *       surviving siblings is exactly what V10 promised to preserve and exactly what a listing
 *       draws. A permutation is caught; a renumber is not a finding, because it is not a defect.
 * </ul>
 *
 * <h2>What it costs against a large database</h2>
 *
 * <p>Sixteen statements, every one of them set-based, and <b>not one query per row</b> — the N+1
 * this model makes easy is the single mistake a door reading whole tables cannot afford. The
 * checks are anti-joins and left joins against {@code entity(id)} and {@code
 * entity_membership(child_id)}, both of which are unique indexes, so postgres hashes or merges the
 * old table against an index rather than probing per row. The dominant cost is therefore one
 * sequential scan of each old table per category that reads it, and the whole run is O(estate) with
 * a small constant — seconds on an estate of this size, and it is a read that takes no lock anything
 * else waits on.
 *
 * <p><b>Memory is bounded by construction and not by hope.</b> Every finding query carries {@code
 * count(*) over ()} beside a {@code limit}, so one statement answers both "how many" and "the first
 * N", and at most {@link #MAX_FINDINGS_PER_CATEGORY} rows per category are ever materialised. A
 * catastrophically broken estate produces a report of a fixed size with honest totals rather than an
 * OOM in a service somebody was using.
 *
 * <p><b>The run is not one snapshot, and it does not pretend to be.</b> Statements run at READ
 * COMMITTED, so each sees its own instant. That is harmless here and saying why is the point: the
 * four old tables have had no writer for several commits and cannot move at all, and the only table
 * that can move is {@code entity} — where a row arriving mid-run shows up as a <em>surplus</em>,
 * which is an informational category rather than a failure. Buying a repeatable-read snapshot would
 * cost a long-lived transaction on a live database to remove a discrepancy that cannot occur.
 */
public final class MigrationVerification {

  /**
   * The per-category cap on listed rows. The count beside it is the real total, so truncation is
   * visible rather than silent — see {@link VerificationCategory#truncated()}.
   *
   * <p>One hundred because the document is read by a person: the first hundred rows of a category
   * tell you what kind of wrong it is, and the total tells you how much of it there is. Nothing
   * downstream pages through this.
   */
  public static final int MAX_FINDINGS_PER_CATEGORY = 100;

  /**
   * The caveats, spelled once and carried on every answer. They are constants so two runs differ
   * only where the estate differs.
   */
  public static final VerificationScope SCOPE =
      new VerificationScope(
          "FORWARD ONLY. Every row in Epic, Ticket, Feature and Task is checked against the unified"
              + " model. The REVERSE is deliberately NOT checked: 'no entity has an id that no old"
              + " row had' is false by design, because the four old tables have had no writer since"
              + " the cutover and are a frozen snapshot of the estate as V10 found it, not a live"
              + " mirror. A clean result here says the copy is intact; it says nothing whatever"
              + " about rows created since.",
          "Counts are compared as entity >= old per archetype, never as equality. Everything created"
              + " through the unified model since the cutover has no old row at all, so a surplus is"
              + " the ordinary state. It is listed under 'entities-created-since-the-cutover' as"
              + " evidence, and only the WRONG direction (old > entity) is a discrepancy.",
          "V10 re-slugged the ENTITY row of every ticket whose slug collided with an epic's in the"
              + " same project, because slug_scope makes them share a scope where two tables did"
              + " not; the epic kept its slug and the Ticket row kept the original. Those rows are"
              + " detected the way V10 identified them — read off the OLD tables — and reported"
              + " under 'expected-de-collided-slugs' with both values. They are NOT slug"
              + " discrepancies, and they are NOT suppressed: a ticket whose slug moved has a branch"
              + " at ticket/<old-slug> that does not move with it.",
          "A row written through the unified model since the copy has entity.updated_at strictly"
              + " greater than the frozen old row's, because V10 copied that column verbatim and"
              + " every later write bumps it. Such a mismatch is reported under"
              + " 'expected-changed-since-the-cutover' with both values rather than as a defect."
              + " THE COST: a genuine backfill defect on a row that has since been edited is"
              + " misfiled there too. It is still listed, with both values; nothing is hidden.",
          "An old row with no entity row is a DISCREPANCY unless auditentry holds a DELETE for that"
              + " id, which is the record of its removal through the unified model. Those are"
              + " reported under 'expected-deleted-since-the-cutover'.",
          "No work branch is stored in this database: a branch name is derived from slugs and"
              + " ancestry (control/WorkBranches). So what is compared is the DERIVED name — the"
              + " one the old tables imply against the one the unified model implies — which is what"
              + " catches a slug or a parent that moved without anybody noticing. The stored"
              + " branches on refinement rows live in the projects database, which this door holds"
              + " no connection to; that is named under notChecked.",
          "Each category lists at most " + MAX_FINDINGS_PER_CATEGORY + " rows. 'findings' is always"
              + " the full count and 'truncated' says whether the list is shorter than it.",
          List.of(
              "The projects database. This door holds one connection, to the epics database, because"
                  + " the epics module depends on domain nowhere. So refinement.epic_id,"
                  + " refinement.branch and workspace references on the other side of that boundary"
                  + " are not compared here.",
              "Anything created through the unified model since the cutover, beyond counting it and"
                  + " listing its ids. There is nothing to compare it against.",
              "Whether a difference excused as 'changed since the cutover' was a legitimate edit or a"
                  + " backfill defect on a row that was later edited. The timestamps cannot tell"
                  + " those apart and neither can this door.",
              "The absolute values of entity_membership.position. Only the relative order of the"
                  + " siblings is asserted, because closeGapAfter renumbers with a bulk update that"
                  + " leaves no trace and a renumber is not a defect.",
              "auditentry rows naming an id that exists in neither the old tables nor entity. Those"
                  + " are rows deleted before V10 ran, and the log is deliberately not foreign-keyed"
                  + " back so that it outlives them."));

  private MigrationVerification() {}

  // ---- the entry point ----------------------------------------------------------------------

  /**
   * Runs every check and answers the whole report. The connection is borrowed, never closed here,
   * and nothing is written to it — this door issues {@code select} and nothing else, which is what
   * makes it safe to press against production as often as anybody likes.
   */
  public static VerificationReport compare(Connection db) throws SQLException {
    Census census = census(db);
    Map<String, Long> entitiesByArchetype = entitiesByArchetype(db);

    List<VerificationCategory> categories = new ArrayList<>();
    categories.add(archetypeCensus(census, entitiesByArchetype));
    categories.add(countDirection(census, entitiesByArchetype));
    categories.add(missingEntities(db, census));
    categories.add(properties(db, census, false));
    categories.add(membershipParents(db, census, false));
    categories.add(membershipOrder(db, census));
    categories.add(orphanedMemberships(db, census));
    categories.add(rootlessEntities(db, census));
    categories.add(danglingOwnerReferences(db, census));
    categories.add(danglingAuditReferences(db, census));
    categories.add(workBranches(db, census));
    categories.add(createdSinceCutover(db, census));
    categories.add(deletedSinceCutover(db, census));
    categories.add(deCollidedSlugs(db, census));
    categories.add(properties(db, census, true));
    categories.add(membershipParents(db, census, true));

    return VerificationReport.of(Instant.now(), SCOPE, categories);
  }

  // ---- the census ---------------------------------------------------------------------------

  /**
   * Every compared-count the report needs, in one round trip. They are scalar subqueries rather than
   * a count per category because a category that answers no findings still has to say what it
   * looked at, and a query that returned no rows could not tell it.
   */
  private record Census(
      long epics,
      long tickets,
      long features,
      long tasks,
      long entities,
      long memberships,
      long dossierPages,
      long dossierAssets,
      long ticketComments,
      long auditEntries,
      long nonRootEntities,
      long pairedEdges,
      long oldRowsWithEntity) {

    long oldRows() {
      return epics + tickets + features + tasks;
    }

    long descendants() {
      return features + tasks;
    }

    long ownerReferences() {
      return dossierPages + dossierAssets + ticketComments;
    }
  }

  private static final String CENSUS_SQL =
      """
      select (select count(*) from Epic)                                   as epics,
             (select count(*) from Ticket)                                 as tickets,
             (select count(*) from Feature)                                as features,
             (select count(*) from Task)                                   as tasks,
             (select count(*) from entity)                                 as entities,
             (select count(*) from entity_membership)                      as memberships,
             (select count(*) from dossier_page)                           as dossier_pages,
             (select count(*) from dossier_asset)                          as dossier_assets,
             (select count(*) from TicketComment)                          as ticket_comments,
             (select count(*) from AuditEntry)                             as audit_entries,
             (select count(*) from entity
               where archetype not in ('EPIC', 'TICKET'))                  as non_root_entities,
             (select count(*) from entity_membership m
               where exists (select 1 from Feature f
                              where f.id = m.child_id and f.epic_id = m.parent_id)
                  or exists (select 1 from Task t
                              where t.id = m.child_id and t.feature_id = m.parent_id)) as paired_edges,
             (select count(*) from (select id from Epic
                                    union all select id from Ticket
                                    union all select id from Feature
                                    union all select id from Task) o
               where exists (select 1 from entity e where e.id = o.id))    as old_with_entity
      """;

  private static Census census(Connection db) throws SQLException {
    try (Statement sql = db.createStatement();
        ResultSet row = sql.executeQuery(CENSUS_SQL)) {
      row.next();
      return new Census(
          row.getLong("epics"),
          row.getLong("tickets"),
          row.getLong("features"),
          row.getLong("tasks"),
          row.getLong("entities"),
          row.getLong("memberships"),
          row.getLong("dossier_pages"),
          row.getLong("dossier_assets"),
          row.getLong("ticket_comments"),
          row.getLong("audit_entries"),
          row.getLong("non_root_entities"),
          row.getLong("paired_edges"),
          row.getLong("old_with_entity"));
    }
  }

  private static Map<String, Long> entitiesByArchetype(Connection db) throws SQLException {
    Map<String, Long> byArchetype = new LinkedHashMap<>();
    try (Statement sql = db.createStatement();
        ResultSet rows =
            sql.executeQuery("select archetype, count(*) as n from entity group by archetype")) {
      while (rows.next()) {
        byArchetype.put(rows.getString("archetype"), rows.getLong("n"));
      }
    }
    return byArchetype;
  }

  /** The archetypes, in the order V10 inserts them, which is the order everything here reports in. */
  private static final List<String> ARCHETYPES = List.of("EPIC", "TICKET", "FEATURE", "TASK");

  private static long oldCount(Census census, String archetype) {
    return switch (archetype) {
      case "EPIC" -> census.epics();
      case "TICKET" -> census.tickets();
      case "FEATURE" -> census.features();
      default -> census.tasks();
    };
  }

  // ---- the categories -----------------------------------------------------------------------

  /**
   * The numbers themselves, always listed, for every archetype — the evidence a reader needs before
   * any of the checks below mean anything. A clean report whose census says four zeros is a vacuous
   * pass and this is what makes that visible.
   */
  private static VerificationCategory archetypeCensus(
      Census census, Map<String, Long> entitiesByArchetype) {
    List<VerificationFinding> rows = new ArrayList<>();
    for (String archetype : ARCHETYPES) {
      long old = oldCount(census, archetype);
      long merged = entitiesByArchetype.getOrDefault(archetype, 0L);
      rows.add(
          new VerificationFinding(
              archetype,
              archetype,
              "old rows against entity rows (surplus " + (merged - old) + ")",
              Long.toString(old),
              Long.toString(merged)));
    }
    return new VerificationCategory(
        "archetype-census",
        VerificationKind.INFORMATIONAL,
        "How many rows each old table holds and how many entity rows carry that archetype. Listed"
            + " whatever the verdict, because every check below is only as meaningful as what it"
            + " ran over.",
        census.oldRows(),
        "rows in Epic, Ticket, Feature and Task, against " + census.entities() + " entity rows",
        rows.size(),
        false,
        List.copyOf(rows));
  }

  /**
   * The count check proper, and it asserts {@code entity >= old} rather than equality — the third of
   * the three findings. Only the wrong direction is a finding; the surplus is evidence and has its
   * own category.
   */
  private static VerificationCategory countDirection(
      Census census, Map<String, Long> entitiesByArchetype) {
    List<VerificationFinding> rows = new ArrayList<>();
    for (String archetype : ARCHETYPES) {
      long old = oldCount(census, archetype);
      long merged = entitiesByArchetype.getOrDefault(archetype, 0L);
      if (merged < old) {
        rows.add(
            new VerificationFinding(
                archetype,
                archetype,
                "the unified model holds FEWER rows of this archetype than the old table does,"
                    + " by " + (old - merged),
                Long.toString(old),
                Long.toString(merged)));
      }
    }
    return new VerificationCategory(
        "archetype-counts",
        VerificationKind.DISCREPANCY,
        "Per archetype, entity count >= old table count. Equality is NOT asserted: anything created"
            + " since the cutover has no old row, so a surplus is ordinary and only a shortfall is a"
            + " defect.",
        ARCHETYPES.size(),
        "archetypes, over " + census.oldRows() + " old rows and " + census.entities() + " entity rows",
        rows.size(),
        false,
        List.copyOf(rows));
  }

  private static final String MISSING_ENTITY_SQL =
      """
      with old_rows as (
          select id, 'EPIC' as archetype from Epic
          union all select id, 'TICKET' from Ticket
          union all select id, 'FEATURE' from Feature
          union all select id, 'TASK' from Task)
      select o.id, o.archetype, count(*) over () as total
        from old_rows o
       where not exists (select 1 from entity e where e.id = o.id)
         and not exists (select 1 from AuditEntry a
                          where a.entity_id = o.id and a.operation = 'DELETE')
       order by o.archetype, o.id
       limit ?
      """;

  private static VerificationCategory missingEntities(Connection db, Census census)
      throws SQLException {
    Rows found =
        read(
            db,
            MISSING_ENTITY_SQL,
            row ->
                new VerificationFinding(
                    row.getString("id"),
                    row.getString("archetype"),
                    "this old row has no entity row and no DELETE audit entry explaining its"
                        + " removal",
                    row.getString("id"),
                    null));
    return new VerificationCategory(
        "missing-entities",
        VerificationKind.DISCREPANCY,
        "Every row in the four old tables has an entity row under the same id. A row whose removal"
            + " through the unified model is recorded by a DELETE audit entry is exempt and is"
            + " reported under 'expected-deleted-since-the-cutover' instead.",
        census.oldRows(),
        "rows in Epic, Ticket, Feature and Task",
        found.total(),
        found.truncated(),
        found.sample());
  }

  private static final String DELETED_SINCE_SQL =
      """
      with old_rows as (
          select id, 'EPIC' as archetype from Epic
          union all select id, 'TICKET' from Ticket
          union all select id, 'FEATURE' from Feature
          union all select id, 'TASK' from Task)
      select o.id, o.archetype,
             (select max(a.changed_at)::text from AuditEntry a
               where a.entity_id = o.id and a.operation = 'DELETE') as deleted_at,
             count(*) over () as total
        from old_rows o
       where not exists (select 1 from entity e where e.id = o.id)
         and exists (select 1 from AuditEntry a
                      where a.entity_id = o.id and a.operation = 'DELETE')
       order by o.archetype, o.id
       limit ?
      """;

  private static VerificationCategory deletedSinceCutover(Connection db, Census census)
      throws SQLException {
    Rows found =
        read(
            db,
            DELETED_SINCE_SQL,
            row ->
                new VerificationFinding(
                    row.getString("id"),
                    row.getString("archetype"),
                    "removed through the unified model; the DELETE audit entry is the record of it",
                    row.getString("id"),
                    row.getString("deleted_at")));
    return new VerificationCategory(
        "expected-deleted-since-the-cutover",
        VerificationKind.EXPECTED,
        "Old rows with no entity row whose removal IS recorded — auditentry holds a DELETE for the"
            + " id. The doc's own wording: every old row resolves to an entity, or to the DELETE"
            + " audit row that says it was removed through the new model.",
        census.oldRows(),
        "rows in Epic, Ticket, Feature and Task",
        found.total(),
        found.truncated(),
        found.sample());
  }

  private static final String CREATED_SINCE_SQL =
      """
      select e.id, e.archetype, e.created_at::text as created_at, count(*) over () as total
        from entity e
        left join (select id from Epic
                   union all select id from Ticket
                   union all select id from Feature
                   union all select id from Task) o on o.id = e.id
       where o.id is null
       order by e.created_at, e.id
       limit ?
      """;

  private static VerificationCategory createdSinceCutover(Connection db, Census census)
      throws SQLException {
    Rows found =
        read(
            db,
            CREATED_SINCE_SQL,
            row ->
                new VerificationFinding(
                    row.getString("id"),
                    row.getString("archetype"),
                    "created through the unified model after the cutover; there is no old row to"
                        + " compare it against",
                    null,
                    row.getString("created_at")));
    return new VerificationCategory(
        "entities-created-since-the-cutover",
        VerificationKind.INFORMATIONAL,
        "Entity rows whose id is in none of the four old tables. This is NOT a defect and is the"
            + " reason the reverse assertion is never made — the old tables stopped being written"
            + " several commits ago.",
        census.entities(),
        "rows in entity",
        found.total(),
        found.truncated(),
        found.sample());
  }

  // ---- the property round-trip ----------------------------------------------------------------

  /**
   * Per archetype, the column-by-column comparison, as one {@code values} list per kind unpivoted
   * with a {@code cross join lateral} so the statement answers <em>one row per differing
   * property</em> rather than one row per differing entity. That shape is why the finding can carry
   * the property name and both values without a second query, and why adding a property to the
   * comparison is one line here rather than a new statement.
   *
   * <p>Every expression is cast to text, so a {@code varchar(512)} title and a {@code timestamptz}
   * marker compare the same way and the {@code values} list has one column type. {@code is distinct
   * from} is what makes a null on one side and a value on the other a difference rather than an
   * unknown.
   *
   * <p><b>{@code slug_scope} and {@code archetype} are compared too, though they are not columns of
   * the old tables.</b> They are both <em>derivations V10 performed</em> — the scope is the project
   * id for a root and the parent id for a child, and the archetype is which table the row came out
   * of — and a backfill that got either wrong would be invisible to every other check here.
   *
   * <p>{@code project_id} is compared against the value V10 derived it from, which for a feature is
   * its epic's and for a task is its feature's epic's. That is the one place the copy derives rather
   * than copies, so it is the one place a wrong join would be silent.
   */
  private static final String EPIC_PROPERTIES =
      """
      select 'EPIC' as archetype, o.id, p.property, p.old_value, p.new_value,
             o.updated_at as old_updated_at, e.updated_at as new_updated_at
        from Epic o
        join entity e on e.id = o.id
        cross join lateral (values
            ('archetype',     'EPIC'::text,                     e.archetype::text),
            ('title',         o.title::text,                    e.title::text),
            ('slug',          o.slug::text,                     e.slug::text),
            ('slug_scope',    o.project_id::text,               e.slug_scope::text),
            ('description',   o.description::text,              e.description::text),
            ('status',        o.status::text,                   e.status::text),
            ('project_id',    o.project_id::text,               e.project_id::text),
            ('superseded_by', o.superseded_by_epic_id::text,    e.superseded_by_entity_id::text)
        ) as p(property, old_value, new_value)
       where p.old_value is distinct from p.new_value
      """;

  private static final String TICKET_PROPERTIES =
      """
      select 'TICKET' as archetype, o.id, p.property, p.old_value, p.new_value,
             o.updated_at as old_updated_at, e.updated_at as new_updated_at
        from Ticket o
        join entity e on e.id = o.id
        cross join lateral (values
            ('archetype',   'TICKET'::text,        e.archetype::text),
            ('title',       o.title::text,         e.title::text),
            ('slug',        o.slug::text,          e.slug::text),
            ('slug_scope',  o.project_id::text,    e.slug_scope::text),
            ('description', o.description::text,   e.description::text),
            ('status',      o.status::text,        e.status::text),
            ('project_id',  o.project_id::text,    e.project_id::text),
            ('ticket_type', o.type::text,          e.ticket_type::text),
            ('impetus',     o.impetus::text,       e.impetus::text),
            ('assignee',    o.assignee::text,      e.assignee::text),
            ('created_by',  o.created_by::text,    e.created_by::text)
        ) as p(property, old_value, new_value)
       where p.old_value is distinct from p.new_value
         and not (p.property = 'slug'
                  and exists (select 1 from Epic ce
                               where ce.project_id = o.project_id and ce.slug = o.slug))
      """;

  private static final String FEATURE_PROPERTIES =
      """
      select 'FEATURE' as archetype, o.id, p.property, p.old_value, p.new_value,
             o.updated_at as old_updated_at, e.updated_at as new_updated_at
        from Feature o
        join entity e on e.id = o.id
        join Epic pe on pe.id = o.epic_id
        cross join lateral (values
            ('archetype',      'FEATURE'::text,                e.archetype::text),
            ('title',          o.title::text,                  e.title::text),
            ('slug',           o.slug::text,                   e.slug::text),
            ('slug_scope',     o.epic_id::text,                e.slug_scope::text),
            ('description',    o.description::text,            e.description::text),
            ('project_id',     pe.project_id::text,            e.project_id::text),
            ('implemented_at', o.implemented_on::text,         e.implemented_at::text),
            ('depends_on',     o.depends_on_feature_id::text,  e.depends_on_entity_id::text)
        ) as p(property, old_value, new_value)
       where p.old_value is distinct from p.new_value
      """;

  private static final String TASK_PROPERTIES =
      """
      select 'TASK' as archetype, o.id, p.property, p.old_value, p.new_value,
             o.updated_at as old_updated_at, e.updated_at as new_updated_at
        from Task o
        join entity e on e.id = o.id
        join Feature pf on pf.id = o.feature_id
        join Epic pe on pe.id = pf.epic_id
        cross join lateral (values
            ('archetype',      'TASK'::text,                 e.archetype::text),
            ('title',          o.title::text,                e.title::text),
            ('slug',           o.slug::text,                 e.slug::text),
            ('slug_scope',     o.feature_id::text,           e.slug_scope::text),
            ('description',    o.description::text,          e.description::text),
            ('project_id',     pe.project_id::text,          e.project_id::text),
            ('repository_id',  o.repository_id::text,        e.repository_id::text),
            ('implemented_at', o.implemented_at::text,       e.implemented_at::text),
            ('depends_on',     o.depends_on_task_id::text,   e.depends_on_entity_id::text)
        ) as p(property, old_value, new_value)
       where p.old_value is distinct from p.new_value
      """;

  private static String propertySql(boolean changedSinceCutover) {
    return "select archetype, id, property, old_value, new_value, count(*) over () as total from ("
        + EPIC_PROPERTIES
        + " union all "
        + TICKET_PROPERTIES
        + " union all "
        + FEATURE_PROPERTIES
        + " union all "
        + TASK_PROPERTIES
        + ") diff where new_updated_at "
        + (changedSinceCutover ? ">" : "<=")
        + " old_updated_at order by archetype, id, property limit ?";
  }

  private static VerificationCategory properties(
      Connection db, Census census, boolean changedSinceCutover) throws SQLException {
    Rows found =
        read(
            db,
            propertySql(changedSinceCutover),
            row ->
                new VerificationFinding(
                    row.getString("id"),
                    row.getString("archetype"),
                    row.getString("property"),
                    row.getString("old_value"),
                    row.getString("new_value")));
    if (changedSinceCutover) {
      return new VerificationCategory(
          "expected-changed-since-the-cutover",
          VerificationKind.EXPECTED,
          "Properties that differ on a row the unified model has written SINCE the copy —"
              + " entity.updated_at is strictly later than the frozen old row's. These are ordinary"
              + " edits, not backfill defects. Both values are listed anyway, because a genuine"
              + " defect on a row that was later edited lands here too and nothing else would show"
              + " it.",
          census.oldRowsWithEntity(),
          "old rows that have an entity row, each compared property by property",
          found.total(),
          found.truncated(),
          found.sample());
    }
    return new VerificationCategory(
        "property-round-trip",
        VerificationKind.DISCREPANCY,
        "Every property of every old row round-trips: title, slug, slug_scope, description, status,"
            + " project_id, the epic's superseded-by, the ticket's type/impetus/assignee/created_by,"
            + " the task's repository_id, and both merged markers (implemented_on/implemented_at,"
            + " depends_on). A ticket slug V10 de-collided is exempt from the slug comparison alone,"
            + " and a row written since the copy is exempt from all of them —"
            + " see the scope.",
        census.oldRowsWithEntity(),
        "old rows that have an entity row, each compared property by property",
        found.total(),
        found.truncated(),
        found.sample());
  }

  // ---- the de-collision -------------------------------------------------------------------------

  private static final String DE_COLLIDED_SQL =
      """
      select t.id, t.project_id, t.slug as old_slug, e.slug as new_slug, ce.id as epic_id,
             count(*) over () as total
        from Ticket t
        join entity e on e.id = t.id
        join Epic ce on ce.project_id = t.project_id and ce.slug = t.slug
       order by t.project_id, t.created_at, t.id
       limit ?
      """;

  private static VerificationCategory deCollidedSlugs(Connection db, Census census)
      throws SQLException {
    Rows found =
        read(
            db,
            DE_COLLIDED_SQL,
            row ->
                new VerificationFinding(
                    row.getString("id"),
                    "TICKET",
                    "slug collided with epic " + row.getString("epic_id") + " in project "
                        + row.getString("project_id")
                        + "; V10 re-slugged the entity row and the epic kept its slug. A branch cut"
                        + " at ticket/" + row.getString("old_slug") + " does NOT move.",
                    row.getString("old_slug"),
                    row.getString("new_slug")));
    return new VerificationCategory(
        "expected-de-collided-slugs",
        VerificationKind.EXPECTED,
        "Tickets whose slug equals an epic's slug in the same project, read off the OLD tables —"
            + " which is exactly how V10 chose the rows it re-slugged. Their entity slug differs"
            + " from their Ticket slug ON PURPOSE. Reported, never suppressed: the difference means"
            + " a work branch no longer derives.",
        census.tickets(),
        "rows in Ticket",
        found.total(),
        found.truncated(),
        found.sample());
  }

  // ---- the memberships ---------------------------------------------------------------------------

  private static final String MEMBERSHIP_PARENT_SQL =
      """
      with expected as (
          select f.id as child_id, f.epic_id as parent_id, f.updated_at, 'FEATURE' as archetype
            from Feature f
          union all
          select t.id, t.feature_id, t.updated_at, 'TASK' from Task t)
      select x.archetype, x.child_id, x.parent_id as old_parent, m.parent_id as new_parent,
             count(*) over () as total
        from expected x
        join entity ce on ce.id = x.child_id
        left join entity_membership m on m.child_id = x.child_id
       where m.parent_id is distinct from x.parent_id
         and WRITTEN_SINCE
       order by x.archetype, x.child_id
       limit ?
      """;

  /**
   * A MISSING edge ({@code m.updated_at} null) belongs in the discrepancy arm and never in the
   * expected one, which is the whole reason these two predicates are spelled out rather than derived
   * from one comparison operator: an edge that does not exist cannot have been written since the
   * copy, and a null comparison would have quietly dropped it out of both arms.
   */
  private static VerificationCategory membershipParents(
      Connection db, Census census, boolean changedSinceCutover) throws SQLException {
    String sql =
        MEMBERSHIP_PARENT_SQL.replace(
            "WRITTEN_SINCE",
            changedSinceCutover
                ? "m.updated_at > x.updated_at"
                : "(m.updated_at is null or m.updated_at <= x.updated_at)");
    Rows found =
        read(
            db,
            sql,
            row ->
                new VerificationFinding(
                    row.getString("child_id"),
                    row.getString("archetype"),
                    row.getString("new_parent") == null
                        ? "has no membership edge at all; the old row named a parent"
                        : "membership names a different parent than the old row did",
                    row.getString("old_parent"),
                    row.getString("new_parent")));
    if (changedSinceCutover) {
      return new VerificationCategory(
          "expected-reparented-since-the-cutover",
          VerificationKind.EXPECTED,
          "Edges whose parent differs from the old row's and which the unified model has WRITTEN"
              + " since the copy — the membership row's updated_at is later than the child's frozen"
              + " one. A reparent is an update of the one edge, so it stamps that column; the copy"
              + " wrote the child's own timestamps onto it.",
          census.descendants(),
          "feature.epic_id and task.feature_id relations whose child still has an entity row",
          found.total(),
          found.truncated(),
          found.sample());
    }
    return new VerificationCategory(
        "membership-parents",
        VerificationKind.DISCREPANCY,
        "Every feature.epic_id and task.feature_id relation has an entity_membership edge naming the"
            + " same parent. A child whose entity row is gone is out of scope here (see"
            + " missing-entities); an edge the model has written since the copy is reported under"
            + " 'expected-reparented-since-the-cutover'.",
        census.descendants(),
        "feature.epic_id and task.feature_id relations whose child still has an entity row",
        found.total(),
        found.truncated(),
        found.sample());
  }

  /**
   * Ordering is its own category and is never folded into the property check, because it is the
   * easiest thing in the whole backfill to lose silently: every position is a plausible integer and
   * a wrong one draws a listing in a wrong order that nothing else notices.
   *
   * <p>It compares <b>relative order, not position</b>. Both ranks are computed over exactly the
   * same set — the old children that still hang under the same parent — one by {@code (created_at,
   * id)}, which is V10's own total order and {@code FeatureRepository.listByEpic}'s sort, the other
   * by the edge's position. A renumber (a deleted sibling, {@code closeGapAfter}) moves every
   * absolute position and no relative one; an append since the cutover adds rows that are not in the
   * set at all. A genuine permutation is what survives, and it is what this catches.
   */
  private static final String MEMBERSHIP_ORDER_SQL =
      """
      with old_child as (
          select f.id as child_id, f.epic_id as parent_id, f.created_at, 'FEATURE' as archetype
            from Feature f
          union all
          select t.id, t.feature_id, t.created_at, 'TASK' from Task t),
      paired as (
          select o.archetype, o.child_id, o.parent_id, o.created_at,
                 m.position as edge_position
            from old_child o
            join entity_membership m
              on m.child_id = o.child_id and m.parent_id = o.parent_id),
      ranked as (
          select p.archetype, p.child_id, p.parent_id, p.edge_position,
                 row_number() over (partition by p.parent_id
                                    order by p.created_at, p.child_id) as old_rank,
                 row_number() over (partition by p.parent_id
                                    order by p.edge_position, p.child_id) as new_rank
            from paired p)
      select archetype, child_id, parent_id, edge_position, old_rank, new_rank,
             count(*) over () as total
        from ranked
       where old_rank <> new_rank
       order by parent_id, old_rank
       limit ?
      """;

  private static VerificationCategory membershipOrder(Connection db, Census census)
      throws SQLException {
    Rows found =
        read(
            db,
            MEMBERSHIP_ORDER_SQL,
            row ->
                new VerificationFinding(
                    row.getString("child_id"),
                    row.getString("archetype"),
                    "sibling order under " + row.getString("parent_id")
                        + " disagrees with (created_at, id); the edge's position is "
                        + row.getString("edge_position"),
                    "rank " + row.getString("old_rank"),
                    "rank " + row.getString("new_rank")));
    return new VerificationCategory(
        "membership-order",
        VerificationKind.DISCREPANCY,
        "Siblings appear under their parent in the same RELATIVE order the old tables imply —"
            + " (created_at, id), which is V10's own total order and the sort both legacy listings"
            + " applied. Absolute positions are deliberately not compared: closeGapAfter renumbers"
            + " with a bulk update and a renumber is not a defect.",
        census.pairedEdges(),
        "edges whose child and parent still match the old relation",
        found.total(),
        found.truncated(),
        found.sample());
  }

  private static final String ORPHANED_MEMBERSHIP_SQL =
      """
      select m.id, m.parent_id, m.child_id,
             case when p.id is null and c.id is null then 'both ends'
                  when p.id is null then 'parent'
                  else 'child' end as missing_end,
             count(*) over () as total
        from entity_membership m
        left join entity p on p.id = m.parent_id
        left join entity c on c.id = m.child_id
       where p.id is null or c.id is null
       order by m.id
       limit ?
      """;

  private static VerificationCategory orphanedMemberships(Connection db, Census census)
      throws SQLException {
    Rows found =
        read(
            db,
            ORPHANED_MEMBERSHIP_SQL,
            row ->
                new VerificationFinding(
                    row.getString("id"),
                    null,
                    "the edge's " + row.getString("missing_end") + " names no entity row",
                    row.getString("parent_id"),
                    row.getString("child_id")));
    return new VerificationCategory(
        "orphaned-memberships",
        VerificationKind.DISCREPANCY,
        "No entity_membership names a parent or a child that does not exist. The schema normally"
            + " forbids this outright — V9's two foreign keys, still in force after V12 — and the"
            + " check runs anyway, because this door also runs against databases whose constraints"
            + " were repointed or restored by hand.",
        census.memberships(),
        "edges in entity_membership",
        found.total(),
        found.truncated(),
        found.sample());
  }

  /**
   * <b>The rule, and where it comes from.</b> An entity may stand alone exactly when its archetype
   * declares it may — {@code ArchetypeSpec.mayBeRoot}, which is {@code EPIC} and {@code TICKET}
   * today and is a declared flag rather than something derived from depth, precisely so the campaign
   * work can put a kind above the epics without revoking epic-as-root.
   *
   * <p>The SQL spells the vocabulary and the registry spells the rule — V9's own split, applied a
   * third time. Reaching into {@code control/Archetypes} from a statement string would put the rule
   * in two places without making either of them the authority; naming the two words here, in a check
   * that is deleted by V13 anyway, keeps the registry the one thing a live path consults.
   */
  private static final String ROOTLESS_SQL =
      """
      select e.id, e.archetype, e.project_id, count(*) over () as total
        from entity e
        left join entity_membership m on m.child_id = e.id
       where e.archetype not in ('EPIC', 'TICKET')
         and m.child_id is null
       order by e.archetype, e.id
       limit ?
      """;

  private static VerificationCategory rootlessEntities(Connection db, Census census)
      throws SQLException {
    Rows found =
        read(
            db,
            ROOTLESS_SQL,
            row ->
                new VerificationFinding(
                    row.getString("id"),
                    row.getString("archetype"),
                    "a non-root archetype with no membership edge: it hangs under nothing, so it is"
                        + " in no tree and no listing draws it",
                    null,
                    row.getString("project_id")));
    return new VerificationCategory(
        "rootless-entities",
        VerificationKind.DISCREPANCY,
        "Every entity whose archetype may NOT be a root — FEATURE and TASK, which is"
            + " ArchetypeSpec.mayBeRoot's answer spelled as a vocabulary here — has a membership"
            + " edge. EPIC and TICKET are roots and are expected to have none.",
        census.nonRootEntities(),
        "entity rows whose archetype is neither EPIC nor TICKET",
        found.total(),
        found.truncated(),
        found.sample());
  }

  // ---- outward referential integrity --------------------------------------------------------------

  private static final String DANGLING_OWNER_SQL =
      """
      select src, ref_id, owner_row, count(*) over () as total from (
          select 'dossier_page.epic_id' as src, dp.epic_id as ref_id, dp.id as owner_row
            from dossier_page dp
           where dp.epic_id is not null
             and not exists (select 1 from entity e where e.id = dp.epic_id)
          union all
          select 'dossier_page.ticket_id', dp.ticket_id, dp.id
            from dossier_page dp
           where dp.ticket_id is not null
             and not exists (select 1 from entity e where e.id = dp.ticket_id)
          union all
          select 'dossier_asset.epic_id', da.epic_id, da.id
            from dossier_asset da
           where not exists (select 1 from entity e where e.id = da.epic_id)
          union all
          select 'ticketcomment.ticket_id', tc.ticket_id, tc.id
            from TicketComment tc
           where not exists (select 1 from entity e where e.id = tc.ticket_id)
      ) dangling
       order by src, owner_row
       limit ?
      """;

  private static VerificationCategory danglingOwnerReferences(Connection db, Census census)
      throws SQLException {
    Rows found =
        read(
            db,
            DANGLING_OWNER_SQL,
            row ->
                new VerificationFinding(
                    row.getString("owner_row"),
                    null,
                    row.getString("src") + " names an id no entity row holds",
                    row.getString("ref_id"),
                    null));
    return new VerificationCategory(
        "dangling-owner-references",
        VerificationKind.DISCREPANCY,
        "dossier_page.epic_id, dossier_page.ticket_id, dossier_asset.epic_id and"
            + " ticketcomment.ticket_id all resolve in entity. V12 repointed these four foreign keys"
            + " at entity(id), so the schema now enforces the very direction this asserts — the"
            + " check stands because the door also runs where a constraint was dropped or restored"
            + " by hand, and because a passing check is the evidence, not the constraint's existence.",
        census.ownerReferences(),
        "rows in dossier_page, dossier_asset and ticketcomment",
        found.total(),
        found.truncated(),
        found.sample());
  }

  /**
   * <b>The audit log is deliberately not foreign-keyed back and must not be checked as though it
   * were.</b> V1 says it outright: audit rows survive the deletion of what they describe, which is
   * most of what an append-only log is for. So a dangling {@code entity_id} is the ordinary state of
   * every row this platform has ever deleted, and asserting "every audit entry resolves" would
   * report years of correct history as broken.
   *
   * <p>What is a defect is narrower and is the only thing asked: an audit entry naming an id that
   * one of the old tables <em>still holds</em> while {@code entity} does not, with no DELETE entry
   * explaining it. That is a row the migration lost, seen from the log's side. Both the {@code
   * entity_id} and the subtree key {@code epic_id} are checked, because the second is what "the
   * whole history of this thing" is indexed by and a broken one silently empties a history screen.
   */
  private static final String DANGLING_AUDIT_SQL =
      """
      with old_ids as (
          select id from Epic
          union all select id from Ticket
          union all select id from Feature
          union all select id from Task)
      select src, id, entity_type, ref_id, count(*) over () as total from (
          select 'auditentry.entity_id' as src, a.id, a.entity_type, a.entity_id as ref_id
            from AuditEntry a
           where exists (select 1 from old_ids o where o.id = a.entity_id)
             and not exists (select 1 from entity e where e.id = a.entity_id)
             and not exists (select 1 from AuditEntry d
                              where d.entity_id = a.entity_id and d.operation = 'DELETE')
          union all
          select 'auditentry.epic_id', a.id, a.entity_type, a.epic_id
            from AuditEntry a
           where exists (select 1 from old_ids o where o.id = a.epic_id)
             and not exists (select 1 from entity e where e.id = a.epic_id)
             and not exists (select 1 from AuditEntry d
                              where d.entity_id = a.epic_id and d.operation = 'DELETE')
      ) dangling
       order by src, id
       limit ?
      """;

  private static VerificationCategory danglingAuditReferences(Connection db, Census census)
      throws SQLException {
    Rows found =
        read(
            db,
            DANGLING_AUDIT_SQL,
            row ->
                new VerificationFinding(
                    row.getString("id"),
                    row.getString("entity_type"),
                    row.getString("src")
                        + " names a row an old table still holds and entity does not",
                    row.getString("ref_id"),
                    null));
    return new VerificationCategory(
        "dangling-audit-references",
        VerificationKind.DISCREPANCY,
        "Audit entries whose entity_id or subtree key names a row that one of the old tables STILL"
            + " HOLDS while entity does not, and with no DELETE entry to explain it. The broader"
            + " question — does every audit entry resolve — is deliberately not asked: the log is"
            + " not foreign-keyed back precisely so it outlives the rows it describes (V1).",
        census.auditEntries(),
        "rows in auditentry, each checked on both its entity_id and its subtree key",
        found.total(),
        found.truncated(),
        found.sample());
  }

  // ---- work branches -------------------------------------------------------------------------------

  /**
   * <b>Nothing in this database stores a branch name.</b> {@code control/WorkBranches} derives it
   * from slugs and ancestry — {@code epic/<e>}, {@code ticket/<t>}, {@code feature/<e>/<f>}, {@code
   * task/<e>/<f>/<t>} — so "the work branches still resolve" can only mean one thing here: the name
   * the old tables imply and the name the unified model implies are the same string. That is a real
   * check and not a restatement of the slug comparison, because it folds in the ancestry as well:
   * a feature whose slug is intact but whose membership moved names a different branch, and the
   * branch is what an agent's workspace is actually standing on.
   *
   * <p>The de-collided tickets are excluded here, and this is the one category where the exclusion
   * is not merely bookkeeping: V10 states that their branch <b>deliberately does not move</b>, so
   * reporting it as a broken branch would contradict the migration's own decision.
   *
   * <p><b>The "written since" discriminator is the row's own timestamp and its own edge's, and
   * nothing further up.</b> A slug is {@code @Column(updatable = false)} on every archetype, so the
   * only way a derived name can move through the model at all is a reparent — which stamps exactly
   * that edge. Reaching further up would be wrong rather than thorough: V10 gave an ancestor's row
   * and edge that <em>ancestor's</em> frozen timestamps, which may legitimately be later than this
   * row's with nothing having been written since, and comparing against them would excuse a real
   * defect. The cost of stopping here is stated rather than hidden: a task whose <em>feature</em>
   * was reparented since the copy is listed, because its branch really did move — and the paired
   * entry under {@code expected-reparented-since-the-cutover} is what explains it to the reader.
   */
  private static final String WORK_BRANCH_SQL =
      """
      with old_branch as (
          select e.id, 'EPIC' as archetype, 'epic/' || e.slug as branch, e.updated_at from Epic e
          union all
          select t.id, 'TICKET', 'ticket/' || t.slug, t.updated_at from Ticket t
          union all
          select f.id, 'FEATURE', 'feature/' || pe.slug || '/' || f.slug, f.updated_at
            from Feature f join Epic pe on pe.id = f.epic_id
          union all
          select tk.id, 'TASK', 'task/' || pe.slug || '/' || pf.slug || '/' || tk.slug, tk.updated_at
            from Task tk
            join Feature pf on pf.id = tk.feature_id
            join Epic pe on pe.id = pf.epic_id),
      new_branch as (
          select e.id,
                 case e.archetype
                     when 'EPIC' then 'epic/' || e.slug
                     when 'TICKET' then 'ticket/' || e.slug
                     when 'FEATURE' then 'feature/' || pe.slug || '/' || e.slug
                     when 'TASK' then 'task/' || ge.slug || '/' || pe.slug || '/' || e.slug
                 end as branch,
                 greatest(e.updated_at, coalesce(m.updated_at, e.updated_at)) as written_at
            from entity e
            left join entity_membership m on m.child_id = e.id
            left join entity pe on pe.id = m.parent_id
            left join entity_membership gm on gm.child_id = pe.id
            left join entity ge on ge.id = gm.parent_id)
      select o.id, o.archetype, o.branch as old_branch, n.branch as new_branch,
             count(*) over () as total
        from old_branch o
        join new_branch n on n.id = o.id
       where n.branch is distinct from o.branch
         and n.written_at <= o.updated_at
         and not (o.archetype = 'TICKET'
                  and exists (select 1 from Ticket ct join Epic ce
                                on ce.project_id = ct.project_id and ce.slug = ct.slug
                               where ct.id = o.id))
       order by o.archetype, o.id
       limit ?
      """;

  private static VerificationCategory workBranches(Connection db, Census census)
      throws SQLException {
    Rows found =
        read(
            db,
            WORK_BRANCH_SQL,
            row ->
                new VerificationFinding(
                    row.getString("id"),
                    row.getString("archetype"),
                    row.getString("new_branch") == null
                        ? "the unified model cannot derive a branch for this row at all — its"
                            + " ancestry is incomplete"
                        : "the branch this row names is not the one the old tables imply",
                    row.getString("old_branch"),
                    row.getString("new_branch")));
    return new VerificationCategory(
        "work-branches",
        VerificationKind.DISCREPANCY,
        "The branch name derived from the old tables equals the one derived from the unified model,"
            + " for all four archetypes — slug AND ancestry, which is what makes this more than the"
            + " slug check. Tickets V10 de-collided are exempt: V10 states their branch deliberately"
            + " does not move. Rows written since the copy are exempt on the usual terms.",
        census.oldRows(),
        "rows in Epic, Ticket, Feature and Task, each as a derived branch name",
        found.total(),
        found.truncated(),
        found.sample());
  }

  // ---- the plumbing ---------------------------------------------------------------------------------

  /** What one finding query answered: the full count, and the capped, ordered head of it. */
  private record Rows(long total, boolean truncated, List<VerificationFinding> sample) {}

  /** Maps the current row of a finding query. Checked, because every caller is reading JDBC. */
  @FunctionalInterface
  private interface FindingMapper {
    VerificationFinding map(ResultSet row) throws SQLException;
  }

  /**
   * Runs one finding query and reads both answers out of it.
   *
   * <p>Every such query ends {@code count(*) over () as total ... limit ?}, which is the whole
   * reason there is one statement per category instead of two: the window function is evaluated
   * after the filter and before the limit, so the first row carries the true total even when the
   * list that follows is capped. A separate {@code count(*)} query would be a second statement at a
   * second instant, and the two could disagree on a live database.
   */
  private static Rows read(Connection db, String sql, FindingMapper mapper) throws SQLException {
    List<VerificationFinding> sample = new ArrayList<>();
    long total = 0;
    try (PreparedStatement query = db.prepareStatement(sql)) {
      query.setInt(1, MAX_FINDINGS_PER_CATEGORY);
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          total = rows.getLong("total");
          sample.add(mapper.map(rows));
        }
      }
    }
    return new Rows(total, total > sample.size(), List.copyOf(sample));
  }
}
