-- THE PER-PROJECT NUMERIC ID. Every entity gains a `long` that is unique within its project and
-- never reused, written by hand in its qualified form `<project>-<n>` — `qits-1337`.
--
-- WHY A NUMBER WHEN THERE IS ALREADY A UUID AND A SLUG. Because the id has to survive where neither
-- does. A uuid does not fit in a commit subject. A slug is truncated at 40 characters and is minted
-- from a title, so it is neither complete nor — across a retitle that happens before the first cut
-- branch — reliably the thing a person remembers. The number is short enough to write by hand,
-- stable for the life of the row, and unambiguous once qualified by its project. That is what turns
-- "which subject does this change belong to" from a guess into a recorded fact.
--
-- THE UUID IS STILL THE PRIMARY KEY, and nothing here touches it. This is a SECOND identifier, not a
-- replacement: every dossier page, audit entry, branch name, workspace and URL on the platform names
-- `entity.id`, and V10's header argues at length why that id space may not move. A re-key would
-- break all of them at once for the sake of a prettier column.
--
-- PER PROJECT AND NOT GLOBAL, so the numbers stay small enough to read and the qualified form
-- carries its own scope. `(project_id, number)` is the uniqueness, and it is enforced HERE rather
-- than in a service: the unified table holds every archetype, so a ticket and a feature in the same
-- project never share a number — THE ID NAMES A NODE, NOT A TICKET.
--
--   V9   the two tables, empty
--   V10  the copy, ids UNCHANGED
--   V11  this file — the numeric id, its constraint, its backfill and its allocator's counter
--   V12  the four outward foreign keys repointed at entity(id)
--
-- ---------------------------------------------------------------------------------------------
-- THE ALLOCATOR IS A COUNTER ROW, AND IT IS BUMPED IN A TRANSACTION OF ITS OWN
-- ---------------------------------------------------------------------------------------------
-- Two properties are wanted and they pull against each other:
--
--   * TWO SIMULTANEOUS CREATES CANNOT COLLIDE.
--   * A ROLLED-BACK CREATE DOES NOT MAKE ITS NUMBER REAPPEAR.
--
-- A `max(number) + 1` read-then-write gives NEITHER: two creates read the same maximum and write the
-- same number, and a rollback hands the number straight back. A postgres SEQUENCE gives both, because
-- `nextval` is non-transactional — but a sequence PER PROJECT means one `create sequence` per
-- project, DDL that this platform's deployer does not run and that nothing in this lineage could
-- account for afterwards. So the third answer is taken: a counter ROW, bumped by `update ... set
-- next_number = next_number + n` — which takes a row lock and is atomic under READ COMMITTED, so the
-- first property holds — inside a transaction OF ITS OWN that commits before the create's
-- transaction does anything else, which is what gives the second. A create that then rolls back
-- leaves a gap, exactly as a sequence would.
--
-- GAPS ARE FINE. The id is a name, not a count: nothing sums it, nothing pages by it and nothing
-- reads a missing number as a missing row.
--
-- WHAT IT COSTS, STATED RATHER THAN HIDDEN. One extra short transaction per created row — a second
-- pooled connection for the length of two statements. In exchange the counter's row lock is held for
-- that bump alone instead of for the whole create, so two people planning in one project do not
-- serialise on each other's audit writes and slug reads. `EntityNumbers` is the one place that
-- transaction is opened.
--
-- ---------------------------------------------------------------------------------------------
-- THE BACKFILL IS ORDERED BY (created_at, id), WHICH IS V10'S OWN TOTAL ORDER
-- ---------------------------------------------------------------------------------------------
-- By the time this file runs, V10 has copied every Epic, Ticket, Feature and Task into `entity`, so
-- `entity` already holds the whole estate and one pass over it is the whole backfill.
--
-- `row_number() over (partition by project_id order by created_at, id)` is the same total order V10
-- derived its positions from, and it is total rather than merely plausible: `created_at` alone ties
-- (V10's own fixtures tie on purpose), and `id` is the primary key, so the pair can never. The
-- alternative — numbering by the order rows come back — is a fact about physical layout that changes
-- with a VACUUM and differs between the test database and the live one, and it would produce a
-- plausible-looking sequence that silently disagreed with itself between two runs.
--
-- It is `partition by project_id` and NOT by archetype: the number names a node in a project's plan,
-- so an epic, its features, its tasks and the project's tickets all draw from one run of integers.

-- ---------------------------------------------------------------------------------------------
-- 1. The column, nullable for the length of the backfill and not one statement longer
-- ---------------------------------------------------------------------------------------------
alter table entity
    add column number bigint;

comment on column entity.number is 'Per-project, never reused, written by hand as <project>-<n>. Allocated by control/EntityNumbers.';

-- ---------------------------------------------------------------------------------------------
-- 2. The backfill
-- ---------------------------------------------------------------------------------------------
update entity e
   set number = ordered.n
  from (select id,
               row_number() over (partition by project_id order by created_at, id) as n
          from entity) ordered
 where ordered.id = e.id;

alter table entity
    alter column number set not null;

-- ---------------------------------------------------------------------------------------------
-- 3. The uniqueness, in the database
-- ---------------------------------------------------------------------------------------------
-- Named, so the next change to it is an ordinary drop rather than a round of guessing at what
-- postgres derived — V12's header makes the same point about the four keys it repoints.
alter table entity
    add constraint uq_entity_project_number unique (project_id, number);

-- ---------------------------------------------------------------------------------------------
-- 4. The allocator's counter, seeded ABOVE the highest number this file just wrote
-- ---------------------------------------------------------------------------------------------
-- `next_number` is the number the NEXT create in that project takes, so the seed is `max + 1` and a
-- project with no entity row has no counter row at all — the allocator inserts it at 1 on first use,
-- which is also what makes this table need no maintenance when a project is created or deleted.
--
-- Seeding it here rather than letting the allocator derive a floor is the whole of "the allocator
-- starts above the highest backfilled value": a derivation would be a `max()` read, which is the
-- read-then-write this design exists not to do.
create table entity_number_sequence
(
    project_id  varchar(255) not null,
    next_number bigint       not null,
    primary key (project_id)
);

comment on table entity_number_sequence is 'One counter row per project: the next entity number. Bumped in its own transaction so a rolled-back create leaves a gap rather than reusing the number.';

insert into entity_number_sequence (project_id, next_number)
select project_id, max(number) + 1
  from entity
 group by project_id;
