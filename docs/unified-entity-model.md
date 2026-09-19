# The unified entity model

Epic, Ticket, Feature and Task become **one table discriminated by an archetype**, with the
parent/child relation as a row of its own. This file is the contract the rest of that work builds
against: what shipped in V9, which old field became which column, what each archetype declares, and
every decision that had to be made along the way.

**Nothing reads the new model yet.** V9 is additive in the strongest sense — no existing table,
column, entity, service or controller is touched, and no row is copied. The four old tables are
still what answers every route.

## Reserved migration versions

| version | what it is | state |
| --- | --- | --- |
| `V9__entity_membership.sql` | the two tables, empty | **shipped** |
| `V10` | the backfill — every Epic/Ticket/Feature/Task row copied in, **ids unchanged** | reserved |
| `V11` | the id settlement (the numeric id the merged model wants) | reserved |
| `V12+` | the drop of the four old tables, once nothing reads them | reserved |

The ids are the **same id space**: `entity.id` is `varchar(255)` exactly as `epic.id` is, because
V10 copies each old row in under the id it already has. Every dossier page, audit entry, branch
name, workspace and URL on the platform names one of those strings, so a merge that re-keyed the
rows would break all of them at once for the sake of a tidier column.

## The DDL as shipped

```sql
create table entity
(
    id varchar(255) not null,
    causation_id uuid,
    project_id varchar(255) not null,          -- no cross-DB FK, indexed String
    archetype varchar(32) not null,            -- ck_entity_archetype
    title varchar(512) not null,
    slug varchar(255) not null,
    slug_scope varchar(255) not null,          -- uq_entity_slug_scope_slug, with slug
    description text,
    status varchar(32),                        -- ck_entity_status, the UNION of both enums
    ticket_type varchar(32),
    impetus text,
    assignee varchar(255),
    created_by varchar(255),
    superseded_by_entity_id varchar(255),      -- self-FK, on delete set null
    repository_id varchar(255),                -- no cross-DB FK, indexed String
    implemented_at timestamp(6) with time zone,
    depends_on_entity_id varchar(255),         -- self-FK, on delete set null. NOT nesting.
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id)
);
create index idx_entity_project_id     on entity (project_id);
create index idx_entity_project_status on entity (project_id, status);
create index idx_entity_repository_id  on entity (repository_id);

create table entity_membership
(
    id varchar(255) not null,
    causation_id uuid,
    parent_id varchar(255) not null,           -- fk -> entity(id) on delete cascade
    child_id varchar(255) not null,            -- fk -> entity(id) on delete cascade
    position integer not null,                 -- dense, zero-based
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uq_entity_membership_one_parent_per_child unique (child_id)
);
create index idx_entity_membership_parent_position on entity_membership (parent_id, position);
```

`ck_entity_status` spells nine words: `REFINING, IMPLEMENTATION, IMPLEMENTED, SUPERSEDED,
ABANDONED, REPORTED, REFINED, VERIFIED, DONE` — the union of `EpicStatus` and `TicketStatus`, which
overlap on `IMPLEMENTED`. A check constraint has no way to say "these five when the archetype is
EPIC" without becoming a second place the vocabulary is written down, so **the constraint spells the
vocabulary and `control/Archetypes` spells the rule**. That split is V1's, applied again.

## The column-per-property map

| old table.column | new column | note |
| --- | --- | --- |
| `epic.id`, `ticket.id`, `feature.id`, `task.id` | `entity.id` | unchanged values; one id space |
| `epic.project_id`, `ticket.project_id` | `entity.project_id` | now on **every** row, descendants included |
| *(derived by walking up)* | `entity.project_id` | a feature/task no longer reaches its project by a join |
| — | `entity.archetype` | new: the discriminator |
| `*.title` | `entity.title` | |
| `*.slug` | `entity.slug` | still minted at create, never re-derived |
| *(implied by the table)* | `entity.slug_scope` | new: see below |
| `*.description` | `entity.description` | |
| **`epic.status` + `ticket.status`** | **`entity.status`** | **merged.** One column, two vocabularies, a `String` in Java |
| `ticket.type` | `entity.ticket_type` | renamed: `type` in a four-archetype row reads as the archetype |
| `ticket.impetus` | `entity.impetus` | |
| `ticket.assignee` | `entity.assignee` | |
| `ticket.created_by` | `entity.created_by` | still stamped, never client-supplied |
| `epic.superseded_by_epic_id` | `entity.superseded_by_entity_id` | self-FK within the merged table |
| `task.repository_id` | `entity.repository_id` | |
| **`feature.implemented_on` + `task.implemented_at`** | **`entity.implemented_at`** | **merged.** One fact that had two names |
| **`feature.depends_on_feature_id` + `task.depends_on_task_id`** | **`entity.depends_on_entity_id`** | **merged.** A sibling ordering edge, **not** nesting |
| `feature.epic_id`, `task.feature_id` | `entity_membership` row | the relation becomes a row |
| `*.causation_id`, `*.created_at`, `*.updated_at` | same | unchanged |

### The three merges, argued

- **`implemented_on` / `implemented_at` → `implemented_at`.** These were never two facts; both mean
  "this is done, as of then", and the two names are an accident of the two tables having been
  written apart. `implemented_at` wins because a timestamp answers "at", and because
  `EpicLifecycle` already speaks of "the implemented markers" in the plural as one rule.
- **`depends_on_feature_id` / `depends_on_task_id` → `depends_on_entity_id`.** One property spelled
  twice because it lived in two tables. **It is not nesting and must never be validated as
  nesting**: a dependency says "do that one first", a membership says "this one is part of that
  one". They have unrelated cycle rules — a dependency cycle is a real error the services already
  check for, a membership cycle cannot occur at all under the depth rule.
- **`EpicStatus` / `TicketStatus` → `status`.** Stored as the enum's own `name()`. In Java it is a
  `String` and **deliberately not a merged nine-word enum**: that would give `IMPLEMENTED` a single
  identity across two lifecycles that mean different things by it, and would leave every existing
  switch over the two real enums with a third vocabulary to translate from.

## The archetype table

Declared in `epics/control/Archetypes.java`, as data. Nothing else re-decides any of it.

| archetype | depth | may be a root | requires | permits (beyond required) | legal statuses |
| --- | --- | --- | --- | --- | --- |
| `EPIC` | 0 | yes | `TITLE` | `SLUG`, `DESCRIPTION`, `STATUS`, `SUPERSEDED_BY` | the five `EpicStatus` words |
| `TICKET` | 0 | yes | `TITLE`, `TICKET_TYPE`, `IMPETUS`, `STATUS` | `SLUG`, `DESCRIPTION`, `ASSIGNEE`, `CREATED_BY` | the five `TicketStatus` words |
| `FEATURE` | 1 | no | `TITLE` | `SLUG`, `DESCRIPTION`, `DEPENDS_ON`, `IMPLEMENTED_AT` | none |
| `TASK` | 2 | no | `TITLE`, `REPOSITORY_ID` | `SLUG`, `DESCRIPTION`, `DEPENDS_ON`, `IMPLEMENTED_AT` | none |

`required` is a subset of `permitted` for every archetype, and a kind permits `STATUS` exactly when
it declares status words. Both invariants are asserted at **class-initialisation** time, so a bad
declaration is a refusal to start naming the archetype rather than a rule that can never fire.

**What a check answers.** `Archetypes.validate(EntityState)` returns **every** violation, never the
first, in vocabulary order. Three questions, all three always asked: every required property
present; no property the archetype has no slot for; and the status word, when there is one, in this
archetype's lifecycle. **A foreign property is refused, not silently dropped** — a value arriving on
a kind that cannot hold it means the caller and the model disagree about what is being written, and
dropping it would lose the value and the disagreement together.

Violations are structured (`ArchetypeViolation`: archetype, property, reason, detail) so a caller
can put the complaint on the offending field, with `message()` for a log line or a plain error body.

## The nesting rule

`epics/control/Nesting.java` — one place, sitting on the registry, naming no archetype anywhere.

> **A membership is legal when the parent's depth is strictly less than the child's.**

- **Levels may be skipped.** An epic holding a task directly is legal (0 < 2). The rule is about
  containment, not about a fixed number of rungs; forbidding this would mean inventing a feature
  nobody wanted every time an epic needs one concrete piece of work.
- **Equal or above is refused**: epic-under-epic, ticket-under-ticket, task-under-task, and
  ticket-under-epic — the last because the two roots are declared at the same depth, which is V4's
  "nothing joins the two tables and nothing should" expressed as a rule instead of as prose.
- **A root has no membership**, and whether a kind may be one is declared (see below).
- **Cycle safety.** A graph legal by depth cannot cycle — every edge strictly increases depth, so a
  walk upwards terminates — and it is asserted anyway, as a bounded walk over facts already in hand.

### It is judged over a post-state

`Nesting.check(Collection<EntityFact>, EntityFacts)` — one call, every violation.

An `EntityFact` is `(id, archetype, parentId)`; a null parent means a root and never "unchanged",
because a partial fact would make detaching an entity impossible to express. Stated facts override
the store; **everything else is read from it, in both directions**:

- **upwards**, because a child needs its untouched parent's archetype;
- **downwards**, because re-archetyping a row re-judges every child it already has.

`EntityFacts` is the seam for that reading — bulk by shape, with an in-memory implementation
(`EntityFacts.of`) so the rules are unit-testable with no database and no Quarkus application.

**Why a post-state at all.** The operation this exists for is a promotion: a feature becomes an epic
and stops hanging under the one it was part of. Neither half is legal alone — re-archetype first and
there is an epic under an epic; reparent first and there is a feature at the root — and only the two
together are a legal tree. A row-by-row validator refuses an operation that is correct, whichever
order it is handed. `NestingTest.aPromotionIsLegalOnlyWhenItsHalvesAreAppliedTogether` asserts all
three of those.

## The `slug_scope` rule

`slug_scope` holds the **project id** for a row with no parent, and the **parent entity id** for a
row that has one. With `unique (slug_scope, slug)` that is literally today's three constraints at
once: `uq_epic_project_slug` + `uq_ticket_project_slug` (roots), `uq_feature_epic_slug` (features in
an epic), `uq_task_feature_slug` (tasks in a feature).

A single `unique (project_id, slug)` would forbid two features in two different epics from sharing a
name; a single `unique (slug)` is absurd. The scope has to be carried explicitly because the thing a
row is unique within is no longer the same column for every row.

**The service maintains it on write, and a reparent recomputes it.** `slug` stays immutable
(`@Column(updatable = false)`) and `slug_scope` does not — that separation is the point: a move
leaves the slug alone, so the branch names and URLs it is in do not move, and the slug is simply
judged against a new set of siblings. **A collision found there is a validation refusal** naming the
slug and the new parent, never a constraint violation surfacing as a 500 and never a silent re-mint.
The path that owns that refusal is a later task; V9 owes it a column and an index that make the rule
expressible.

## Decisions this document is the record of

Everything below was open and is now settled.

### 1. `CAMPAIGN` goes to depth **-1**; the existing depths do not move

Depths are **relative and only their order matters**. The nesting rule is "strictly less than" and
there is no arithmetic on depths anywhere — no "one level down", no maximum, no count — so a kind
declared above the epics takes a negative number and `EPIC 0 / FEATURE 1 / TASK 2` stay exactly as
they are. Renumbering the estate (campaign 0, epic 1, …) would be a second answer to a question
every reader has already been given, and would silently invalidate anything that had written `1`
down as "feature".

Depth is a **declared field on `ArchetypeSpec`**, never `Archetype.ordinal()`: an ordinal-derived
depth would require inserting a constant at position zero of an enum whose names are in a check
constraint and in every row of a table.

### 2. Rootness is a declared flag, not derived from depth

`ArchetypeSpec.mayBeRoot`. Depth answers "what may contain what"; rootness answers "what may stand
alone". They agree today only by accident — the two roots happen to be the two shallowest kinds —
and they come apart the moment a campaign is declared above them: an epic stops being shallowest and
must go on being a legal root. Deriving rootness from "is the minimum depth" would revoke
epic-as-root on the day the campaign lands, with nothing in the diff saying so.

So the root archetype set **is** constrained, and widening it is one word.

### 3. Violations are records, not strings, and come back all at once

`ArchetypeViolation(archetype, property, reason, detail)` over an `EntityProperty` vocabulary, and
`NestingViolation(entityId, archetype, parentId, parentArchetype, reason)`. Both carry a
`message()`. A caller fixing one problem per round trip is the failure mode both exist to avoid, and
it is worse for nesting, where the fixes are moves: told one at a time, a caller walks a tree
through several invalid shapes to reach a valid one.

A nesting violation always names the **child**. A parent is never at fault for what somebody hung
under it.

### 4. The nesting validator is its own class

`control/Nesting`, not a method group on `Archetypes`. One place either way; a separate class because
`Archetypes` answers questions about **one row** and `Nesting` answers one about **a graph**, and
they take different inputs (`EntityState` vs a post-state plus a store). Both callers — the ordinary
write and the multi-entity transition — use both, so neither is hidden behind the other.

### 5. `WorkEntity`, not `Entity`

`jakarta.persistence.Entity` owns the word. A class named `Entity` would have to import its own
annotation under an alias in every file that mentioned it, and `import ...epics.entity.Entity` could
not be read without checking which of the two was meant. The **table** is `entity` — that name is
right and it is what a reader of the SQL sees — so the class carries `@Table(name = "entity")`.

This is the one place in the module where a table name and a class simple name deliberately
disagree; V1's other tables rely on unquoted mixed case folding to lower case and need no `@Table` at
all. The table in V9 is therefore spelled in lower case, to say so out loud rather than to rely on a
fold that no longer matches a class name.

### 6. `entity.status` is a `String` in Java

See "the three merges" above. `EntityState` carries it beside the property set and normalises the
two against each other, so `STATUS`-present and a non-null status cannot disagree.

### 7. `SLUG` is permitted everywhere and required nowhere

It is minted by the writer from the title (`Slugs.slugify`), not supplied by a caller. Declaring it
required would fail every create before the writer had run; declaring it absent would let a caller's
slug through unexamined.

### 8. `STATUS` is required of a ticket and merely permitted on an epic

This asymmetry looks like an oversight and is not. An epic's phase is minted by the writer — every
epic starts `REFINING`, set by `EpicService.create` and never by a caller — so demanding it of a
candidate would fail every create, exactly as for `SLUG`. A ticket's status is a statement the
intake surfaces already make and the transition API moves; the five words are the ticket's whole
lifecycle and a ticket without one is not a ticket in any phase.

The column is nullable either way, because features and tasks have no status at all.

### 9. Position lives on the **edge**, not on the child

`entity_membership.position`, dense and zero-based, `dossier_page.position`'s rule and
`DossierPageRepository.maxPosition`/`closeGapAfter` + `DossierService.move` as the idiom to copy.
On the edge because the overlapping campaign membership needs it there: a row that belongs to two
things has two positions, one per membership, and a column on the child could hold only one.

### 10. One parent per child, named for what it asserts

`uq_entity_membership_one_parent_per_child unique (child_id)`. The campaign epic adds an overlapping
membership kind, so the relaxation is a `kind` column on this table (`STRUCTURAL`, `CAMPAIGN`) and
this constraint becoming partial — `unique (child_id) where kind = 'STRUCTURAL'`. That is a
migration on one table, which is only true because the tree-ness stands in **one named constraint**
rather than being spread across the schema.

## The one narrowing, stated rather than discovered

**Today an epic and a ticket in the same project may hold the same slug.** They are two tables with
two constraints, and nothing stops it; the branch prefixes differ (`epic/<slug>` versus
`ticket/<slug>`) so no branch collides either.

Under `slug_scope` they share the project id as their scope and **can no longer**. That is a real
change, and **V10 has to answer for it**: a colliding epic/ticket pair must be re-slugged there,
deliberately and with the branch names it moves accounted for, rather than being discovered as a
migration that will not apply against the live database.

It is accepted because the alternative — folding the archetype into the root scope
(`<projectId>:EPIC`) — buys a collision nobody wants and costs the property that makes the column
readable: **the scope of a row is the id of the thing it belongs to, and nothing else.** If the
backfill finds the collision is common rather than theoretical, that escape hatch is one line in the
writer and one sentence here.

## Where the code is

| | |
| --- | --- |
| migration | `epics/src/main/resources/db/epics/migration/V9__entity_membership.sql` |
| entities | `epics/…/entity/Archetype.java`, `WorkEntity.java`, `EntityMembership.java` |
| repositories | `epics/…/persistence/WorkEntityRepository.java`, `EntityMembershipRepository.java` |
| the registry | `epics/…/control/Archetypes.java`, `ArchetypeSpec.java`, `EntityProperty.java`, `EntityState.java`, `ArchetypeViolation.java` |
| the nesting rule | `epics/…/control/Nesting.java`, `EntityFact.java`, `EntityFacts.java`, `NestingViolation.java` |
| tests | `epics/src/test/…/control/ArchetypesTest.java`, `NestingTest.java`; `…/persistence/WorkEntityPersistenceTest.java`; `…/migration/EntityMembershipMigrationTest.java` |

The rule tests are plain JUnit and boot no application: a `@TestProfile` is a whole Quarkus app at
roughly 125 MB of retained metaspace inside a 4 GB CI step, and rules that are pure functions should
cost none of it. The persistence test is the one that has to be a `@QuarkusTest` — Flyway owns the
DDL and `database.generation` is `none`, so a column name that disagrees with V9 is invisible until
the first query — and it adds no profile of its own.
