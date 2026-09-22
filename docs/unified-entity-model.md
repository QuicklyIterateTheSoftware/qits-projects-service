# The unified entity model

Epic, Ticket, Feature and Task become **one table discriminated by an archetype**, with the
parent/child relation as a row of its own. This file is the contract the rest of that work builds
against: what shipped in V9, which old field became which column, what each archetype declares, and
every decision that had to be made along the way.

**All four archetypes read and write the new model now.** V9 was additive in the strongest sense —
no existing table, column, entity, service or controller touched, no row copied — and V10 copied the
rows and still read none of them. Then "The cutover of the two roots" below moved `EpicService`,
`TicketService`, `EpicLifecycle` and `TicketLifecycle` onto `entity`; and "The cutover of the two
descendants" moved `FeatureService` and `TaskService` onto `entity` **plus `entity_membership`,
which is the parent/child relation of the whole planning tree from that point on**.

**Nothing is on an old table any more.** "The cutover of the dossier" below moved `DossierService`
onto `entity` and `V12__owner_keys_to_entity.sql` repointed the four outward foreign keys that were
holding the mirror, so the write-behind mirror is gone and **no class under `src/main` constructs,
persists, updates or deletes an `Epic`, `Ticket`, `Feature` or `Task` row**. The four old tables
have no writer and no referent; they are a frozen snapshot.

## Reserved migration versions

| version | what it is | state |
| --- | --- | --- |
| `V9__entity_membership.sql` | the two tables, empty | **shipped** |
| `V10__backfill_unified.sql` | the backfill — every Epic/Ticket/Feature/Task row copied in, **ids unchanged** | **shipped** |
| `V11__entity_number.sql` | the numeric id: the column, `uq_entity_project_number`, the backfill and the allocator's counter | **shipped** |
| `V12__owner_keys_to_entity.sql` | the four outward foreign keys repointed at `entity(id)`, which retires the mirror | **shipped** |
| `V13__drop_legacy_planning_tables.sql` | the four old tables dropped, and `ck_audit_entity_type` re-stated off the archetype set over an unchanged vocabulary. It deletes the verification door with them — `entities/…/migration/`, `service/…/entities/api/MigrationVerificationController.java` and both its test classes — because a door whose comparison target no longer exists can only answer about nothing. See "The cleanup, as shipped" below | **shipped** |

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
    number bigint not null,                    -- V11; uq_entity_project_number, with project_id
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

`ck_entity_status` spells the union of `EpicStatus` and `TicketStatus`, which overlap on
`IMPLEMENTED` — nine words as V9 wrote it: `REFINING, IMPLEMENTATION, IMPLEMENTED, SUPERSEDED,
ABANDONED, REPORTED, REFINED, VERIFIED, DONE`. A check constraint has no way to say "these ones when
the archetype is EPIC" without becoming a second place the vocabulary is written down, so **the
constraint spells the vocabulary and `control/Archetypes` spells the rule**. That split is V1's,
applied again — and it is why a lifecycle that gains a word is two changes rather than one: the
enum, and a migration that widens this constraint to match it.

## The per-project numeric id (V11)

Every entity carries a `long` that is **unique within its project and never reused**, written by
hand in its qualified form `<project>-<n>` — `qits-1337`.

```sql
alter table entity add column number bigint not null;
alter table entity add constraint uq_entity_project_number unique (project_id, number);

create table entity_number_sequence
(
    project_id  varchar(255) not null,
    next_number bigint       not null,
    primary key (project_id)
);
```

**The uuid is still the primary key.** This is a second identifier, not a replacement: every dossier
page, audit entry, branch name, workspace and URL on the platform names `entity.id`, and "The ids
are the same id space" above is the argument for why that may not move.

### Why a number when there is already a uuid and a slug

Because the id has to survive where neither does. A uuid does not fit in a commit subject. A slug is
truncated at 55 characters (`Slugs.MAX_LENGTH`) and is minted from a title, so it is neither complete nor — before the
first branch is cut — reliably the thing anybody remembers. The number is short enough to write by
hand, stable for the life of the entity, and unambiguous once qualified by its project. That is what
turns "which subject does this change belong to" from a guess into a recorded fact.

**Per project rather than global**, so the numbers stay small enough to read and the qualified form
carries its own scope. The qualifier is the **project's slug** (`qits`), which lives in `domain`'s
`project` table and is therefore resolved at the surface rather than stored here; `entity` holds the
bare number and `project_id`. **Putting the two together — on the DTOs, on the MCP returns and in a
commit subject — is done**, and "The qualified form, and where it is rendered" below is the record
of it. This section is what makes the id exist and be allocated correctly.

**`uq_entity_project_number` is over `(project_id, number)` and over nothing else, which is the
statement that the id names a NODE and not a ticket.** The unified table holds every archetype, so
an epic, its features, its tasks and the project's tickets all draw from one run of integers and a
ticket and a feature in the same project can never share a number. A partition by archetype would
have made `qits-7` ambiguous in exactly the project that scopes it.

### The allocator: a counter row, bumped in a transaction of its own

`entities/control/EntityNumbers` is the one place a number comes from. Two properties are wanted and
they pull against each other:

- **Two simultaneous creates cannot collide.**
- **A rolled-back create does not make its number reappear.**

| implementation | no collision | no reuse |
| --- | --- | --- |
| `max(number) + 1`, read then write | **no** — two creates read one maximum | **no** |
| a postgres **sequence per project** | yes | yes (`nextval` is non-transactional) |
| a counter row in the **caller's** transaction | yes (row lock) | **no** — the rollback undoes the bump |
| **a counter row in its OWN transaction** — taken | yes | yes |

The sequence is the textbook answer and it is right about both properties, which is why the taken
answer imitates it. What rules it out is the *per project*: a sequence per project is one `create
sequence` per project — DDL the platform's deployer does not run, that no migration in this lineage
could account for afterwards, and that two concurrent creates of a brand-new project's first entity
would race on in the system catalogue.

So: a counter row, bumped by `update … set next_number = next_number + n`, which takes the row's
write lock and is atomic under READ COMMITTED — that is the first property — inside a transaction of
its own, which commits before the create's transaction does anything further, and that is the
second. It is a sequence emulated in a row, with the one behaviour that matters preserved: **the
number leaves the counter for good the moment it is handed out**.

**Gaps are therefore ordinary and are fine.** The id is a name, not a count: nothing sums it,
nothing pages by it, and nothing reads a missing number as a missing row.

**What it costs, stated rather than hidden.** One extra short transaction per created row — a second
pooled connection, held for the length of two statements, nested inside the create's own
transaction. That is why the concurrent-create test runs eight threads rather than sixteen: the pool
ceiling is what bounds a create, not the allocator, and `EntityNumbersTest` hammers `EntityNumbers`
directly at sixteen threads to prove the allocator itself. What the cost buys back is real — the
counter's row lock is held for the bump alone rather than for the whole create, so two people
planning in one project do not serialise on each other's slug reads and audit writes.

`allocate(projectId, count)` is the batch form, and `EpicService.supersede` is its caller: a
supersede copies a whole feature/task tree, the size is known before anything is written, and one
bump for the block is one round trip instead of N.

### The module boundary, and how the tension was resolved

`domain`'s `ProjectService` is the platform's other per-project derivation — it is where the project
slug is allocated, and where the cap of 31 comes from so `<slug>-<slug>` still fits a git-host
repository id. It is also the wrong module, and **`CLAUDE.md` is emphatic that `entities` depends on
neither `domain` nor any auth module and should stay that way**: it is the module most likely to be
lifted out next, and a lift-out dragging `domain` behind it would move a database rather than tables
out of somebody else's.

The resolution is the one this module has already reached for exactly this tension, once:
`Slugs.slugify` is a **deliberate copy** of `ProjectService.slugify`, duplicated rather than shared,
with each side told to change the other. The idiom travels; the dependency does not.

Here not even a copy was needed, and the reason is stronger than style: **a project slug is
allocated against the `project` table in the `projects` database and an entity number against the
`entity` table in the `epics` database — two separate physical databases.** A shared allocator could
not have been in one transaction with either write even if the module boundary had permitted it. So
`EntityNumbers` sits in `entities/control/`, beside the services that create the rows it numbers, and
its javadoc carries the rule and its reason rather than a reference across a boundary that does not
exist.

### Every create path allocates; the transition allocates nothing

| path | what it allocates |
| --- | --- |
| `EpicService.create` → `insert` | one |
| `TicketService.create` | one |
| `FeatureService.create` | one, from the **project's** run and not the epic's |
| `TaskService.create` | one, from the **project's** run and not the feature's |
| `EpicService.supersede` → `copyUnder` | one block for the whole copied tree — a copy is a new entity |
| `EntityTransitionService` | **nothing** |

The last row is the one worth stating. A transition **creates nothing** — "Existing ids only, and
refusals are collected rather than thrown" above is the rule — so a re-archetype, a reparent and a
whole tree restructured in one request leave every number exactly where it was. That is also the
right answer on its own terms: the number names a node, and a node that changes which kind it is is
still the same node. `WorkEntity.number` is `@Column(updatable = false)`, so the schema says it
rather than a convention, and a write that tried to move one would be a silent no-op rather than a
wrong row.

### The backfill, and where the allocator starts

V11 backfills in the same migration that creates the column. By the time it runs, V10 has copied
every Epic, Ticket, Feature and Task into `entity`, so one pass over `entity` is the whole backfill:

    row_number() over (partition by project_id order by created_at, id)

**`(created_at, id)` is V10's own total order** — the one it derived `entity_membership.position`
from — and it is total rather than merely plausible: `created_at` ties (V10's fixtures tie on
purpose) and `id` is the primary key, so the pair cannot. Numbering by the order rows come back
instead is a fact about physical layout that changes with a VACUUM and differs between the test
database and the live one; it would produce a plausible-looking sequence that silently disagreed
with itself between two runs.

**The counter is seeded `max(number) + 1` per project by the same file**, which is the whole of "the
allocator starts above the highest backfilled value". Deriving a floor at runtime instead would be a
`max()` read — the read-then-write this design exists not to do. A project with no entity row gets no
counter row at all and the allocator mints it at 1 on first use, which is also why nothing has to
write this table when a project is created or deleted.

### What proves it

- `EntityNumbersTest` — **an actually concurrent run** (sixteen threads × twenty allocations in one
  project: 320 numbers, no repeat; and eight threads creating epics at once through the real path),
  **an actually rolled-back transaction** whose number never comes back, one run of integers across
  all four archetypes, two projects both starting at 1, and a supersede numbering its copies afresh.
  A `max(n) + 1` allocator passes every sequential assertion in that class and fails those two,
  which is why they are there.
- `EntityNumberMigrationTest` — the backfill over an estate that already exists, which no other
  suite can reach because every one of them starts from an empty database. The same estate inserted
  in the **opposite order** into a second database is numbered identically; the counter is above the
  highest backfilled value in each project; and the constraint refuses a second row on one number in
  one project while permitting it in another.
- `EntitiesTestSupport.wipe()` clears `entity_number_sequence` with the rows it numbered. In production
  a number is never reused, which is exactly why that line is needed: without it a test's first epic
  is numbered by however many rows the previous test happened to create.

**No existing test's assertions moved.** The only test changes are mechanical: the wipe above, and
`WorkEntityPersistenceTest`'s hand-built fixtures taking a distinct number each, since every one of
them is in one project and `uq_entity_project_number` now applies.

## The qualified form, and where it is rendered

`<project-slug>-<number>` — `qits-1337`. The bare number is `entity.number`; the qualifier is
`project.slug`. **The two are in two different physical databases, in two modules that do not
depend on each other**, and everything below follows from that one fact.

### `entities` never sees it, and that is the constraint rather than a preference

`CLAUDE.md` is emphatic that `entities` depends on neither `domain` nor any auth module. **What
actually enforces that is `entities/pom.xml`** — the dependency is simply not declared, so a reach into
`domain` from that module does not compile. `entities`' `ArchRulesTest` is *not* the guard, whatever
its name suggests: it runs `CausationRowRules` and nothing else, and it would pass a module that had
just grown the dependency. The project slug lives in
`domain`'s `project` table, in the `projects` database. `entities` therefore knows the bare `number`
and the `project_id` and nothing else; **it cannot render the qualified form even if the module
boundary had permitted the reach**, because there is no join across two physical databases to make.

So the assembly happens one module up, at the DTO boundary, in
`service/…/projects/api/QualifiedEntityIds` — **modelled directly on `DispatchedWorkspaces`**, which
is the sanctioned place `entities.api` responses are already decorated with data from another context,
and which sits in the same package for the same declared reason. The `entities` side carries the datum
and a `withQualifiedId` setter; the `service` side carries the rendering.

| where | what it carries |
| --- | --- |
| `entity.number`, `entity.project_id` | the storage |
| ~~`WorkEntityProjections` → `Epic`/`Ticket`/`Feature`/`Task`~~ | carried `number` and `projectId` on all four as `@Transient`, because the legacy tables had neither column. **Deleted**: the row a service answers with is the `entity` row itself, where both are real columns |
| `EpicDto`/`TicketDto`/`FeatureDto`/`TaskDto` | `number`, `qualifiedId` (null off the mapper), and `projectId` on the two descendants |
| `TransitionedEntity` | `number`, `qualifiedId` (null out of `of(…)`), plus `withQualifiedId` |
| `service/…/projects/api/QualifiedEntityIds` | **the only renderer**: `render(slug, number)` |
| `service/…/projects/entitieshost/CommitSubjectEntities` | **the only reader**: the grammar and the lookup |

The mappers spell `@Mapping(target = "qualifiedId", ignore = true)` **explicitly** rather than
letting MapStruct's silence produce the null: the null is a statement about the module boundary, and
a reader who finds it has to be able to tell it from an omission.

### The N+1 answer: one lookup per listing, never one per row

`ProjectRepository.list(Collection<String>)` and `ProjectService.slugsByIds(Collection<String>)` are
one query — `id in (…)` — and `QualifiedEntityIds` collects the **distinct** project ids of a whole
page before asking. That is `WorkEntityRepository.listByIds`' rule and `DispatchedWorkspaces`' rule
applied a third time, and it is asserted rather than described: `QualifiedEntityIdsTest` counts the
lookups a forty-row listing across two projects makes (one) and what it was asked about (two ids).
An empty listing asks nothing at all, and a `projectId` naming no project row leaves `qualifiedId`
**null** and never throws — a decoration degrades to what the screen showed before the field
existed.

**The two project-scoped listings pay nothing extra.** `ProjectEpicsController` and
`ProjectTicketsController` already call `projectService.get(projectId)` for the 404 and discarded
the result; they read the slug off it and render inline, so no second lookup is made there.

Every response path carries the field: both project listings, both creates, the single gets, the
updates, both lifecycle transitions (the epic's successor included), the feature and task listings
and creates, and the multi-entity transition's whole map.

### MCP returns carry `qualifiedId` ONLY, never the bare number

Every entity-shaped record on the `repository` server gained one field and only one:
`EpicMcpTools.{EpicSummary, EpicDetail, FeatureSummary, FeatureDetail, TaskSummary, TaskDetail,
TaskImplemented}` and `TicketMcpTools.{TicketSummary, TicketDetail}`. `EntityMcpTools` answers
`TransitionedEntity` and qualifies it on both tools. **`DossierMcpTools`' pages and `DossierFigure`
are not entities in the merged table and gained nothing.**

The reason is the surface's purpose. **An agent that is shown a bare number will hand-prefix it, and
it will get the qualifier wrong** — the repository's name, the epic's slug, its own project when the
id came from somebody else's. The surface that exists so an id can be written into a commit subject
should only ever hand out the form that belongs in one. The REST DTOs carry both because the SPA
needs the datum as well as the rendering: it sorts, filters and links on the number and is not the
thing that types a commit message.

The slug is resolved **once per tool call** — `ProjectScopeGuard.scopedProjectSlug()`, the precedent
that class already sets for an MCP class reaching `domain`'s `ProjectService` — and handed into the
`summarize(…)`/detail builders, never asked per row. **No tool was added, renamed or reshaped**, so
`RepositoryMcpToolsTest.exposesExactlyTheRepositoryContextToolset` and the three MCP suites pass
with their assertions unchanged.

## Reading it back: the commit-subject parser

`service/…/projects/entitieshost/CommitSubjectEntities` — **one parser, in one place, and it is the
only thing in the estate that knows this grammar.** It lives in `service` because resolution needs
*both* databases (the slug in `domain`, the `(project_id, number)` pair in `entities`) and `service` is
the only module that can hold the pair; `projects/entitieshost/` is where this repository already
declares a service-layer bridge into `entities` (`TicketUnattendedGateTickets`).

    /** the grammar, pure and side-effect free */
    static Optional<QualifiedId> reference(String subject);
    record QualifiedId(String projectSlug, long number) { String rendered(); }

    /** grammar + lookup, in one call */
    Optional<NamedEntity> resolve(String subject);
    record NamedEntity(String id, String qualifiedId, String projectId, String projectSlug,
                       long number, Archetype archetype, String status, String title);

`NamedEntity` is shaped for its first consumer — a wrapper release request refusing a branch whose
subject does not name a VERIFIED entity — so **the entity, its archetype and its status come from
one lookup**. That consumer is not built here. The lookup hits `uq_entity_project_number` directly,
through `WorkEntityRepository.findByProjectAndNumber`; the two reads are in two separate
transactions because the two datasources are local and non-XA and Narayana enlists one such resource
per transaction, which is the rule `EpicMcpTools` already states.

### The grammar

`term(<project-slug>-<n>): message` — the id sits in the conventional-commit **scope**.

- **Only the first line is considered.** Everything from the first `\n` on is the body and is
  ignored, *including a body that itself contains something id-shaped* — which is the ordinary case
  of a message quoting an id it is not filed under.
- The term before `(` may be absent (`(qits-1337): msg`) or contain a slash (`entities/control`). It
  may not contain whitespace or a colon, which is what stops `fix: tidy (qits-7): …` from reading
  its parenthesised aside as a scope.
- An optional breaking-change `!` may sit between `)` and `:`.
- Inside the parens, `<slug>-<digits>`, split on the **LAST** hyphen-then-digits — so a project slug
  that itself ends in digits (`other-2`) still reads correctly. The digit run is bounded at 18, so a
  number that could not fit a `long` reads as no id rather than as an exception.

### "No subject" is a NORMAL answer, and it is NEVER a complaint

**Every commit already in this platform's history predates this convention and always will**, and
most commits written after it will not carry an id either. A subject with no id, a malformed id and
a well-formed id naming no entity are therefore one answer — `Optional.empty()` — and **nothing is
logged for any of them: no warning, no error, no debug-level complaint, no counter, no metric,
nothing any reader could ever take for a degraded state.** The class holds no logger field at all,
which is the cheapest way to make that unbreakable, and
`CommitSubjectEntitiesTest.aSubjectWithNoIdIsNotAnErrorAndIsNotEvenMentioned` attaches a JUL handler
at WARNING+ to the class's own category and asserts nothing was recorded.

Get this wrong once and every reader built on top inherits a false alarm that can never be cleared,
because the condition it fires on is the ordinary case. A caller that *needs* an id says so itself,
in its own words, at its own call site.

### Cross-project: it RESOLVES

`resolve` takes **no "current project" argument**, and that is a decision rather than an omission.
**The form is project-qualified, which is the entire reason it is qualified**: `other-7` names
project `other`'s entity 7 unambiguously and globally, and a resolver that silently refused it — or,
worse, read it as the caller's own entity 7 — would be answering a question nobody asked. What a
consumer that cares about the project does is **compare**: `NamedEntity` carries `projectId` and
`projectSlug` precisely so the refusal can be made on that consumer's terms and in its words, rather
than disguised as "no id found". `anIdFromAnotherProjectResolvesToThatProjectsEntity` pins it, over
two projects whose runs both start at 1.

## What is left for the SPA

Nothing on the SPA was touched (it is a separate repository). What it will find, all additive:

| shape | new fields |
| --- | --- |
| `EpicDto`, `TicketDto` | `number` (`long`), `qualifiedId` (`String`, e.g. `qits-1337`) |
| `FeatureDto`, `TaskDto` | `projectId`, `number`, `qualifiedId` |
| `TransitionedEntity` (the transition's answer map and `list_entities`) | `number`, `qualifiedId` |

`qualifiedId` is the string to render and to offer for copying; `number` is the datum to sort,
filter and search on. `qualifiedId` is **nullable** and a client must treat it so: it is null
exactly when the owning project row could not be resolved, which is the degraded case the renderer
answers with rather than failing the read. No field was removed or renamed, and `docs/openapi.yml`
is regenerated with the new shapes.

## The column-per-property map

| old table.column | new column | note |
| --- | --- | --- |
| `epic.id`, `ticket.id`, `feature.id`, `task.id` | `entity.id` | unchanged values; one id space |
| — | `entity.number` | new (V11): the per-project numeric id, `<project>-<n>` — see above |
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

Declared in `entities/control/Archetypes.java`, as data. Nothing else re-decides any of it.

| archetype | depth | may be a root | requires | permits (beyond required) | legal statuses |
| --- | --- | --- | --- | --- | --- |
| `EPIC` | 0 | yes | `TITLE` | `SLUG`, `DESCRIPTION`, `STATUS`, `SUPERSEDED_BY` | the five `EpicStatus` words |
| `TICKET` | 0 | yes | `TITLE`, `TICKET_TYPE`, `STATUS` — **plus `IMPETUS` at create** | `SLUG`, `DESCRIPTION`, `IMPETUS`, `ASSIGNEE`, `CREATED_BY` | the six `TicketStatus` words |
| `FEATURE` | 1 | no | `TITLE` | `SLUG`, `DESCRIPTION`, `DEPENDS_ON`, `IMPLEMENTED_AT` | none |
| `TASK` | 2 | no | `TITLE`, `REPOSITORY_ID` | `SLUG`, `DESCRIPTION`, `DEPENDS_ON`, `IMPLEMENTED_AT` | none |

**There are two required sets, not one.** `required` is what a row of a kind must carry at every
moment of its life; `requiredAtCreate` is what intake demands of a row being born, and it is the
wider of the two. They differ on exactly one entry in the whole registry — a `TICKET` requires an
`IMPETUS` at create and not afterwards — and "The `IMPETUS` question, settled" below is the argument
for why the axis exists at all. `required` ⊆ `requiredAtCreate` ⊆ `permitted` for every archetype,
and a kind permits `STATUS` exactly when it declares status words. Every one of those invariants is
asserted at **class-initialisation** time, so a bad declaration is a refusal to start naming the
archetype rather than a rule that can never fire.

**What a check answers.** `Archetypes.validate(EntityState)` returns **every** violation, never the
first, in vocabulary order. Three questions, all three always asked: every required property
present; no property the archetype has no slot for; and the status word, when there is one, in this
archetype's lifecycle. **A foreign property is refused, not silently dropped** — a value arriving on
a kind that cannot hold it means the caller and the model disagree about what is being written, and
dropping it would lose the value and the disagreement together.

Violations are structured (`ArchetypeViolation`: archetype, property, reason, detail) so a caller
can put the complaint on the offending field, with `message()` for a log line or a plain error body.

## The cutover of the two roots

`EpicService`, `TicketService`, `EpicLifecycle` and `TicketLifecycle` read and write `entity`. That
table is the source of truth for every value a caller sees and for every rule either service
applies: the status a transition is judged against, the slugs a create mints around, the two
listings the board draws, and the `createdAt`/`updatedAt` a write answers with. Nothing above them
moved by one byte — `EpicDto` and `TicketDto` keep every field, in order, under the same JSON names;
the five epic and ticket controllers, both mappers, the MCP tools, `DossierService` and
`EpicsTopicHints` are untouched; and every route, status code and error body is what it was.

What made that possible at the time was that the two services still **returned** `Epic` and `Ticket`,
built by `control/WorkEntityProjections` as detached projections of the `entity` row — a fresh object
that was never persisted, merged or attached, taken only after an explicit flush, because
`@CreationTimestamp` and `@UpdateTimestamp` are populated at flush and a create is promised a
`createdAt` the moment it returns.

**That device was scaffolding and it is gone.** The two services answer the merged `WorkEntity` row
itself now and `WorkEntityProjections` is deleted with the four shapes it built; the flush rule
survives it unchanged, because the row a write answers with is still read back after the flush that
populates its timestamps. Nothing on the HTTP surface moved either time — see "The cleanup, as
shipped" below, which is the record of the second half and of the one consequence it carries for
`auditentry.snapshot`.

## The cutover of the two descendants

`FeatureService` and `TaskService` read and write `entity` + `entity_membership`. They answered
`Feature` and `Task` as detached `WorkEntityProjections` — the identical device the two roots used,
for the identical reason — so `FeatureDto`, `TaskDto`, `FeatureController`, `TaskController`,
`EpicController`, `EpicsTopicHints`, `EpicMcpTools` and `EpicDispatchController` were untouched and
every route, status code and error body is what it was. They answer `control/Nested` — the merged row
beside its parent's id — since the cleanup; the three merged columns are read back under their old
names at the DTO boundary instead, in `mapper/WorkEntityMapper`, which is where
`depends_on_entity_id` becomes a feature's `dependsOnFeatureId` and a task's `dependsOnTaskId` and
`implemented_at` becomes a feature's `implementedOn`.

The parent is the change that is not merely a rename. **`feature.epic_id` and `task.feature_id` are
`entity_membership` rows now**, and four things follow:

- **"The same epic" is "the same parent".** The dependency scope check is one `membershipOf` lookup
  plus an archetype check, not a column comparison. Both refusal messages are unchanged
  (`Unknown or out-of-epic dependsOnFeatureId: …`, `Unknown or out-of-feature dependsOnTaskId: …`).
- **A task's epic is TWO hops** — task → feature → epic — where it was two columns. It is resolved
  **once per service call** and passed down to the phase guard and to every audit row, rather than
  re-walked per row.
- **The slug scope is the parent id**, which is what `uq_entity_slug_scope_slug` makes of
  `uq_feature_epic_slug` and `uq_task_feature_slug`. `Slugs.slugify`/`unique` are unchanged, so a
  slug minted now is one either old writer would have minted.
- **Every row carries `project_id`**, copied from the parent's row at create. No walk answers "whose
  is this" any more.

### Ordering and position, as implemented

`entity_membership.position` is **dense and zero-based**, `dossier_page.position`'s rule and
`DossierPageRepository.maxPosition`/`closeGapAfter` copied rather than reinvented.

- **A create appends** at `maxPosition(parent) + 1`.
- **A delete closes the gap** (`closeGapAfter(parent, position)`), so the survivors are `0, 1, 2, …`
  and never `0, 2, 3`. A sparse sequence sorts the same way, which is exactly why the test asserts
  the numbers and not only the order.
- **A listing is drawn in the edges' order**, not the rows'. `childrenOf` answers in position order;
  `listByIds` answers oldest-first; so the rows are indexed by id and re-emitted in the edges' order.
  **Two queries per listing, never one per row** — the N+1 is the single performance mistake this
  model makes easy, and a tree listing is where it would land first.
- **`supersede` copies the edges with the rows**, taking each copy's position from the source's
  order, so the successor draft is drawn in the order of the plan that was discarded.

### The subtree walks moved with them

`EpicService.stampImplemented`, `supersede` and the cascade in `delete` walk `entity_membership` +
`entity` now. They **had to**: a feature created after this change has no legacy row at all, so a
walk over `FeatureRepository`/`TaskRepository` would have found nothing, silently. Each one reads a
whole level at a time (`childrenOf`/`childrenOfAll` then `listByIds`) and never one query per node.
`delete`'s two nested loops are one membership-driven walk that records a DELETE audit row per
FEATURE and TASK descendant; the edges go with the FK's `on delete cascade`.

`EpicServiceTest.deleteCascadesToFeaturesAndTasks` and
`deleteRecordsAuditForWholeSubtreeAndSurvivesDeletion` pass with their assertions unchanged, which
is the oracle this cutover was steered by.

### `Nesting` judges every new membership, and NEVER a `dependsOn`

`control/StoredEntityFacts` is the `EntityFacts` implementation over the two repositories — bulk by
shape, two queries per question, a row with no edge answering as a root rather than as an absence —
and it is what `Nesting.check` reads the untouched half of the post-state from. Both services run it
on the edge they have just written.

**`dependsOn` is a column and is never handed to it.** The two are easy to conflate (both are a
self-reference between two planning rows) and conflating them would be silent in the ordinary
direction and wrong in the interesting one: a feature depending on a sibling feature is what the
planning surface is *for*, while a feature *under* a feature is an illegal membership.
`UnifiedDescendantsTest.nestingIsNotConsultedForADependency` is the negative that pins it — the
dependency is accepted, the depended-on row does **not** become the parent, and the same pair offered
to `Nesting` as a membership comes back `NOT_NESTABLE`.

### There is NO feature/task mirror, and that is a decision

**`FeatureService` and `TaskService` stopped writing the legacy `feature` and `task` tables
entirely.** No write-behind mirror was introduced, and one must not be.

The mirror that existed for `epic`/`ticket` was there because live foreign keys and out-of-scope
readers named those two tables; "The cutover of the dossier" below is where that stopped being true
and the mirror went. **Nothing on this platform foreign-keys to `feature` or to `task`**,
and once `EpicService`'s three subtree walks moved onto the memberships, nothing read those rows
either. A mirror would therefore have bought a table that is written, never read and never
constrained — and **a half-live table is the worst of the three states**: it looks authoritative to
anyone who opens it, it drifts the first time a path forgets to write it, and the drift is invisible
because no reader would notice.

**No old table is dropped, renamed or altered.** `feature`, `task`, `epic` and `ticket` are the
recovery path until the verification door has run clean against live data, and they are that door's
comparison target. They simply stop growing.

## The cutover of the dossier, and the end of the mirror

`DossierService` reads `entity` and no old table. It made three legacy reads — the `REFINING` guard
in `requireWritable`, and two `findByIdOptional` existence checks in `requireOwner` — and all three
are now one `WorkEntityRepository` lookup **by id and archetype**, which is `EpicService.entity` and
`TicketService.entity` applied a third time and for their reason: the four kinds share one id space,
so a row of the wrong archetype is a 404. Both refusal messages are byte-identical (`Epic not found:
<id>`, `Ticket not found: <id>`). The guard is `EpicLifecycle.requireRefining(row)` — the idiom
`EpicService`, `FeatureService` and `TaskService` already use, and **no second signature was added to
`EpicLifecycle`**: one place, one condition. (It read
`requireRefining(WorkEntityProjections.epic(row))` while the projections stood; the argument is the
merged row itself now, and the condition it applies is the same one.)

With that reader gone, `EpicService.mirrorLegacyRow`, `TicketService.mirrorLegacyRow` and both
`deleteLegacyRow`s are **deleted**, along with the two `EpicRepository`/`TicketRepository`
injections. `EpicRepository`, `TicketRepository`, `FeatureRepository` and `TaskRepository` then stood
on disk with **zero injections in `src/main`**, and the cleanup deleted them with their tables.

### The decision: repoint the keys, do not keep the mirror

Four live foreign keys pointed at the two tables the mirror existed to keep populated. Two answers
were available:

- **(a) keep the mirror.** Go on writing `epic` and `ticket` behind every entity write so the
  constraints resolve.
- **(b) repoint the keys at `entity(id)`.** Taken, as `V12__owner_keys_to_entity.sql`.

| column | dropped | replaced by |
| --- | --- | --- |
| `dossier_page.epic_id` | `dossier_page_epic_id_fkey` (postgres-derived; V5 wrote it inline) | `fk_dossier_page_owner_epic` |
| `dossier_page.ticket_id` | `dossier_page_ticket_id_fkey` (postgres-derived; V8 wrote it inline) | `fk_dossier_page_owner_ticket` |
| `dossier_asset.epic_id` | `dossier_asset_epic_id_fkey` (postgres-derived; V6 wrote it inline) | `fk_dossier_asset_epic` |
| `ticketcomment.ticket_id` | `fk_ticket_comment_ticket` (named, V4) | `fk_ticket_comment_ticket` — same name, same id, one table to the left |

Every replacement keeps `on delete cascade` and every one is **named**, so the next change is an
ordinary drop rather than a second round of guessing what postgres derived. The three derived names
were read off V5, V6 and V8 before the drops were written — V4 and V5 both had to do exactly this
dance for `auditentry_entity_type_check`, and their headers are the precedent. The migration changes
nothing else: no column renamed, no column dropped, `ck_dossier_page_owner` untouched, the four old
tables untouched, not one row written.

**It is safe because the ids are one id space.** V10 copied every epic, ticket, feature and task
into `entity` under the id it already had, so every value in these four columns already resolves in
`entity`: the `add constraint` **validates** against rows that are all present rather than failing
on the first orphan, with no backfill and no `not valid` escape hatch.

**(a) was refused on the descendants' own terms.** Keeping the mirror means the old tables still
have a writer purely to satisfy a constraint, and a half-live table written by nobody's intent is
exactly the state "There is NO feature/task mirror, and that is a decision" already rejected: it
looks authoritative to anyone who opens it, it drifts the first time a path forgets to write it, and
the drift is invisible because no reader would notice. Repointing leaves the old four with **no
writer and no referent**, which is a cleaner thing for the verification door to compare.

### What this means for the verification door — including the half it must not assume

The door (epic feature "Migration verification, against live data") compares the old tables against
the unified model and checks that dossier pages, dossier assets, audit entries, ticket comments and
work branches **still resolve to an entity**. V12 changes no column on any of the four old tables
and copies nothing; it only changes which table those outward references are *constrained* against
— and the direction it constrains them in is precisely the one the door asserts, so that check is
now enforced by the schema rather than merely verified by it.

**What the door must not assume is the reverse direction — "no entity has an id no old row had".**
That has been impossible since `FeatureService`/`TaskService` stopped writing legacy rows, and with
the mirror gone it is now equally impossible for epics and tickets. **The old four tables are a
frozen snapshot of the estate as V10 found it, not a live mirror.** The comparison is forward and
only forward: every old row resolves to an entity, or to the DELETE audit row that says it was
removed through the new model. A door written the other way round would report every row created
since the cutover as a defect.

### The narrowing the foreign key gives up, stated rather than hidden

`dossier_page.epic_id` used to be constrained to an actual epic, and `ticket_id` to an actual
ticket. Against `entity(id)` each is constrained to a row of **any** archetype — the database can no
longer tell an epic's id from a ticket's, because they are one table.

Two things stand where that stood. `ck_dossier_page_owner` still enforces exactly-one-owner, so the
shape of the row is unchanged; and `DossierService` refuses a row of the wrong archetype with a 404
**before it writes**. So the rule is enforced one layer up, which is where `ck_entity_status` already
put the equivalent question: the constraint spells the vocabulary and `control/Archetypes` spells the
rule.

### The cascade is preserved and its source moves

Deleting an epic used to take its dossier pages and assets with it through the legacy row's cascade,
fired by the mirror's own delete. It takes them through the **`entity` row's** cascade now — the row
the service actually deletes — so the safety net hangs off the real write instead of off a
bookkeeping one. Same for a ticket and its comments; `TicketService` still deletes comments
in-service first so each gets its own DELETE audit row, and the cascade stays what V4 called it.

`TicketComment` rows are still written by `TicketService` and still carry `ticketId`, holding the
same string they always did. Only the foreign key under them moved.

### What this task deliberately did NOT touch, and why

Three things were checked and left exactly as they are. Each is recorded because "not mentioned" and
"checked and correct" are indistinguishable to a later reader, and the second is the truth here.

- **`AuditService`, `AuditEntityType` and `ck_audit_entity_type`.** `AuditService` reads and writes
  only `auditentry` and touches no legacy table, so it needed no change; and the vocabulary was
  deliberately left as it was because **the verification door compares old against new and needed the
  old words to compare with**. Renaming or widening it there would have moved the door's own
  yardstick. (V13 re-states the constraint over the same six words and derives the four planning ones
  from `Archetype`; the yardstick had done its work by then.)
- **`control/WorkBranches`.** A pure derivation over slugs and the parent ids the projections carry
  — no repository, no table, nothing to move. **`WorkBranchesTest` is the oracle**: it constructed
  `Epic`/`Feature`/`Task`/`Ticket` directly and asserted against those signatures, and its assertions
  were untouched, which is what says the branch names an agent is given did not move by one byte.
  (The four shapes are `WorkEntity` and `Nested` since the cleanup; the test's fixtures moved with
  them and not one asserted literal did.)
- **The whole service-side dispatch and phase-prompt path** — `TicketUnattendedGateTickets`,
  `TicketPhasePrompts`, `TicketPhaseAdvance`, `TicketDispatchController`, `EpicDispatchController`,
  `TicketWorkspaces`, `DispatchedWorkspaces`, `EpicResolutions`, `RefinementService`,
  `DossierFigures`, `EpicMcpTools`, `TicketMcpTools`, `DossierMcpTools`. Every one of them goes
  through `EpicService`/`TicketService`/`FeatureService`/`TaskService`/`DossierService` and passed
  projections around; **none touches a legacy repository or a Panache static call**. The campaign
  epic drives that path automatically later, so it matters that a reader can see it was checked
  rather than missed. (The cleanup retyped every one of them onto `WorkEntity`/`Nested` — a
  mechanical retype with no behaviour in it, which is exactly what the projections' whole existence
  had been deferring.)

`DossierAssetService` is the fourth: it touches only `dossier_asset`/`dossier_page_asset` and needed
no change, though `dossier_asset.epic_id` is one of the four keys V12 repoints.

### The one test assertion that had to move

`EpicWriteCutoverTest` counted what a create left behind through `EpicRepository.listByProject` —
which answered only because the mirror wrote a legacy row behind every create. With the mirror gone
that table has no writer at all and the same query answers **zero** for every case in the class,
which is a green-looking nothing rather than a failure. So the **subject** moved to
`WorkEntityRepository.listByProjectAndArchetype(…, EPIC)` and the claim did not: exactly one epic
row, counted in the table the create actually writes. Every other assertion in the module is
untouched — the dossier's six suites included, which is what steered the cutover.

`EntitiesTestSupport.wipe()` needed no reordering: the dossier pages, the dossier assets and the ticket
comments already went before `workEntityRepository.deleteAll()`, which is what the repointed keys
now require. Its javadoc says so rather than describing the FK graph it used to have.

### An epic and a ticket now share one slug scope

Both archetypes mint their slug with `slug_scope = projectId`, against `WorkEntityRepository.slugsInScope`.
That is the narrowing "The one narrowing, stated rather than discovered" below already argues for and
V10 already answered for in the backfill; this is the writer's half of it. No existing test depended
on the two being independent scopes. `Slugs.slugify` and `Slugs.unique` are unchanged in shape, so a
slug minted now is a slug either old writer would have minted; only the cap has moved since — see
"The slug cap is 55" below.

### The slug cap is 55

`Slugs.MAX_LENGTH` moved from **40 to 55**, and it is the single constant both `Slugs.slugify` and
`Slugs.unique` read.

Nothing in git or in this database enforces a bound — slug columns are `varchar(255)` and
qits-githost validates refnames through JGit, which has no length cap. What binds is
`WorkspaceService.toWorkspaceSlug` in **qits-workspaces-service**: it sanitizes a branch name and
hard-cuts it at **64 characters**, because the result becomes a filesystem path segment. The longest
branch prefix minted here is `refining/` (9 characters), so `55 + 9 = 64` is the largest slug that
cannot overflow that cut.

Going beyond 55 means fixing the 64-cut first, and that is not a free change: `toWorkspaceSlug`
truncates with **no de-collision**, so two branches whose first 64 sanitized characters agree
collapse onto one workspace id and the second dispatch fails as "Workspace already exists" — a name
clash presenting as a duplicate.

**No backfill.** A slug is minted once at create and never re-derived, deliberately, so that a
rename cannot orphan a branch already cut from the old slug. Names already truncated under the old
40-character cap stay truncated for ever; the new cap widens new slugs only. V10's transcribed
arithmetic below is likewise frozen at the 40 that was current when it was written — it is an
applied migration and its numbers are history, not the live rule.

### The registry judges the ordinary write, with one named exception

`Archetypes.validate(WorkEntity)` runs on every create and every update in both services, and a
candidate the registry refuses is a 400 whose message joins every violation — which is what
`validate` returning all of them rather than the first is for.

**There is no exception any more, and there used to be exactly one.** Every write names the moment
it is — `Archetypes.validate(candidate, Demand.AT_CREATE)` on a create, `Demand.ON_UPDATE` on an
update and on every entry of a multi-entity transition — and **every violation the registry raises at
that moment is refused**. The one tolerated violation, `IMPETUS` missing on a ticket update, is gone
because the registry no longer raises it there: the property is declared `requiredAtCreate` and not
`required`, so intake demands it and an update does not. `ImpetusConcession` is deleted. See "The
`IMPETUS` question, settled".

### What did not change

The retry seams are where they were. `ReadPatience.hold` still wraps the list reads outside any
transaction, `WritePatience.hold`/`run` still replaces `@Transactional` on the write seams and
flushes the `epics` persistence unit last, nothing gained a `@Transactional`, validations that need
no row still run before the wrap, and ids and slugs are still minted inside it. Both listings are
still one query and nothing is resolved per row.

Two test fixtures moved with the storage and no assertion did: `ConnectionLosingEpics` now severs
`WorkEntityRepository.listByProjectAndArchetype` and `FailingEpicWrites` now fails after
`WorkEntityRepository.persist`, because those are the read and the write an epic list and an epic
create actually make now. `EntitiesTestSupport.wipe()` clears the two new tables, children first.

**The descendants' cutover moved no fixture and changed no assertion at all.** Both doubles already
sever the merged table, which is now the read and the write a feature or a task makes too;
`FeatureServiceTest`, `TaskServiceTest`, `EpicServiceTest`, `EpicLifecycleTest`, `EpicApiTest`,
`EpicLifecycleApiTest`, `EpicMcpToolsTest` and `EpicPlanningIT` all pass exactly as written. What was
added is `UnifiedDescendantsTest`, for the four things that are genuinely new and were previously
impossible to get wrong: a listing's order, a middle sibling's removal leaving dense positions, the
two-hop walk to a task's epic, and the `Nesting`-not-consulted-for-`dependsOn` negative.

## The multi-entity transition, as shipped

`POST /projects/api/entities/transition` takes a map of entity id to the **full** state that entity
is to have afterwards, judges the whole of it as one post-state, applies it in **one transaction**
and announces it **once**.

```json
{ "<id>":       { "archetype": "EPIC",    "membership": { "parent": null,   "position": 0 },
                  "title": "…", "status": "REFINING", "description": "…" },
  "<other-id>": { "archetype": "FEATURE", "membership": { "parent": "<id>", "position": 0 },
                  "title": "…" } }
```

### Why it exists, and why it cannot be a loop over the existing PUTs

**A feature becoming an epic while its tasks are rescoped is a state no ordering of single-entity
writes reaches legally.** Re-archetype the feature first and there is an epic under an epic;
reparent it first and there is a feature at the root; move the tasks first and they hang under
something still shaped as a feature. Every intermediate shape is refused by a rule that is correct,
and the whole is correct — which is the argument `Nesting`'s javadoc makes, and this endpoint is the
caller it was written for. `EntityTransitionServiceTest.aFeatureBecomesAnEpicWhileItsTasksAre
RescopedInOneRequest` is that case, and it is the test that fails loudest if atomicity regresses.

**The existing per-entity `PUT`s stay exactly as they are.** This is an addition, not a replacement:
a caller that wants to retitle one epic still has a route that says so, and the ordinary write
expressed here is simply a map of one.

### Existing ids only, and refusals are collected rather than thrown

**Nothing is created and nothing is deleted here.** An id in the map that names no row is a refusal;
so is a `membership.parent` in neither the map nor the store. Creating on an unknown id is the one
thing a transition must never do — the caller supplied that id, and one it got wrong would become a
row nobody meant rather than a message somebody reads.

Both are **violations collected with the rest and answered as one 400**, deliberately not 404s thrown
one at a time. A caller fixing one id per round trip is the failure mode the structured violations
exist to avoid, and it is worse here than anywhere else in the module because the fixes are *moves*:
told one at a time, a caller walks a tree through several invalid shapes to reach a valid one.

**`membership.parent` resolves against the map first and the store second**, which is what lets two
entities swap their relation in one request and what lets a child name a parent that does not exist
in its target shape until the same request commits.

### It is a PUT: an absent property is CLEARED

The entry is **the entity in full**, not a move instruction — description and every
archetype-specific property. That is deliberately not the partial update the four per-entity services
offer, and the reason is what the operation is: a caller re-archetyping a row is stating what the row
*becomes*, and a merge with what it used to be would carry a property the new kind has no meaning for
into a state nobody asked for. There is no clear-flag pairing for the same reason — those flags exist
on a PATCH because absent and "make it absent" are indistinguishable there, and under a PUT they are
one statement.

**A property the target archetype has no slot for is REJECTED, not dropped** — `Archetypes.validate`
already answers in that shape, and for its reason: a value on a kind that cannot hold it means the
caller and the model disagree, and dropping it would lose the value and the disagreement together.

### The two server-owned properties, settled here

Neither `slug` nor `createdBy` is caller-statable, so neither may be cleared merely by not being
mentioned and neither may be a `NOT_PERMITTED` complaint. The rule is: **carried when the target
archetype permits it, cleared when the target has no slot for it.**

- **`slug` is never re-minted and never cleared.** It is permitted on all four archetypes, so it is
  always carried. It names branches already cut and URLs people have sent each other, and it is
  `@Column(updatable = false)` — which makes that a schema fact rather than a convention. **What
  moves is `slug_scope`**, which is the whole point of those being two columns.
- **`createdBy` is carried into a `TICKET` and cleared out of anything else.** A demotion from
  `TICKET` to `FEATURE` clears it, because leaving a reporter on a row that is no longer a report is
  a value nothing would ever correct and nothing could explain. It is never a violation, because the
  caller could not have written it.

**`WorkEntity.createdBy` therefore lost its `updatable = false`**, and that is a decision rather than
a slip. The annotation would have made the clear a silent no-op — the field null in Java, the column
unchanged in postgres — which is the worst of the three possible behaviours. The guarantee that
stands is the one that was ever meant: the column is written by the server at create and by a
re-archetype that removes it, and by nothing else. `slug` keeps `updatable = false` precisely because
*its* rule is the opposite one.

### Three validation layers, ONE rejection

All three run **before anything is written**, and every finding from all three comes back together in
one 400 whose message joins them with `"; "` — the same `BadRequestException` → `EntitiesExceptionMapper`
path the four services already take.

1. **The row.** Each entry against its **target** archetype through `Archetypes.validate(EntityState)`.
2. **The slug scope.** See below.
3. **The tree.** `Nesting.check(stated, StoredEntityFacts)` — evaluated over the post-state
   **including entities the request never mentions**: upwards for an untouched parent's archetype,
   downwards because re-archetyping a row re-judges every child it already has.

### The slug_scope trap, answered

A move changes what a slug is unique within, so a slug that was free under one parent may be taken
under another. That is a **validation refusal naming the slug and the new parent** — never a
constraint violation arriving as a 500, and never a silent re-mint, which is exactly what the
`slug_scope` section above owed a path.

The occupancy is computed over the **post-state** of every affected scope, from the map *and* the
store: every stored row in an affected scope counts, **except one that is itself in the map** and
therefore about to be re-placed. That exclusion is what makes two siblings swapping parents legal
rather than a collision against their own former selves. The bulk read it rests on is
`WorkEntityRepository.listBySlugScopes`, which answers rows rather than strings because the question
is *who* holds each slug: `slugsInScope` cannot say, and asking it once per moved entity would be the
N+1 this model makes easy.

### The concurrency answer is the transaction, and there is no version

**Validation and application happen in the same transaction**, inside one `WritePatience` body, so
nothing read during validation can move before it is written. There is **no subtree token and no
re-read**, and deliberately **no per-entity version**: `WorkEntity` and `EntityMembership` carry no
`@Version`, the single per-entity `PUT`s use none, and inventing optimistic locking here would be a
second concurrency model for one table — reachable through one of two write paths, which is the way
two rules drift invisibly.

### Two rules the transition states itself

- **An entry whose target archetype declares status words must state a status.** `Archetypes`
  declares `STATUS` merely *permitted* on an `EPIC` because an epic's first status is minted by
  `EpicService.create` and demanding it would fail every create (decision 8 above). **A transition
  mints nothing**, so under the PUT rule an omitted status would *clear* one and leave a status-less
  epic `EpicLifecycle.parse` cannot read. Requiring it of the caller is the only answer that neither
  invents a value nor ships a lifecycle-broken row. The stated word is still judged by `Archetypes`
  against the target's vocabulary, so a word from the other lifecycle is `ILLEGAL_STATUS` as ever.
- **A transition does not move work between projects.** Every row carries `project_id` and a
  descendant's is copied from its parent at create; a cross-project reparent would either leave a
  stale value or need a cascade down into entities the request never mentioned. It is refused and
  named. No surface asks for the move.

**It is NOT a lifecycle move.** `EpicLifecycle.requireTransition` and `TicketLifecycle.requireTransition`
are not run here and must not be: the lifecycle rules — adjacency along the pipeline, plus a
ticket's off-path exit — stay owned by the two existing transition endpoints, which is where a caller asking "advance this
ticket" goes. This endpoint answers a different question, *make the shape of the plan be this*, and a
status it is handed is part of the shape rather than a step along it.

### Position, and the renumber that is one place

`membership.position` is caller-stated and **clamped to the legal range rather than refused** — a
caller stating 99 means "last", and making it count the siblings first would be a round trip bought
for nothing. An entry with a parent and no position **appends**. An entry with `parent: null` is a
root and **has no membership row**; any existing edge is deleted, because a root *has* no membership
and that is a statement rather than an absence.

**Both the old and the new parent end dense and zero-based**, through one renumber pass per affected
parent in `EntityTransitionService.replaceMemberships`. A `closeGapAfter` per departing child — the
idiom a single-entity delete uses — cannot be right here: several children leaving one parent in a
single request would each compute their gap from positions a previous close had already moved.
Everything is read before anything is mutated, for the same reason.

**A reparent is an UPDATE of one edge, not a delete and an insert**, because an edge's id is the
child's (V10's rule) and `uq_entity_membership_one_parent_per_child` already says a child has at most
one.

### The side effects, all of them

- **Audit**: one `UPDATE` row per entry, with the `AuditEntityType` of the **target** archetype and
  the **post-state subtree root** as its key — the epic's id for an `EPIC`/`FEATURE`/`TASK`, the
  ticket's own id for a `TICKET`, which is what `AuditEntry.epicId` already means. The snapshot was
  the same `WorkEntityProjections` shape every existing reader of `auditentry.snapshot` expected; it
  is the merged `WorkEntity` since the cleanup, and "One consequence, stated rather than left to be
  discovered" is what that costs. `AuditService.record` keeps its `@Transactional` and joins the
  write, exactly as elsewhere.
- **SSE hints** are fired by the **controller**, after the service returns, never inside
  `WritePatience` — that body re-runs on a retry. Both topics (`EPICS` and `TICKETS`) go out per
  affected project, because a batch may well have moved a ticket and an epic tree at once.
- **The announcement** is made after the transaction has committed, for the same reason.

### The event: `EntityTransitioned`, one per batch

`service/…/bus/EntityTransitioned`, published by `EntityTransitionAnnouncer` (`@ApplicationScoped
@DefaultBean`) over the **optional** `entities/control/TransitionAnnouncer` port, injected as
`Instance<T>`. That indirection is required rather than stylistic: the `entities` module depends on
`domain` nowhere and publishes nothing, and the standing rule is that bus control flow lives in
`service/…/bus/` and nowhere else. It mirrors `projects/control/RepositoryAnnouncer` exactly.

Four rules ride with it, and each is the platform's rather than this endpoint's:

- **The event class lives in `service/…/bus/`, not in a published vocabulary module.** Nothing
  consumes it yet, and a jar this platform's Maven registry does not serve is a build that resolves
  from a developer's `~/.m2` and fails in a release pipeline's step container.
- **It is registered in `EventWireReflection` — and so is its NESTED payload record.** A nested
  record is as invisible to the image builder as its enclosing one, so registering only the outer
  record fails in exactly the same place and the same words as registering neither: inside
  `CanonicalJson`, on the first publish, with the JVM suite green throughout.
  `EventWireReflectionTest` pins both lines.
- **No payload field spells `signature`, `name`, `eventId` or `occurredAt`.** `occurredAt()` is an
  override backed by a differently-named component (`transitionedAt`), `RepositoryRenamed`'s exact
  shape, because Jackson matches the canonical mix-in to a record's accessor *by name* and a
  component sharing one would be dropped from every payload with nothing failing anywhere.
- **One call for the batch, never one per entity.** Announcing the entities one at a time would
  describe a sequence of illegal trees that never existed — which is the whole point of the
  operation, said on the wire.

`RecordingTransitionAnnouncer` in the `entities` suite is the recording double; it wins the port's
injection point simply by existing, past the `@DefaultBean`.

### The answer shape

**A map of entity id to that entity's whole post-state**, keyed exactly the way the request is, so a
caller can put its statement and the result side by side and read off what became of each entry. That
symmetry is why it is not wrapped in an envelope the way the single-entity routes' responses are:
those answer one named thing (`{"ticket": …}`) and this answers the collection it was handed.

`TransitionedEntity` is the merged model and not one of the four projections, deliberately: every one
of those drops the columns its kind has no slot for, and a transition's whole subject is a row
changing which kind it is — so an answer shaped as one kind could not describe the other end of the
change.

### Why the path is `/entities`

**The unified entity is the noun.** The four archetypes are one table discriminated by a column, and
the subject of this endpoint is a row changing which of them it is — so putting it under `/epics` or
`/tickets` would file the operation under one of the two ends it moves between, and a reader looking
for the write surface of the merged model would have to know the answer before finding it.
`/entities` is where that reader looks, and it is the segment the rest of the merged model's surface
grows under as it arrives.

It is under `/projects` like every other machine surface here, so **`quarkus.quinoa.ignored-path-prefixes`
needs no change**: that key already carries the one prefix, and the SPA fallback cannot swallow a path
a real route answers. The class is `@RolesAllowed("qits:admin")` and nothing else — a transition is a
write, an agent keeps every read and gains no write, and there is nothing here to bind a re-shaping
of a project's whole plan to.

### The registry, served

`GET /projects/api/entities/archetypes` answers the whole of `Archetypes` as one JSON object:

```json
{ "properties":  ["TITLE","SLUG","DESCRIPTION","STATUS","TICKET_TYPE","IMPETUS","ASSIGNEE",
                  "CREATED_BY","SUPERSEDED_BY","REPOSITORY_ID","IMPLEMENTED_AT","DEPENDS_ON"],
  "serverOwned": ["SLUG","CREATED_BY"],
  "archetypes":  [ { "archetype": "EPIC", "depth": 0, "mayBeRoot": true,
                     "required": ["TITLE"], "requiredAtCreate": ["TITLE"],
                     "requiredOnTransition": ["TITLE","STATUS"],
                     "permitted": ["TITLE","SLUG","DESCRIPTION","STATUS","SUPERSEDED_BY"],
                     "legalStatuses": ["ABANDONED","IMPLEMENTATION","IMPLEMENTED","REFINING",
                                       "SUPERSEDED"] } ] }
```

**It exists so the archetype gate is met as form fields rather than as an error after a button
press.** That is `Archetypes.validate` returning *every* violation at once, moved one step earlier:
told after the press, a caller fixes one problem per round trip; told before it, there is no round
trip. The caller this was written for is the Angular client assembling a transition, and an agent
doing the same — which has no screen to be told about a missing impetus on.

**It is derived at read time and is never a second declaration.** `ArchetypeRegistryDocument.describe()`
walks `Archetype.values()` and reads `Archetypes.spec(...)`; nothing in it is written down twice, and
a fifth archetype or a twelfth property appears in the answer with no edit to any file on the path.
The alternative — the registry copied into TypeScript — is a copy that drifts the day a property is
added, and drifts *silently*, because nothing compiles the two against each other. Prefer served over
duplicated.

**`requiredOnTransition` is the transition's rule, not the registry's**, and it is served precisely
because the two differ. `required` is what `Archetypes` demands of a row; `requiredOnTransition` is
that plus `STATUS` wherever the kind declares status words, which is "Two rules the transition states
itself" above said as data — a transition mints nothing, so an omitted status would *clear* one. An
epic's lists therefore disagree (`["TITLE"]` against `["TITLE","STATUS"]`) and a ticket's agree. The
condition is `EntityTransitionService.requiresStatusOnTransition`, one method read by both the check
after the press and the document before it, so the two cannot come apart.

**`serverOwned` is `EntityTransitionService.SERVER_OWNED`, the same constant the check reads.** A
client renders no field for `SLUG` or `CREATED_BY`; "The two server-owned properties, settled here"
is why. It is a constant rather than a literal in two places for the same reason the status rule is a
method: a list a client is shown and a list the server enforces, spelled twice, is a field a caller
can fill in and the server silently ignores.

**No archetype PAIR is served, deliberately.** Whether a kind may contain another is
`Nesting.mayContain`, which is `parent.depth < child.depth` and nothing else, so a client holding the
depths derives the whole nesting rule with one comparison. A served matrix would be a second spelling
of the same rule — which is the argument `Archetypes` already makes for declaring a depth at all:
only the ORDER of the numbers means anything, and the order is what travels.

**Two orderings, and one of them means nothing.** `required`, `requiredOnTransition` and `permitted`
are `Set`s in Java and are answered in `EntityProperty` declaration order — the same order
`Archetypes.validate` reports violations in, so a form's fields and a refusal's complaints read in one
sequence. `legalStatuses` is a `Set<String>` whose source enum order is **not recoverable** from
`ArchetypeSpec`, so it is sorted alphabetically to make the answer deterministic, and **a client must
not read a lifecycle, an ordering or a first phase out of it**: `ABANDONED` leads an epic's list and
is its last word. The adjacency rules stay on the two lifecycle endpoints and are not served here.

**It is a class of its own, `service/…/entities/api/EntityArchetypesController`, and it takes
`qits:agent`.** Every read route on this surface takes the agent, and this is a read of four public
declarations — no row, no project, no identity, the same answer for every caller. It is not a method
on `EntityTransitionController` because that class is `@RolesAllowed("qits:admin")` at class level
with an argument for it, a method-level `@RolesAllowed` *replaces* the class list rather than adding
to it, and the exception would be harder to read than the rule. A second root resource at `/entities`
is the shape the segment already has (`EntityTransitionController` is the other; the verification
door was a third until V13 deleted it).

**Three required-lists, and each is exactly what the server enforces at its own moment.**
`requiredAtCreate` is intake's demand, `required` is the invariant every edit is judged against, and
`requiredOnTransition` is `required` plus the transition's own `STATUS`. A client picks the one that
matches the form it is drawing.

The middle one is what this document was missing, and the gap was visible from here: while there was
one `required` list saying what *intake* demands, a client gathering it asked for strictly more than
the server insisted on — it demanded an impetus for a promotion the server accepted without one,
because every update path tolerated exactly that violation through a named concession no reader of
this document could see. That was the safe direction of error and it was still a divergence between
what is advertised and what is enforced. It is closed: the concession is deleted, the axis is
declared, and every list here is a demand the server actually makes. See "The `IMPETUS` question,
settled".

### The agent surface: ONE tool, `transition_entities`

The same operation on the `repository` MCP server (`/projects/mcp`, still named `repository` —
qits-workspace-daemon addresses it by name and nothing about the declaration moved):

    transition_entities(entities: { "<id>": { archetype, membership: {parent, position},
                                             title, description, status, ticketType, impetus,
                                             assignee, repositoryId, implementedAt, dependsOn } })
      -> { "<id>": <the post-state> }

**It is one tool because it has to be.** An agent restructuring a refinement through several
single-entity tool calls is precisely the sequence of illegal intermediate states this design rules
out, so a tool that could only move one entity would reintroduce at the agent surface the exact
problem the endpoint exists to prevent. There is deliberately no single-entity spelling, and
`EntityMcpToolsTest.exposesOneTransitionToolTakingAMap` asserts the absence as well as the presence.

**It is `service/…/projects/mcp/EntityMcpTools`, a class of its own**, and that is
`EntityTransitionController`'s argument one layer up: the unified entity is the noun, so hanging the
tool off `EpicMcpTools` or `TicketMcpTools` would file it under one of the two ends it moves
between, and an agent looking for "how do I restructure this plan" would have to know the answer
before finding it. The REST surface answers that with a resource of its own; the MCP surface answers
it with a tool class of its own, in the same package and on the same server as its three neighbours.

Everything else is the house pattern applied again: scope from the `X-QITS-Project` header and never
from an argument, `@WrapBusinessError` so the refusal arrives as a readable tool error rather than a
JSON-RPC protocol error, both SSE topics fired after the write, no `@Transactional` (two persistence
units, non-XA). Two rules are this tool's own:

- **Every entity the request NAMES must be in the caller's project** — every key of the map *and*
  every `membership.parent`, whether or not that parent is itself an entry. One belonging to another
  project reads as not found, the rule the epic and ticket surfaces already apply. A parent is
  checked for the same reason a key is: a reparent onto somebody else's epic is a cross-project reach
  expressed as a membership.
- **An id that names nothing at all is NOT refused by the tool.** It falls through to
  `EntityTransitionService` and is collected with every other complaint into one refusal. Refusing it
  at the scope check would hand the model one wrong id per round trip, which is the failure mode the
  collected violations exist to avoid — and worse here than anywhere else, because the fixes are
  moves.

`transition_entities` is in `ReadOnlyRepositoryToolFilter.MUTATING_TOOLS`, on the strongest reading
in that list: it restates part of the plan *in full*, so an unattended run steered by an untrusted
commit message could re-archetype, re-parent and clear properties across a whole tree in one call.

**The description is the deliverable.** An agent only ever sees that string, so it states, in this
order and in the imperative: the value is the entity's full target state and not a diff; an omitted
property is cleared, and what that costs on a demotion; every id must already exist — create first,
then transition, nothing is created or deleted here; **a plain edit is a map of one**, said outright
because otherwise agents keep reaching for the older per-entity tools out of habit and this one only
ever gets used for exotic moves; and that a rejection naming missing properties **is the archetype
gate**, whose fix is to supply them rather than to retry. Those sentences are pinned by a test, so a
later edit that drops one fails the build.

### The read side had to keep up, and it is an ADDITION

**Whatever tool an agent uses to see the tree must show archetype and membership, or it cannot
construct a valid request** — an entry is judged against its *target* archetype and names its parent,
and `get_epic`, `list_epics`, `get_ticket` and `list_tickets` report neither.

The answer is a **new read tool, `list_entities`**, on the same class: the project's whole planning
tree as one flat list, each entry carrying archetype, parent and position, roots first and each
followed by its descendants in order. **No existing tool changed name or shape**, which the epic
requires — an agent mid-refinement must not be able to tell this shipped, and `EpicMcpToolsTest`,
`TicketMcpToolsTest` and `DossierMcpToolsTest` pass with their assertions unchanged. The
additive-field route was available and was not taken: a new field on `EpicDetail` would still leave
`list_tickets` and `get_ticket` blind, and `EpicDetail`'s nesting is the epic-shaped tree the merged
model exists not to be the only reading of.

**It answers `TransitionedEntity`**, the transition's own answer shape, so the read and the write
speak one vocabulary: an entry read there is an entry restatable here. Flat rather than nested
because the membership is a property of the row now — a nested answer would have to pick one shape
per archetype again.

Behind it is `entities/…/control/EntityCatalogService`: `listByProject` and `byIds`, each a bulk row
read plus a bulk edge read (**two queries, never one per row**) wrapped in `ReadPatience` like every
other list read in the module. It adds a reading and changes none — the four per-archetype services
keep every method, shape and caller.

### `exposesNoTransitionTool` does not collide, and that was checked

`EpicMcpToolsTest.exposesNoTransitionTool` asserts that no tool named `transition_epic`,
`supersede_epic` or `mark_epic_implemented` exists. It matches on **those three exact names**, not on
a name containing "transition", so `transition_entities` does not trip it and the test passes
unmodified — and its claim is untouched in substance: an epic **lifecycle** move is still a person's
press in the UI, and an **archetype** transition is a different operation that happens to share a
word. `transition_ticket` carries the same ambiguity (it *is* a lifecycle move, and is the one the
cleanup feature owes a note). Nothing was weakened to make room; the new test states the distinction
where a reader of either will find it.

## The `IMPETUS` question, settled

**A ticket requires an impetus at intake and does not owe one afterwards, and the registry says both
now.** `Archetypes` declares `IMPETUS` in `TICKET`'s **`requiredAtCreate`** set and not in its
`required` set; `entities/control/Demand` is which of the two a write is judged against;
`ImpetusConcession` is deleted. Nothing tolerates a violation anywhere any more.

### What was actually wrong

The registry could say "required" or "permitted" and nothing else, so it had one slot for two facts
that genuinely differ:

- **Intake demands an impetus.** A REPORTED ticket *is* an impetus — `TicketService.create` refuses a
  ticket without one and every intake surface supplies it.
- **An existing ticket need not carry one.** V7 made the column nullable on purpose: rows that
  predate it have none, triage may write one onto them, and *clearing* one is behaviour a person has
  (`TicketServiceTest.theClearFlagsAreWhatEmptyTheNullableFields`,
  `TicketApiTest.theClearFlagsAreWhatEmptyTheNullableFields`).

Declaring `IMPETUS` required said the first and contradicted the second, so every update path carried
a named predicate that discarded exactly that complaint after the registry had raised it. **The
disagreement was never between the registry and the column, and never between the registry and the
tests. It was between the registry's vocabulary and the model** — a kind can demand a property once
without demanding it for ever, and there was no way to declare that.

**The served document is where it stopped being a private wrinkle.** `GET
/projects/api/entities/archetypes` publishes `required`, and the SPA gathers a target archetype's
`required` before drawing a transition form. A client reading it faithfully asked a person for an
impetus the server would have accepted the absence of.

### The axis, and why it is the shape that was already here

The document **already had two axes**: `requiredOnTransition` exists because *when* a property is
demanded differs from *whether* the kind has a slot for it — `STATUS` is minted by the writer at
create and must be carried by a transition. Impetus is that observation mirrored, so the settlement
is the same move in the other direction:

| axis | what it answers | where it comes from |
| --- | --- | --- |
| `permitted` | does this kind have a slot for the property at all | declared |
| `required` | must a row of this kind carry it at **every moment** | declared — the invariant, and what every UPDATE is judged against |
| `requiredAtCreate` | must **intake** be given it | declared — `required` plus what a kind needs once and does not owe for ever |
| `requiredOnTransition` | must a transition entry state it | **derived**: `required` + `STATUS` where the kind has status words |

The last is derived because it follows from a kind having a lifecycle at all; the create one follows
from nothing and is therefore declared. `required` ⊆ `requiredAtCreate` ⊆ `permitted` is checked at
class-initialisation time beside the two invariants that were already there — a property owed for
ever but not at birth is a rule no writer could satisfy, since every row is created before it is
updated.

`Demand` is a **parameter of `Archetypes.validate` at every call site and has no default**. A create
judged by the update set would silently stop enforcing intake, which is precisely the failure this
settlement exists to make impossible rather than merely unlikely. Every create path passes
`AT_CREATE`; `TicketService.update`, `TicketService.transition`, the three other services' updates
and **every entry of the multi-entity transition** pass `ON_UPDATE` — a transition creates nothing,
so each entry names a row that already exists and a promotion to `TICKET` with no impetus is accepted
by the registry itself rather than by an exception filtering the registry's answer.

### The one assertion that moved, stated exactly

`ArchetypesTest.aTicketWithoutAnImpetusIsRefusedAndTheRefusalNamesImpetus` asserted that a candidate
`TICKET` carrying a title and a type and no impetus produces exactly one `MISSING_REQUIRED`
violation — **unconditionally, at every moment**. That is the assertion that moved, and it moved in
one direction only:

- **from** "a ticket with no impetus is refused"
- **to** `aTicketFiledWithoutAnImpetusIsRefusedAndTheRefusalNamesImpetus` (the identical candidate and
  the identical one-violation claim, under `Demand.AT_CREATE`) **plus**
  `anExistingTicketWithNoImpetusIsNotRefusedBecauseTheColumnAndThePersonBothAllowIt` (the same
  candidate under `Demand.ON_UPDATE`, no violations).

**The old assertion was incorrect rather than inconvenient**, and the proof is that no code ever
behaved the way it claimed: `TicketService.update`, `TicketService.transition` and
`EntityTransitionService` all accepted that candidate, every one of them by explicitly discarding the
violation this test asserted. It described the declaration, the declaration described only intake, and
the test read it as a rule about all writes. The new pair asserts what the three services do.

Two served lists moved with it, for the same reason and in the same direction —
`ArchetypeRegistryDocumentTest` and `EntityArchetypesApiTest` asserted `TICKET.required ==
[TITLE, STATUS, TICKET_TYPE, IMPETUS]`, which is now `requiredAtCreate`, with `required` (and
`requiredOnTransition`, which is built from it) answering `[TITLE, STATUS, TICKET_TYPE]`. **Nothing
else moved.** In particular the two `theClearFlagsAreWhatEmptyTheNullableFields` assertions are
untouched, and that is the test of the settlement rather than a happy accident: they encode a person
clearing an impetus, which is real product behaviour, and a settlement that had to move them would
have been the wrong settlement.

### The two answers that were rejected, and what killed each

1. **Demote `IMPETUS` to merely permitted.** One word, and it makes the enforced behaviour and the
   registry agree — by giving up the thing the registry is for. Intake enforcement would then live
   only in `TicketService.create`'s `Validations.requireText(impetus, "impetus")` and in whatever each
   surface happens to check, which is exactly the "the rule has to live somewhere and this is that
   somewhere" the registry exists to end. The served document would stop describing intake at all, so
   a client drawing a ticket-intake form from it would render no impetus field and be refused after
   the press — the failure mode the document was built to remove. And it throws away a true statement
   to fix a vocabulary that was too narrow.
2. **Make the column `not null` with a backfill.** This is the one that looks like the tidy answer and
   is the destructive one. It turns a currently-accepted write into a refusal, which means both
   `theClearFlagsAreWhatEmptyTheNullableFields` assertions would have to move — and they encode **real
   product behaviour**: `TicketService.update` offers `clearImpetus` beside `clearDescription` and
   `clearAssignee`, the SPA renders it, and triage clearing a badly-filed impetus is a thing somebody
   does. Moving an assertion that encodes a person's ability to do something is the signal that the
   settlement is wrong, not that the test is. The backfill has no honest value to write either:
   pre-V7 rows have no impetus and inventing one ("(none recorded)") would put a sentence somebody
   never wrote into the field whose whole point is being the words originally used. It would also be a
   fourteenth migration for a constraint the registry can state more precisely than the column can —
   `not null` cannot say "at create", which is the actual rule.
3. **Leave `ImpetusConcession` standing.** Not an answer, just the previous state: a named exception
   is honest about itself but the served document cannot carry it, so the advertised contract stays
   wrong for every client.

**No migration was written and the column stays nullable**, which is the correct state and always
was: `entity.impetus` is nullable because an existing ticket may genuinely have no impetus, and that
is now what the registry says too.

### What the SPA may do with this, and what it need not

**No SPA change is required.** It reads `required` for its transition form, and `required` is now
exactly what an update is judged against, so the form it draws and the write the server accepts agree
where before the form asked for one field too many. `requiredAtCreate` is additive and nothing breaks
by ignoring it. What it *enables*, if somebody wants it later, is drawing the ticket-intake form from
the document rather than from hand-written knowledge that an impetus is mandatory there.

## The nesting rule

`entities/control/Nesting.java` — one place, sitting on the registry, naming no archetype anywhere.

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
annotation under an alias in every file that mentioned it, and `import ...entities.entity.Entity` could
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
intake surfaces already make and the transition API moves; the six words are the ticket's whole
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
change, it is live for every epic and ticket written since the cutover above, and **V10 answers for
it** in the rows that predate it (see "The backfill, as shipped" below): the colliding pair is
detected in SQL, the epic keeps its slug and the ticket's `entity` row is re-slugged, deliberately
and with the branch name it moves written into the deployment log — rather than being discovered as
a migration that will not apply against the live database.

It is accepted because the alternative — folding the archetype into the root scope
(`<projectId>:EPIC`) — buys a collision nobody wants and costs the property that makes the column
readable: **the scope of a row is the id of the thing it belongs to, and nothing else.** If the
backfill finds the collision is common rather than theoretical, that escape hatch is one line in the
writer and one sentence here.

## The backfill, as shipped

`V10__backfill_unified.sql` is the copy and nothing more: four `insert ... select` statements into
`entity`, two into `entity_membership`, and a plpgsql block that decides one ticket slug. It reads
the four old tables and **writes to none of them** — no drop, no rename, no trigger, no column
change, no UPDATE, not even against the row whose slug it spells differently on the other side. They
are the recovery path until the verification door has run clean against live data: `entity` can be
emptied and rebuilt by re-running the file.

**Each archetype is ONE statement, in the order EPIC, TICKET, FEATURE, TASK.** That is a
requirement, not tidiness: `fk_entity_superseded_by` and `fk_entity_depends_on` are self-foreign-keys
and a row may be inserted before the row it names, or in a mutual pair. Postgres fires RI triggers at
the end of the statement, so every reference inside one `insert ... select` resolves whatever the
physical order turns out to be. The memberships come last because both of their keys reach `entity`
across statements.

**`project_id` is derived exactly once, upwards.** A feature inherits its epic's, a task walks
task → feature → epic. Both joins are inner and total (`feature.epic_id` and `task.feature_id` are
`not null` with real FKs), so no row is lost to the join.

### The de-collision rule: the epic keeps, the ticket moves

A colliding epic/ticket pair in one project is detected in SQL — never assumed absent, because the
live database is not the test database — and **the epic keeps its slug**. An epic's slug is the
`slug_scope` of every feature under it and a path segment of `epic/<e>`, `feature/<e>/<f>` and
`task/<e>/<f>/<t>`; a ticket's slug names one thing and one branch, `ticket/<slug>`. Least blast
radius wins.

The ticket's **`entity` row** takes the next free `-2`, `-3`, … under the 40-character cap that was
current when V10 was written (see "The slug cap is 55" above — the live cap is 55 now, and an
applied migration's numbers do not move), with the base trimmed to `40 - length(suffix)` and
trailing dashes stripped from the trimmed head — that is `Slugs.unique`'s arithmetic transcribed, so the result is a slug the writer itself could have minted.
The taken set is every epic slug and every ticket slug in the project **plus every value this pass
has already assigned**, which is what stops two tickets whose slugs trim to one head from both
landing on `-2`; that makes the assignment sequential, hence a `do $$ … $$` block walking the
colliding rows in `(project_id, created_at, id)` order into a `on commit drop` working table the
ticket insert reads through `coalesce(fix.slug, t.slug)`. It is deterministic because it reads only
the immutable old tables in a fixed order.

**The cost, stated rather than hidden:** a ticket whose slug moves may already have work on a branch
cut at `ticket/<old-slug>`, and **that branch does not move**. Nothing here renames a ref and nothing
should. Every re-slug is announced with `raise notice` naming the project, the ticket, the old slug
and the new one, because the deployment log is where an operator learns that a branch name moved.

### Idempotence is `on conflict (id) do nothing`

Flyway will not re-run a V10 that succeeded, so this is about the half-applied deployment that was
retried. `on conflict (id) do nothing` is one statement with no read-then-write window, and it says
"already copied" in the words the primary key already says it: this id is in this table. A
`where not exists` is a weaker second spelling of the same predicate, evaluated at a different
instant from the insert it guards. It is deliberately **not** an upsert: `do update` would let a
retry overwrite a row the settlement or a reader had already touched.

### `entity_membership.id` is the child's id

Not `gen_random_uuid()` — a random id would make every re-run mint a second edge for the same pair,
which the `on conflict (id)` clause cannot see and only
`uq_entity_membership_one_parent_per_child` would catch, as a failed migration rather than a no-op.
The child's id is deterministic and unique by construction, since that constraint already says a
child has at most one parent; and it is the only value in reach that means anything, because an
edge's identity **is** the child — the end of it that can only be in one.

`position` is `row_number() over (partition by <parent> order by created_at, id) - 1`, which is
`Sort.by("createdAt").and("id")` — `FeatureRepository.listByEpic`'s and `TaskRepository.listByFeature`'s
own sort — made dense and zero-based. Never insertion order: a heap scan's order changes with a
VACUUM and between databases, and would produce a plausible dense sequence that silently disagreed
with the listing.

## The cleanup, as shipped

The four old classes and the four old tables are **gone**, and with them the scaffolding that let the
storage move under an unchanged HTTP surface for two releases. This section is the record of what was
deleted, what took its place, and the three questions that had to be decided rather than assumed.

### What went, and what answers in its place

Deleted outright: the entities `Epic`, `Ticket`, `Feature` and `Task`; their repositories
`EpicRepository`, `TicketRepository`, `FeatureRepository` and `TaskRepository`; their mappers
`EpicMapper`, `TicketMapper`, `FeatureMapper` and `TaskMapper`; `control/WorkEntityProjections`; and
the whole verification door — `entities/…/migration/`, `service/…/entities/api/MigrationVerificationController`
and both its test classes.

What answers instead:

| | |
| --- | --- |
| `EpicService`, `TicketService` | the merged `WorkEntity` row, bare |
| `FeatureService`, `TaskService` | `control/Nested` — the row beside its parent's id |
| the four DTOs | `mapper/WorkEntityMapper`, four methods where there were four files |

**`Nested` exists because the parent has nowhere on the row to sit.** `feature.epic_id` and
`task.feature_id` were columns and are an `entity_membership` row; a merged row carries no parent id
of its own, and a row that fetched its own edge would be a query per row on exactly the tree listings
this model makes an N+1 easy on. Every caller has already read the edge — a listing reads the whole
level in one query, a create writes it, a get resolves it once — so handing it back beside the row
costs nothing and asks nothing again. That is the deleted projections' own argument ("the parent is
passed in, never read from the row") surviving the shapes it was written for.

**Not one DTO moved by a byte, which is why the mapper has four methods and not one.** The HTTP
contract is four shapes and stays four shapes; what moved is only where each value is read from, and
`WorkEntityMapper` is where the three merges are read back under their old names —
`supersededByEntityId` → `supersededByEpicId`, `ticketType` → `type`, `implementedAt` →
`implementedOn` on a feature, `dependsOnEntityId` → `dependsOnFeatureId`/`dependsOnTaskId`, and
`epicId`/`featureId` as **parameters** taken from `Nested.parentId()`. The two status methods go
through `EpicStatus.valueOf`/`TicketStatus.valueOf` rather than passing the column straight through,
because `ck_entity_status` spells the union of both lifecycles and an `EPIC` row holding `REPORTED`
satisfies it — the projections refused that at exactly this boundary and the mapper keeps the refusal
where it was.

**Three migration tests went with the tables**, and the third had no choice about it.
`MigrationVerificationTest` is the door's own oracle and goes with the door. The other two —
`UnifiedBackfillMigrationTest` (V10's copy) and `TicketLifecycleMigrationTest` (V7's rewrite) — each
stood at an intermediate Flyway version, wrote rows into `epic`/`ticket`/`feature`/`task` with
hand-written SQL, and read the result back. **Neither was forced out by the drop**, and that is worth
saying plainly rather than implying: they target V10 and V7, where the four tables still exist, and a
fresh database still runs the whole lineage V1 → V13, so V10's backfill still executes against tables
V1 still creates. They were removed with the model they were written about, not because they had
stopped being runnable. **No assertion in them was weakened to keep them alive** — they are absent,
not softened — and restoring either is a small, self-contained thing to do if their coverage is
wanted back. `EntityMembershipMigrationTest` and `EntityNumberMigrationTest` stay untouched: both
write into `entity` and neither ever named an old table.

**`AuditEntityType.of(Archetype)` replaces a hand-written switch.** The four planning words were a
parallel list of the four kinds with a translation written out by hand; they are derived now, so the
kinds are declared once in `control/Archetypes` and `AuditEntityTypeTest` fails the build if an
archetype ever gains a constant without a matching audit word. `TICKET_COMMENT` and `DOSSIER_PAGE`
stay hand-written because they are not archetypes and are not rows in `entity` at all.

### `V13__drop_legacy_planning_tables.sql`

`Task`, `Feature`, `Ticket`, `Epic` — dropped in that order, which is their own V1 foreign keys read
leaves-first, so no `cascade` clause is needed on any drop. `cascade` would have worked and would
have been the wrong instruction to leave behind: it silently drops a constraint somebody added later,
which is the one thing a reader of that file needs to be told did not happen.

It also drops and re-adds `ck_audit_entity_type`. **The permitted set is identical** — `EPIC`,
`FEATURE`, `TASK`, `TICKET`, `TICKET_COMMENT`, `DOSSIER_PAGE`, six words before and the same six
after. **This is not a widening**, and nothing in it permits a value the column did not already
permit. What changed is the Java side above it: the constraint is re-stated so that this file is the
one place a reader goes to see the vocabulary as it stands after the merge, beside the tables whose
names it used to echo. V4 replaced V1's inline unnamed constraint with the named one precisely so
every later statement of it would be an ordinary drop; V5 took that offer and so does V13.

It writes no row, reads no row, renames no column and touches no other table. `entity`,
`entity_membership`, `entity_number_sequence`, `dossier_page`, `dossier_asset`, `dossier_page_asset`
and every column, row and index of `auditentry` are left exactly as they are.

### `TicketComment` stays exactly as it is — and that was checked, not assumed

`V12__owner_keys_to_entity.sql` had **already** dropped and re-added `fk_ticket_comment_ticket`
against `entity (id)`. So by the time this task began the comment thread was keyed on an entity id,
not on a ticket row: there was no key left pointing at the table V13 drops, and therefore nothing to
drop and nothing to rekey. The table needed no migration at all.

**`ticket_id` keeps its name**, and that is a decision on the reasoning V4 already recorded for
`auditentry.epic_id`: renaming a column across an applied lineage and a live log buys a better word
and nothing else. The column holds the same string it always did, the constraint under it is already
pointing at the right table, and every row written since V12 has resolved there. A rename would be a
migration, a re-checksum risk on a lineage people run, an entity class edit and a fresh round of
"which name does this reader expect" — paid for with a word.

`TicketComment` is also not an archetype and must not become one: a comment is a remark on a row
rather than a node of the plan, it has no slug, no number and no membership, and it lives in its own
table. `AuditEntityType.TICKET_COMMENT` says so, which is why that constant stays hand-written beside
the four derived ones.

### Every per-entity endpoint stays, and the reason is a cross-repository check

**No route was removed.** The tempting reading was that the multi-entity transition had retired the
per-entity surface, and it is wrong in two separate ways, both of which were checked against the
deployed SPA (qits-projects-frontend `main`, released 2026.920.846) rather than reasoned about:

- **The SPA still calls both per-entity PUTs for ordinary edits.** `PUT /projects/api/epics/{id}` is
  how the refining page inserts an image (`refining-page.ts:769`) and `PUT /projects/api/tickets/{id}`
  is how a ticket is edited (`ticket-detail-page.ts:964`). The transition replaced the per-entity
  **UPDATE** surface only in the sense that it *can* express the same write as a map of one; nothing
  has moved onto it, and removing either PUT would break the product today.
- **The transition creates nothing and deletes nothing.** "Existing ids only, and refusals are
  collected rather than thrown" is the rule and it is absolute — an id naming no row is a violation,
  never a create. So the transition never stood in for `POST` or `DELETE` on any archetype, and could
  not have, whatever the SPA did.

`POST /epics/{id}/transition` and `POST /tickets/{id}/transition` also stay, and conflating them with
the archetype transition would break the product. Those are **lifecycle** moves — a status along
`REFINING → IMPLEMENTATION → …` or `REPORTED → REFINED → …`, judged by `EpicLifecycle` and
`TicketLifecycle`, one step at a time, with a supersede that spawns a successor draft and a phase
that starts itself. `POST /entities/transition` moves archetype and membership and judges a
post-state. The two share a word and nothing else.

**The routes the SPA no longer calls are kept deliberately**, as a retained follow-up rather than as
dead code: `POST /projects/{id}/epics`, `DELETE /epics/{id}`, `POST /epics/{id}/features`, `PUT` and
`DELETE /features/{id}`, `POST /features/{id}/tasks`, and `PUT` and `DELETE /tasks/{id}`. They are
still reached by this repository's own REST suite, and the MCP planning tools (`propose_epic`,
`add_feature`, `update_feature`, `remove_feature`, `add_task`, `update_task`, `remove_task`,
`mark_task_implemented`) call the same services those routes call — so the create and delete paths
are live code with live callers whatever the browser does. Retiring an endpoint is a contract change
against a generated client and belongs to a task that says so.

### The evidence, and where it is kept

The verification door was run against **live data** on **2026-09-20T01:32:01Z**, against deployed
**2026.920.12314** — the release that shipped the door for exactly this moment:

    verdict: CLEAN, discrepancies: 0

- **309 old rows against 309 entity rows** — `EPIC` 17/17, `TICKET` 84/84, `FEATURE` 66/66, `TASK`
  142/142, surplus 0.
- **208 membership edges** compared, for parent and for relative order.
- **All ten `DISCREPANCY` categories: 0 findings.**

The full report is committed at **`docs/migration-verification-2026-09-20.json`**, so it outlives the
door that produced it. That is the whole point of keeping it: the door is deleted, the tables it
compared are dropped, and the run can never be repeated — so the artifact is the only remaining
answer to "what was the evidence". A verdict quoted in prose is a claim; the report is the material
to disagree with it, which is the shape that section argues for at length.

### One consequence, stated rather than left to be discovered

**`auditentry.snapshot` now serializes the merged `WorkEntity`.** It used to serialize whichever of
four shapes the row was, because the projections were what the services handed the audit writer. Those
classes are gone, so there is nothing left to serialize the old form from — the new snapshot is the
merged row, and that is a consequence of the deletion rather than a choice made beside it.

Concretely: a feature's snapshot carries `implementedAt`, `dependsOnEntityId`, `archetype` and
`slugScope` where it used to carry `implementedOn`, `dependsOnFeatureId` and `epicId`. **Historical
rows keep the old shape**, because an audit row is a record of what a write saw and is never
rewritten — so the log genuinely holds two shapes, split at this release, and a reader of it has to
know that.

It is not repaired and should not be. Backfilling the column would mean rewriting history to say
something the write never saw; keeping a fifth class alive purely to shape a JSON blob would be the
half-live artifact this whole merge exists to stop. What the snapshot is for — "what did this row
look like when somebody changed it" — is answered correctly by both shapes.

### The "epics" vocabulary rename, as shipped

**The word `epic` stops naming the container concept and names only an archetype.** This was the
last task of the epic and it was deliberately last: a rename touching nearly every import is the
change most likely to hide a real regression, so it does not share a commit with V13's deletion of
the four old entities — that shipped on its own, after the verification door ran clean.

**It is a rename and nothing else.** No route, no DTO field, no JSON name, no MCP tool, no table, no
column, no migration and no deployment variable moved. `docs/openapi.yml` is byte-identical, which
is the check: a diff there would have meant something was changed that should not have been.

| was | is | why |
| --- | --- | --- |
| the `epics/` maven module, `qits-projects-epics` | `entities/`, `qits-projects-entities` | the module holds one `entity` table and four archetypes; `entity`/`entity_membership` is the vocabulary that won |
| `eu.wohlben.qits.epics.*` (in `entities/` **and** `service/`) | `eu.wohlben.qits.entities.*` | plural, because the module's own JPA sub-package is `…entity` and `entity.entity` would be unreadable |
| `service/…/projects/epicshost/` | `service/…/projects/entitieshost/` | a `*host` package is named for what it bridges into, so it moves with the module |
| `EpicsPrincipal`, `EpicsExceptionMapper`, `EpicsException` | `EntitiesPrincipal`, `EntitiesExceptionMapper`, `EntitiesException` | module-named infrastructure — the module's name changed, these follow (the mapper and the exception it maps are one decision) |
| `EpicsTestSupport`, `EpicsEmbeddedPgConfigSource`, `EpicsAuditIdentityTest` | `Entities…` | same rule, test side. The config source still writes `quarkus.datasource.epics.*`; only the class is module-named |
| `EpicChangeHints`, `TicketChangeHints` | `EpicsTopicHints`, `TicketsTopicHints` | **named for the SSE topic each fires, not for an entity kind.** `Topic.EPICS`/`Topic.TICKETS` reach the wire lowercased and the SPA subscribes to them, so the topics did not move and the beans now track them. The old singular names read as "hints about an Epic" while `EpicsTopicHints` in fact resolves EPIC, FEATURE and TASK rows of one table |
| `qits.epics.read-deadline`, `qits.epics.write-deadline` | `qits.entities.*` | the module's own config namespace. Neither is declared in `.config/qits/configuration.yml`, so nothing on the platform injects either and both fall back to their shipped 15S default |

**What was checked and deliberately left, because "not mentioned" and "checked and correct" are
indistinguishable to a later reader:**

- **Every MCP tool keeps its name and shape** — `list_epics`, `get_epic`, `propose_epic`,
  `update_epic`, the ticket, feature, task and dossier tools, and the `transition_entities` /
  `list_entities` pair this epic added. They are called by name from prompts written down outside
  this repository; renaming them breaks every one of those silently, for no gain here. They are
  deprecated in their own epic, and leaving them untouched is the shorter path to the same end. The
  server is still `@McpServer("repository")`, which qits-workspace-daemon addresses by name.
- **Every REST route, DTO field and JSON name** — `/projects/api/epics/…` and
  `/projects/api/tickets/…` are the deployed contract and the SPA calls them.
- **The SSE topics** `epics` and `tickets`. `ProjectEventBroadcaster` lowercases
  `ProjectChangeHint.Topic`'s enum name straight onto the frame, so the enum constant *is* the wire
  word. This is the one place in the rename where a name that reads "epic" reaches a client.
- **The database, and it is not code.** Table and column names, the Flyway location
  `classpath:db/epics/migration` and its applied lineage, the datasource and persistence unit named
  `epics`, `QITS_RESOURCE_EPICS_URL`/`_USERNAME`/`_PASSWORD`, the resource `postgresql:epics:qits_epics`
  in `.config/qits/deployments.yml`, and `HibernateBeanContainerWarmup.Epics` (which names that persistence
  unit). Changing any of it is a deployment-breaking change well beyond a rename. **The maven module
  and the datasource being the same word was a coincidence, and it is not collapsed here**: the
  module directory moved and the Flyway location did not, so `entities/src/main/resources/db/epics/migration/`
  is the deliberate result and not an oversight.
- **`AgentSurface`'s `project.epics`** (`AgentSurfaceDefaults.PROJECT_EPICS`) — a session-surface
  key shared with qits-projects-daemon and persisted in the agent-configuration document every
  container is born holding. It names a screen, it is cross-repository, and it cannot be renamed
  from here.

**Two transitions now mean two different things, and both say so in code.** `transition_ticket` is a
**lifecycle** move (one ticket, one adjacent step, `entity.status` alone, judged by
`TicketLifecycle`); `transition_entities`/`EntityTransitionService` is an **archetype** move (what
kind a row is and whose child it is). They share a word and share nothing else, so each carries a
javadoc note naming the other — without it every future reader conflates them.

## The verification door, as shipped — and as deleted

`GET /projects/api/entities/migration-verification` — **admin only, temporary, and deleted by V13
with the tables it compared.** It put the four frozen old tables beside the unified model on whatever
database the running process was pointed at and answered **every discrepancy it found**. A clean run
against live data is the evidence that authorises V13; nothing else is.

**It ran, it came back clean, and it is gone.** The run and its report are recorded under "The
evidence, and where it is kept" in "The cleanup, as shipped" just above. Everything in this section is
written in the present tense because it is the record of a door that existed and of what it asserted
— read it as the specification of the evidence, not as a route you can still press. The whole of
`entities/…/migration/`, `service/…/entities/api/MigrationVerificationController` and both test classes
went in the same change as V13: its own `package-info` carried the instruction, and a door whose
comparison target no longer exists can only ever answer about nothing.

It existed because every migration test in this repository starts from a database a test seeded,
which proves the statements are right about the rows a test wrote and says nothing at all about
three months of real planning. So it had to be a door on the running process, runnable on demand,
repeatedly, against production, with no deployment.

### What it compares

| category | kind | the question |
| --- | --- | --- |
| `archetype-census` | informational | how many rows each old table holds against how many entity rows carry that archetype — always listed |
| `archetype-counts` | **discrepancy** | `entity >= old` per archetype. Never equality; see below |
| `missing-entities` | **discrepancy** | every old row has an entity row under the same id |
| `property-round-trip` | **discrepancy** | one row per differing property: title, slug, slug_scope, description, status, project_id, the epic's superseded-by, the ticket's type/impetus/assignee/created_by, the task's repository_id, and both merged markers (`implemented_on`/`implemented_at`, `depends_on`) |
| `membership-parents` | **discrepancy** | every `feature.epic_id` / `task.feature_id` has an edge naming the same parent |
| `membership-order` | **discrepancy** | the siblings' **relative order** matches `(created_at, id)` — its own category, never folded into the property check |
| `orphaned-memberships` | **discrepancy** | no edge names a parent or child that is not an entity |
| `rootless-entities` | **discrepancy** | every FEATURE and TASK has an edge (EPIC and TICKET are roots — `ArchetypeSpec.mayBeRoot`) |
| `dangling-owner-references` | **discrepancy** | `dossier_page.epic_id`, `dossier_page.ticket_id`, `dossier_asset.epic_id`, `ticketcomment.ticket_id` all resolve in `entity` |
| `dangling-audit-references` | **discrepancy** | audit entries naming a row an old table **still holds** and `entity` does not |
| `work-branches` | **discrepancy** | the branch name the old tables imply equals the one the unified model implies, for all four archetypes |
| `entities-created-since-the-cutover` | informational | entity rows with no old row at all |
| `expected-de-collided-slugs` | expected | the tickets V10 re-slugged, with both values |
| `expected-changed-since-the-cutover` | expected | properties that differ on a row written since the copy |
| `expected-reparented-since-the-cutover` | expected | edges whose parent moved since the copy |
| `expected-deleted-since-the-cutover` | expected | old rows removed through the unified model, per the DELETE audit entry |

**It answers the discrepancies themselves and never a boolean.** Every category is present on a
clean run, each carrying how many rows of what kind it compared, and the response's `scope` block
states in words what was deliberately not checked. The operation this authorises is destructive and
irreversible, so what a person needs is not "yes" but the material to disagree with "yes" — and a
count of zero beside a compared count of zero is a vacuous pass, which this shape makes visible
rather than reassuring. Each category lists at most 100 rows with the **full** count beside it, so a
truncated list is never mistaken for a short one and a broken estate cannot exhaust the service.

### What it deliberately does NOT check, and why

**1. FORWARD ONLY.** The epic asked for both directions — every old row has an entity, *and* no
entity has an id no old row had. The second half was correct when it was written and has been false
since `FeatureService`/`TaskService` stopped writing legacy rows; with the mirror retired by V12 it
is false for all four archetypes. "What this means for the verification door" above is the standing
statement of it. **The reverse assertion is not made anywhere**, and the omission is on the wire
(`scope.direction`) rather than only in javadoc, because a person reading a clean result has to know
which half of the comparison it was.

**2. The V10 slug de-collision is expected, and is reported.** A ticket whose slug collided with an
epic's in the same project had its **entity** row re-slugged and its `Ticket` row left alone. The
door detects those rows exactly the way V10 chose them — a ticket whose slug equals some epic's slug
in the same project, read off the **old** tables, so the predicate cannot drift with the new model —
and reports them under `expected-de-collided-slugs` with both values, naming the epic and the branch
that does not move. They are exempt from the slug property comparison and from the work-branch
comparison, and from nothing else. Silently ignoring them would hide the one consequence that
matters: `ticket/<old-slug>` still has somebody's work on it.

**3. Counts are a relation, not an equality.** Anything created since the cutover has no old row, so
`entity >= old` per archetype is what is asserted and the surplus is listed as evidence under
`entities-created-since-the-cutover`. Only the wrong direction fails.

**4. The model has been written the whole time** — which the epic did not name and which matters as
much as the other three. A row *edited* through the unified model legitimately disagrees with its
frozen old row, and a row *deleted* through it legitimately has no entity row. `entity.updated_at`
is the discriminator for the first (V10 copies it verbatim; every later write bumps it) and a DELETE
audit entry is the discriminator for the second (the doc's own wording, and the log is deliberately
not foreign-keyed back so that it outlives the row). Both are reported as expected differences
carrying both values. **The cost, stated rather than hidden:** a genuine backfill defect on a row
that has since been edited is misfiled into the expected category. It is still listed, with both
values; what is lost is the failing verdict, not the evidence.

**Ordering is compared as relative order and never as absolute position**, and that is not a
weakening: `EntityMembershipRepository.closeGapAfter` renumbers a removal's tail with a bulk update
that leaves no timestamp behind, so absolute positions drift the moment anything is deleted with
nothing to discriminate on — while the *order* of the surviving siblings is exactly what V10
promised and exactly what a listing draws.

**No work branch is stored in this database.** `control/WorkBranches` derives it from slugs and
ancestry, so what the door compares is the derived name on each side — which folds in the ancestry
as well as the slug and is therefore more than the property check. The stored `refinement.branch` is
in the **projects** database, which this door holds no connection to (the entities module depends on
`domain` nowhere); that is named in `scope.notChecked` along with everything else a reader might
reasonably assume was covered.

### How to run it against live data

It is a `GET`, it issues `select` and only `select`, and it stores nothing — so it may be pressed as
often as anybody likes. From inside the platform network, against the service's own alias, with the
forward-auth pair the edge strips from every inbound request (which is what makes it trustworthy
there and unusable from outside):

```sh
curl -sS -H 'X-Qits-User: <you>' -H 'X-Qits-Roles: qits:admin' \
     http://dev-qits-projects:8080/projects/api/entities/migration-verification | jq .

# the one-line answer, and the categories that found something
curl -sS -H 'X-Qits-User: <you>' -H 'X-Qits-Roles: qits:admin' \
     http://dev-qits-projects:8080/projects/api/entities/migration-verification \
  | jq '{verdict, discrepancies,
         failing: [.categories[] | select(.kind=="DISCREPANCY" and .findings>0)
                                 | {name, findings, compared, comparedRows}]}'
```

An administrator in a browser reaches the same route on the projects host under their own session.
**Do not forge a role you do not hold** — it is privilege escalation, not a workaround, and this
door is `qits:admin` precisely because it is an operator's gate. A machine bearer does not open it:
it carries `qits:system` or `qits:agent` and answers 403.

**Read `scope` before `verdict`.** A clean verdict is a statement about the copy and about nothing
else.

### Why admin alone, with no `qits:agent`

This is the only GET on this surface an agent does not hold, so the exception is argued rather than
assumed. It is an operator's gate on a destructive migration; it reads whole frozen tables —
sixteen set-based scans of the whole estate per press, where every other read an agent holds here is
a bounded indexed query about the agent's own work; and no agent workflow has any use for a column-by-column
comparison of a copy it never saw. The class was therefore **deliberately outside
`AgentReadAccessTest`'s explicit `CLASSES` list**, and that test's javadoc named it and this reason —
because a class silently missing from that list and a class deliberately kept off it look identical.
No assertion in that test moved, then or at the deletion: the paragraph now records that the
exception went with the door, and every read controller on the surface is in the list again.

### What it costs

Sixteen statements, every one set-based, **not one query per row**. The checks are anti-joins and
left joins against `entity(id)` and `entity_membership(child_id)`, both unique indexes, so
postgres hashes or merges the old table against an index rather than probing per row; the dominant
cost is one sequential scan of each old table per category that reads it, and the whole run is
O(estate) with a small constant. Memory is bounded by construction: every finding query carries
`count(*) over ()` beside a `limit`, so one statement answers both "how many" and "the first N" and
at most 100 rows per category are ever materialised.

The run is **not one snapshot and does not pretend to be** — statements are READ COMMITTED and each
sees its own instant. That is harmless here, and why is the point: the four old tables have had no
writer for several commits and cannot move at all, and the only table that can move is `entity`,
where a row arriving mid-run lands in an informational category rather than a failing one. Buying a
repeatable-read snapshot would mean a long transaction on a live database to remove a discrepancy
that cannot occur.

### What proves it

`entities/src/test/…/migration/MigrationVerificationTest` — plain JUnit over `EmbeddedPg` + Flyway,
`UnifiedBackfillMigrationTest`'s shape and no `@TestProfile`, over **two estates built once and
compared once**. The clean one is produced by running V10 itself rather than by hand-writing what a
correct copy looks like: a hand-built fixture would be a second implementation of the backfill, free
to be wrong in the same direction as the door. The broken one carries one named defect per category
— a missing entity, each property in turn, a wrong parent, a permuted order, an orphaned edge, a
rootless descendant, four dangling outward references, a dangling audit row, a moved branch — plus
the three findings as their own cases: a row created after the cutover is not a discrepancy, a
de-collided slug is expected rather than a mismatch, and counts unequal in the right direction are
clean while the wrong direction fails.

Two of those defects **cannot be seeded while the schema stands** — V9's membership foreign key and
V12's four repointed owner keys forbid them outright — so the test drops those constraints in its
own database. The door checks them regardless, and that is deliberate: "the schema forbids it" is a
claim about one database's current DDL, not about the rows in front of you, and this door also runs
against a database restored from a dump or repaired by hand. The constraint is the mechanism; the
check is the evidence.

`service/src/test/…/entities/api/MigrationVerificationApiTest` is the other half, a plain `@QuarkusTest`
with no profile: it proves the persistence unit hands out a usable connection outside any caller's
transaction, that twenty statements of hand-written SQL parse against the schema Flyway actually
builds, and that the record tree serialises. It asserts CLEAN over a database where the old tables
are **empty** and `entity` is full — which is the shape the three findings are about, and exactly
what a door written with the reverse assertion would report as broken.

## Where the code is

| | |
| --- | --- |
| migrations | `entities/src/main/resources/db/epics/migration/V9__entity_membership.sql`, `V10__backfill_unified.sql`, `V11__entity_number.sql`, `V12__owner_keys_to_entity.sql`, `V13__drop_legacy_planning_tables.sql` |
| entities | `entities/…/entity/Archetype.java`, `WorkEntity.java`, `EntityMembership.java` |
| repositories | `entities/…/persistence/WorkEntityRepository.java`, `EntityMembershipRepository.java` |
| the registry | `entities/…/control/Archetypes.java`, `ArchetypeSpec.java`, `EntityProperty.java`, `EntityState.java`, `ArchetypeViolation.java` |
| the five cut-over services | `entities/…/control/EpicService.java`, `TicketService.java`, `FeatureService.java`, `TaskService.java`, `DossierService.java` — answering `WorkEntity` and `control/Nested.java`; `WorkEntityProjections.java` is **deleted** |
| the lifecycle guards | `entities/…/control/EpicLifecycle.java` — every caller hands it the `entity` row itself |
| the one mapper | `entities/…/mapper/WorkEntityMapper.java` — `toEpicDto`/`toTicketDto`/`toFeatureDto`/`toTaskDto`, replacing `EpicMapper`, `TicketMapper`, `FeatureMapper` and `TaskMapper`, all four **deleted** |
| the four old entities and their repositories | **deleted** with their tables (V13): `entities/…/entity/Epic.java`, `Ticket.java`, `Feature.java`, `Task.java`; `entities/…/persistence/EpicRepository.java`, `TicketRepository.java`, `FeatureRepository.java`, `TaskRepository.java` |
| the nesting rule | `entities/…/control/Nesting.java`, `EntityFact.java`, `EntityFacts.java`, `StoredEntityFacts.java`, `NestingViolation.java` |
| the multi-entity transition | `entities/…/control/EntityTransitionService.java`, `EntityTransition.java`, `TransitionedEntity.java`, `TransitionAnnouncer.java`; `service/…/entities/api/EntityTransitionController.java` |
| the registry, served | `entities/…/control/ArchetypeRegistryDocument.java` — derived from `Archetypes` at read time, and the `SERVER_OWNED` / `requiresStatusOnTransition` pair on `EntityTransitionService.java` it reads; `service/…/entities/api/EntityArchetypesController.java` — `GET /projects/api/entities/archetypes`, `qits:admin` + `qits:agent` |
| the merged read | `entities/…/control/EntityCatalogService.java` — `listByProject`/`byIds`, answering `TransitionedEntity` |
| the agent surface | `service/…/projects/mcp/EntityMcpTools.java` — `transition_entities` + `list_entities`, registered in `ReadOnlyRepositoryToolFilter` |
| the qualified form | `service/…/projects/api/QualifiedEntityIds.java` — the only renderer; `domain`'s `ProjectRepository.list(ids)` / `ProjectService.slugsByIds` behind it |
| the commit-subject parser | `service/…/projects/entitieshost/CommitSubjectEntities.java` — the only reader of the grammar; `entities/…/persistence/WorkEntityRepository.findByProjectAndNumber` behind it |
| the verification door — **DELETED WHOLE BY V13** | was `entities/…/migration/` (`package-info.java` carried the deletion instruction; `MigrationVerification.java` the comparison, `VerificationReport`/`VerificationCategory`/`VerificationFinding`/`VerificationScope` the answer, `MigrationVerificationService.java` the CDI bridge) and `service/…/entities/api/MigrationVerificationController.java` — `GET /projects/api/entities/migration-verification`, `qits:admin` alone. Its one clean run against live data survives it, at `docs/migration-verification-2026-09-20.json` |
| the create axis | `entities/…/control/Demand.java` — `AT_CREATE`/`ON_UPDATE`, a parameter of `Archetypes.validate` at every call site; `ArchetypeSpec.requiredAtCreate`/`requiredFor`. `ImpetusConcession.java` is **deleted**; see "The `IMPETUS` question, settled" |
| the transition's event | `service/…/projects/bus/EntityTransitioned.java`, `EntityTransitionAnnouncer.java`, registered (with its nested payload record) in `EventWireReflection.java` |
| tests | `entities/src/test/…/control/ArchetypesTest.java`, `NestingTest.java`, `UnifiedDescendantsTest.java`, `EntityTransitionServiceTest.java`, `RecordingTransitionAnnouncer.java`, `DossierServiceTest.java`, `DossierTicketOwnerTest.java`, `WorkBranchesTest.java`, `ArchetypeRegistryDocumentTest.java`; `…/persistence/WorkEntityPersistenceTest.java`; `…/entity/AuditEntityTypeTest.java`; `…/migration/EntityMembershipMigrationTest.java`, `…/migration/EntityNumberMigrationTest.java` (the three that wrote rows into the four old tables — `UnifiedBackfillMigrationTest`, `TicketLifecycleMigrationTest`, `MigrationVerificationTest` — went with those tables, and `service/src/test/…/entities/api/MigrationVerificationApiTest.java` with the door); `service/src/test/…/entities/api/EntityTransitionApiTest.java`, `service/src/test/…/entities/api/EntityArchetypesApiTest.java`, `service/src/test/…/projects/mcp/EntityMcpToolsTest.java`, `service/src/test/…/projects/api/QualifiedEntityIdsTest.java`, `service/src/test/…/projects/entitieshost/CommitSubjectEntitiesTest.java` |

The rule tests are plain JUnit and boot no application: a `@TestProfile` is a whole Quarkus app at
roughly 125 MB of retained metaspace inside a 4 GB CI step, and rules that are pure functions should
cost none of it. The persistence test is the one that has to be a `@QuarkusTest` — Flyway owns the
DDL and `database.generation` is `none`, so a column name that disagrees with V9 is invisible until
the first query — and it adds no profile of its own.
