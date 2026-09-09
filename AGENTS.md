# qits-projects-service — working notes

Read `README.md` first: it defines the boundary and lists the ports. This file is the working
conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials. `./mvnw verify` is the gate. Anything that would break that
is not a tradeoff to weigh; it is the thing this repo exists to avoid.

That is why the poms duplicate versions instead of inheriting them, why the git fixtures are built
at test time instead of checked out as submodules, and why every reach into another context is an
optional port rather than a dependency.

**`service/` compiles to a GraalVM native image**, the same rule qits-workspace-daemon and
qits-gateway carry, and it extends the clone-alone rule rather than qualifying it: `.sdkmanrc` names
`25.0.2-graalce`, so `sdk env` gives you a `native-image` and `./mvnw package -Dnative` produces
`service/target/qits-projects` with no container involved.

Two consequences worth stating before you reach for a dependency:

- **A missing GraalVM does not fail the build.** Quarkus logs `Cannot find the native-image …
  Attempting to fall back to container build` and shells docker with a 1.8 GB Mandrel image. Green
  either way, so the fallback is easy to be in without noticing — recognise it by the image pull.
- **Every dependency is a decision about what the builder has to be told.** Reflection, dynamic
  proxies, `ServiceLoader`, resource loading by computed name and JNI/JNA all need registering, and
  the failure lands at *runtime* in the binary while the JVM suite stays green. Prefer what is
  already in the image — `ProcessBuilder` over a process library, `java.lang.foreign` over JNA.

That second point is not hypothetical here: it is why the sign-in terminal no longer uses pty4j.
pty4j is JNA plus per-platform `.so`s unpacked from its own jar at runtime, none of which the image
builder can see; `ForeignPty` is six libc calls through `java.lang.foreign`, with git put on the
slave device by `setsid --ctty` — so `setsid` (util-linux) is now a host requirement alongside git.

**FFM is not free of registration either, whatever you may have read.** GraalVM 25 registers zero
downcall stubs on its own — hoisting the `FunctionDescriptor`s into `static final` constants does
not help, and neither does building them inline; both were measured and both report `0 downcalls …
registered for foreign access`. The build then *fails*, parsing `ForeignPty.open` with `unexpected
input could not be handled: linkToNative`. What makes it work is two things that must both be
there and that nothing else substitutes for:

- `domain/src/main/resources/META-INF/native-image/eu.wohlben.qits/qits-projects-domain/reachability-metadata.json`
  — one entry per **distinct** descriptor, in canonical layout names, `firstVariadicArg` included
  for `ioctl`. Change a signature in `ForeignPty` and this file changes with it.
- `quarkus.native.additional-build-args=--enable-native-access=ALL-UNNAMED` in the service's
  `application.properties`, which permits the restricted calls. Without it the binary still works
  but warns on every sign-in, and the warning says it will become a refusal.

## Package and module conventions

`eu.wohlben.qits.projects.*` across `domain/` and `service/`, with disjoint sub-packages so there is
no split package, plus `eu.wohlben.qits.epics.*` in `epics/`:

- `domain/` — `entity`, `persistence`, `dto`, `mapper`, `control`, `error`, `validation`.
  Framework-free in the sense that matters: no JAX-RS, no websockets. Entities are Panache
  active-record with public fields; mappers are MapStruct `@Mapper(componentModel = "jakarta")`.
- `service/` — `api` (JAX-RS + the remote-login websocket), `mcp` (the `repository` MCP server),
  `startup`, `wiring` (the git host's lifecycle client — `HttpGitHostRepositories`, a
  `java.net.http.HttpClient` as an instance field). There is **no `notify` package any more**: its
  one class implemented `ProjectDomainRegistrar` against qits-platform-dns, that service is gone from
  the platform, and the port is an unimplemented hook now — so this repo implements no creation port
  at all. Bring the package name back (qits-ci-service's `ci.notify` idiom) if a fire-and-forget notifier
  ever returns; `wiring` is deliberately not it, because a repository create is waited on and can
  fail the caller's request.
- `service/…/containershost/` — the orchestrator client: the `ContainerRuntime` implementation, the
  producer that gives the jar its bean and its bearer, and the native-image registration. It is an
  *adapter* like `wiring` and `bus` are — the seam is `agenthost/ContainerRuntime`, and
  what lives here is only what a deployable owes a plain jar. See "The container orchestrator".
- `service/…/idphost/` — qits-idp's commission API, the same adapter shape one directory over: the
  seam is `agenthost/AgentCredentials` and the whole of what lives here is one `@DefaultBean` HTTP
  client. See "The commissioned credential".
- `service/…/workspacehost/` — qits-workspaces, and `@DefaultBean` HTTP clients again. A package of
  its own rather than classes in `releasehost/` because **neither is a release verb** —
  qits-workspaces' release door left on 2026-09-03 and stays gone; what travels here are
  workspace-lifecycle facts and asks. **Two seams, one per class, and the split is the failure
  contract rather than the address:**
  - `control/ReleasedBranchWorkspaces` → `HttpReleasedBranchWorkspaces`: the POST a release makes,
    after it has already landed, to say that a branch it deleted is gone. Fire-and-forget, **never
    throws**.
  - `control/WorkspaceAgentDispatch` → `HttpWorkspaceAgentDispatch`: the POST a ticket's "Assign
    agent" makes to `/workspaces/api/agent-dispatches`, which stands an aggregate workspace on
    `ticket/<slug>` and launches an agent in it. A **request somebody is waiting on**, so it throws
    a `DomainException` — 502 for the exchange, 503 for a hop with no address or no credential.

  That is why the second one is a new class and not a second method on the first: two verbs with
  opposite failure contracts do not share a class, and the standing rule stays — do not grow a verb
  onto `HttpReleasedBranchWorkspaces` on the grounds that the address is configured again. The
  address itself is `qits.projects.workspaces-url`, shipped **unset** and falling back to
  `qits.projects.release-requests.workspaces-url`, which already ships set and is what every
  environment injects; the dispatch reads the honest name and costs no configuration change to reach
  it.
- `service/…/maintenancehost/` — qits-maintenance, the same `@DefaultBean` HTTP-client shape once
  more: the seam is `control/DownstreamComponents` and the whole of what lives here is ONE GET,
  `/maintenance/api/repositories/{repoId}/downstream`, asked at fold time so
  `ReleaseRequestChanged` can carry what is built on top of the repository. It is addressed by this
  service's own repository row id, which is qits-maintenance's `catalogId`. Everything about it is
  advisory: unset, unreachable, refused and 404 are one behaviour — a WARN and `Optional.empty()`,
  which becomes an absent event key a consumer reads as "unknown". A 200 with an empty array is a
  different answer (a leaf, `[]` on the wire) and the two must never be collapsed.
- `epics/` — untouched by the extraction beyond its `<parent>`: its own package, its own error
  types, its own datasource, its own physical database (`qits_epics`) and its own Flyway lineage. It
  depends on neither `domain` nor any auth module, and it should stay that way — it is the module
  most likely to be lifted out next, and lifting it out now moves a database rather than tables out
  of somebody else's.

`control/` is flat. The monorepo split this code across `domain.project.*`, `domain.repository.*`
and `domain.seeding.*` to break cycles that do not exist here.

## Paths

**The client is served at `/`** on this service's own host (`projects.<env>.<domain>`); every
machine surface keeps the segment `/projects` — see the table in the README. Three things about
that are easy to get wrong:

- **`@WebSocket` does not follow `quarkus.rest.path`.** The remote-login socket spells
  `/projects/api/...` out as a literal and has to be kept in step with the key by hand. Anything new
  registered straight onto the Vert.x router is the same.
- **The MCP server's name is not its path.** It is mounted at `/projects/mcp` and is still *named*
  `repository` (`@McpServer("repository")`), because qits-workspace-daemon addresses it by name.
  Renaming it breaks the daemon; an *undeclared* name stops the process booting outright.
- **A machine surface outside `/projects` needs a line in
  `quarkus.quinoa.ignored-path-prefixes`.** Quinoa's SPA fallback is a catch-all at `/*` now, so a
  real route still wins — but a path matching *no* route is rerouted to `index.html` and answers
  `200 text/html`, which a machine client parses as data. The values are **absolute request paths**
  since `ui-root-path` became `/` (they used to be tails relative to it, which is why the old list
  read `/api,/q,/mcp,/daemon,/container`), so the whole list is one prefix: `/projects` covers
  `/projects/api`, `/projects/q`, `/projects/mcp` and both daemon harnesses. This service owns no
  route outside that segment; one that did — `/v2`, `/git` — would list it beside. Setting the key
  **replaces** Quinoa's derivation rather than extending it, and the derivation never named the MCP
  root-path or the harnesses' raw routes anyway.

Path parameters naming a repository are `{repoId}` everywhere. Parameter names are visible in the
generated client, so keep them uniform. Note the two reconciles under a project are deliberately
different routes: `POST /{projectId}/reconcile` re-asserts the dns record, and
`POST /{projectId}/repositories/reconcile` reconciles the project's repositories against its
wrapper.

## The event bus

`service/…/bus/` is the whole of the bus's **SEAMS**. The machinery is the published
`qits-eventstream` jar; the consumed vocabulary is `qits-githost-events`, the git host's four
records. Its rules live in that library's own repository and are not restated here.

**This service publishes exactly one event, and it stopped being consume-only for it.**
`RepositoryRenamed` — projectId, repositoryId, oldName, newName, occurredAt — announced by
`bus/RepositoryRenamedAnnouncer` over the `control/RepositoryAnnouncer` port, from
`RepositoryService.rename`. It publishes because a rename is the one thing this service knows that
nothing else can derive: the bare does not move (it is keyed by the row's id), so a consumer holding
a stale `(projectId, repoName)` pair has no way to notice. Four things travel with that and each is
a rule rather than a detail:

- **The event class lives in `service/…/bus/`, not in a published vocabulary module.** Nothing
  consumes it yet, and a jar this platform's Maven registry does not serve is a build that resolves
  from a developer's `~/.m2` and fails in a release pipeline's step container. A consumer decodes it
  with `CanonicalJson.payloadTo` into a local record of its own, which is the platform's standing
  answer. Publishing it as a jar is a decision to make when a second repo needs the type.
- **It is registered in `EventWireReflection`.** The envelope was already there for the day
  something first published; the payload record is what turns that prediction into a line, and its
  absence is qits-ci's measured failure — every publish dying inside `CanonicalJson` with "no
  serializer found", the JVM suite green throughout.
- **The announcement is made AFTER the rename's transaction, never inside it.** That write is
  `DbRetry.inNewTx` and its body re-runs on a retry; an announcement in it would be made twice.
- **The port is optional and the adapter is `@DefaultBean`**, so the suite's
  `RecordingRepositoryAnnouncer` wins the injection and no test reaches the bus.

**The word is SEAMS now, not "the bus", and the narrowing was deliberate (2026-08-10).** The
eventstream jar also carries the platform's causation *persistence vocabulary* — `CausedRow`,
`CausationStamp`, `@Uncaused`, three jakarta-persistence-shaped types with no publish, no subscribe
and no wire in them — and six entities implement it, so the jar sits in `domain`'s and `epics`' poms
now. That is not a crack in `epics`' "depends on `domain` and on `auth/*` not at all": a lift-out of
that module takes three annotations along in one jar and no CDI graph. What the rule still forbids
is control flow — no listener, no publisher, no `EventFrame`, no `QitsEventBus` outside
`service/…/bus/`. What the dependency costs is honest and paid in the suites: the jar's persistence
unit boots in both modules' tests too, so each `testdb/*EmbeddedPgConfigSource` feeds it a database
of its own, the test properties keep the bus dark, and — the surprise — both modules now open an
HTTP server they never wanted, because vertx-http rides in with the jar. That is what
`quarkus.http.test-port=0` is doing in two more properties files; without it the suites die on
`Port already bound: 8081`, which is the platform's own npm registry and not a code failure.

- **`ScmBackupTriggerListener` is a `QitsDurableEventListener`, and durable is the point.** It
  replaced `api/GitHostEventController`, a fire-and-forget `POST /projects/api/events/post-receive`
  the git host made from inside somebody's `git push` — so a push landing while this process was
  restarting cost a backup with nothing to say so. Now the claim and the schedule commit together
  and a disconnect window is caught up from the log.
- **All four events map to one call, and `suppressCi` is ignored.** `SCMPublishCommit`,
  `SCMPublishTag`, `SCMDeleteBranch` and `SCMDeleteTag` each say "the refs in this repository are
  not what the twin holds". Tags and deletions **never used to trigger a backup at all** — the old
  hook fanned out branch updates only — so that is a fix rather than a translation. `suppressCi`
  says whether a *build* should run, which is qits-ci's question; the push that sets it is an
  imported upstream's whole history, which is exactly the push that most needs a twin.
- **`consumerId()` is `projects-backup-push` and it is STORAGE.** It names every `consumed_event`
  row and the watermark. Change it and you mint a brand-new consumer that initializes at the head of
  the log, silently skipping everything in between.
- **Nothing here throws.** `onPush` returns immediately and always and swallows every backup
  failure, so the only reachable failure on this thread is a payload that will not parse or names no
  repository — the same bytes forever, which is the poison case: a WARN and a return.
- **`ScheduledBackupSweep` stays.** Durable delivery narrows what it is for without emptying it: an
  unreachable forge, an expired credential and a backup that failed on its own are none of them
  missing events.
- **Causation is stamped on every push TO the git host, and on no push to a forge.**
  `bus/EventstreamPushCausation` implements `control/PushCausation` over `CausationScope`;
  `GitMirrorRegistry` hands it to `GitMirrors` as a `Supplier<String>`, and `RepoMirror.push` turns
  it into `-c http.extraHeader=X-Qits-Causation-Id: <uuid>`. One place, so `createBranch`,
  `deleteBranch` and every hand-built `PushSpec` carry it without a call site remembering to. The
  header name is a literal in `gitmirror` (that module depends on **nothing**, and stays that way);
  `EventstreamPushCausationTest` asserts it equals `CausationHeader.NAME`. A value that will not
  parse as a UUID is dropped rather than interpolated — a cause is advisory and must never fail a
  push, and a newline in an HTTP header would be injection. Backup pushes go to GitHub through the
  domain's own `git` invocation, never through `RepoMirror`, so they cannot pick it up.
- **The jar brings a MANDATORY deployment resource.** `.config/qits/deployments.yml` declares
  `postgresql:eventstream:qits_projects_eventstream`, and the resource **name** is load-bearing
  because the jar reads `QITS_RESOURCE_EVENTSTREAM_*`. `qits.eventstream.enabled=false` (`%dev`,
  `%test`) stops publishing, sweeping and dialling — never the datasource, which Quarkus opens and
  migrates at boot regardless. That is why the suite hands out a third database
  (`testdb/ServiceEmbeddedPgConfigSource`) and why `PackagedSurfaceIT` supplies a third triple.
- **`DeploymentActiveListener` is the only reason `main` ever moves, and it is the platform's first
  consumer of the deployment lifecycle events.** A release is a tag; `main` is finalized when
  qits-deployments says the version is live, and `control/ReleaseFinalization` is where every
  decision behind that sits — the correlation (by tag name, because `DeploymentActive` names an
  *application* and the application `qits-ci` is built from the repository `qits-ci-service`), the
  merge through `BackingBranchMerger` onto `refs/heads/main`, and the bookkeeping that follows
  (`ReleaseRequests.onReleasedTagMerged`, still the only writer of `merged_at`). Three things travel
  with it: the payload is a **local record** because the platform's Maven registry serves nothing
  under `qits-platform-deployments-events` (measured 2026-09-03) and a jar it does not serve is a
  release pipeline that cannot build; `consumerId()` is `projects-main-finalization` and initializes
  at the **head** of the log, because replaying from the epoch would try to merge every deployment
  this platform has ever made; and **a merge that will not apply is never thrown** — it is recorded
  on the released tag's row (`merge_requested_at`/`merge_detail`, V13) and retried by
  `ReleaseRequestSweep.sweepFinalizations`, because a throw would hold this consumer's watermark
  behind one repository's stuck merge and stop every other application's deployment from being read.
  A conflict there is an ERROR on every attempt: `main` only advances through this path and every
  release folds the pending tags in, so a released tag that will not merge is an anomaly rather than
  traffic.
- **`ReleaseFinalization.onReleased` is the same phase's TEMPORARY second gate, and its whole
  content is one question.** A library, an SPA and a docs repository deploy nothing, so
  `DeploymentActive` never comes for them and their `main` would never move again — so **at the
  release**, the released tag's tree is read and a repository declaring no
  `.config/qits/deployments.yml` is finalized there and then. **The fork lives in exactly one place**
  (`ReleaseFinalization.deployability`, reached only from `fork`), it reads the TREE and not the file
  (a tree listing separates "declares nothing" from "could not be asked"; `file` answers "failed" to
  both), and a repository that *does* declare a deployment is left entirely alone — merging early
  would put the commit on `main` before the deployment, the ordering this epic exists to fix. It goes
  when qits-maintenance becomes the lifecycle for libraries and a consumer's bump becomes the
  deployment it already is.
  **It hung off qits-ci's `SoftwareRelease` until 2026-09-04 and that was a gate only half the
  platform could pass**: that event is emitted by a repository's `ci-event-release.yml` recipe, so
  every recipe-less repository — every SPA — released tags that never reached `main` at all
  (qits-deployments-platform-frontend 2026.904.151913, `merged_at` null, main one commit behind for
  ever). A release is something this service performs itself, so the fork hangs off that now and
  needs no subscription: `SoftwareReleaseListener` and its `projects-non-deployable-publish`
  consumer are **gone**. It never throws (a tag exists by the time it runs; nothing after it may fail
  the release), and crash-safety is the **catch-up** half of `ReleaseFinalization.sweep()`, which
  re-asks the deployability question of every ungated `released_tag_pending_merge` row — one cheap
  tree listing per release in flight, and the thing that heals anything stranded.
- **`ProjectCreated` / `ProjectDeleted` are the project's own lifecycle, and the slug is the whole
  point of them.** `bus/ProjectLifecycleAnnouncer` over the `control/ProjectAnnouncer` port, from
  `ProjectService.create` and `.delete`. Payloads are `{projectId, slug, projectName, createdAt}`
  and `{projectId, slug, deletedAt}` — pinned by `ProjectLifecycleContractTest`, because the platform
  edge derives a project's TLS SANs (`*.<slug>.<domain>`) from those literals and no vocabulary jar
  carries the type. Four rules ride with them, and the first is not about projects at all:
  - **A payload field may not spell one of the envelope's words** — `signature`, `name`, `eventId`,
    `occurredAt`. `QitsEvent.name()` is a default method the `CanonicalJson` mix-in `@JsonIgnore`s,
    and Jackson matches a mix-in to a record's accessor **by name**, so a component called `name`
    has the getter `name()` and is **dropped from every payload with nothing failing anywhere**.
    Measured here: the display name was `name` in the first draft and published three keys where it
    meant four. It is `projectName` for that reason and no other.
  - **Both announcements are made AFTER the transaction**, which is why `delete` stopped being
    `@Transactional` and drives its rows through an explicit `QuarkusTransaction.requiringNew()` the
    way `create` always has. A self-invoked `@Transactional` helper would not be intercepted at all.
    `delete` reads the slug *before* the block, because afterwards there is nowhere left to read it.
  - **Every project is announced, with a dns record or without one.** The `ProjectDomainRegistrar`
    loop beside it is gated on `project.dns`; the announcement is not, and gating it would let an
    unrelated placeholder field decide whether the platform ever hears about a project.
  - **`startup/ProjectAnnounceBackfill` catches up what predates the event**, latched by
    `Project.announced_at` (V15): create stamps the column on the insert, the backfill selects the
    nulls, and it **publishes before it stamps** — a crash in between re-publishes into an
    idempotent projection next boot, while the reverse order would lose the announcement for ever.
    It is dark when `qits.eventstream.enabled` is false (`%dev`, `%test`), because a stamp written
    against an event that never left is exactly the unrecoverable half; the suite drives
    `backfill()` directly.
- **`bus/EventWireReflection` is the native-image registration**, and `EventWireReflectionTest`
  guards its completeness against the registered listener beans. Read that class's javadoc before
  adding a wire type; the failure it prevents is invisible to every JVM test by construction.

## Adding a dependency on another context

Don't. Declare a port in `domain/…/projects/control/`, inject it as `Instance<T>`, and make absent a
supported configuration with a documented behaviour — see the table in the README. Every port here
is optional; if you ever add a mandatory one, say why in its javadoc, the way
qits-workspaces-service's `RepositoryLookup` does.

**`qits-containers-client` is the exception that shows where the rule's real boundary is.** It is a
*client*, so it has to be read against this rule rather than waved past it — and the answer is the
one the eventstream jar already gets: what is forbidden is a dependency on another **bounded
context**, and this is **platform infrastructure**. The test is whether the jar is the platform's
single answer to a capability every module needs or one context's model. qits-events is where the
platform's events live; qits-containers is where its containers live. Three properties travel with
that, and a jar missing any of them is a client on another context whatever it is called: no domain
model crosses (images, environment and lifetimes — it cannot name a `Project` and does not want to),
every call is synchronous, bounded and **cannot throw**, and the jar brings no framework at all.

Never add a JPA relation to another context's entity. `Project ↔ Repository ↔ repository_name` are
real relations with real foreign keys because those tables are in **this** database. Anything else
is a string id through a port. (`repository_submodule` was the fourth such table; the wrapper's
`.gitmodules` is the submodule graph now, and V4 dropped it.)

Prefer widening an existing port over adding a synchronous call into another context. Where a port
call is a genuine ordering precondition — `createMainWorkspace` before a clone returns,
`releaseRepository` before a repository row goes — say so at the declaration, as
`WorkspaceLifecycle` does.

## Authentication

User authentication happens at `qits-gateway`. This service resolves the trusted forwarded user
and roles through the shared `qits-auth-core`; machine callers present validated OIDC credentials.

There is no auth variant to select in this service. The shared `qits-auth-core` resolves both
`X-Qits-User` and `X-Qits-Roles`, and the edge strips every client-supplied `X-Qits-*` header from
every inbound request unconditionally, which is the entire reason a header can be trusted as an
identity here.

**Nothing on this surface is open, and the role names a kind of caller rather than a level.**

| role | how a caller holds it | what it opens |
| --- | --- | --- |
| `qits:admin` | the forwarded `X-Qits-Roles` header alone — the edge asserts it for an authenticated admin session | every REST controller here (class-level), the events stream and the remote-login socket |
| `qits:system` | a machine bearer alone — qits-idp copies a client's `roles` into the token's `groups` claim, and quarkus-oidc reads that claim as roles with no configuration at all | `GET /projects/{projectId}/repositories/by-name/{repoName}` (qits-githost), `POST /projects/{projectId}/repositories/adopt` (the bootstrap) and the agent control socket `/projects/daemon/{projectId}` |

**Four routes take both roles**, because a sibling service and a browser read each of them:
`GET /projects` (the bootstrap turning the project's name into its id, and the projects overview),
`GET /projects/{projectId}/repositories` (qits-workspaces creating an aggregate branch, and the
projects overview), `GET /repositories` (qits-ci's trigger catalogue) and `GET /repositories/{repoId}`
(qits-workspaces' `RepositoryLookup`, and the workspaces detail screen, which has only the
repository id to go on).

**`POST /projects/{projectId}/repositories/adopt` is `qits:system` alone**, like the by-name
resolution and for the same reason: its caller supplies a git-host STORAGE id, which only the
machine that created the bare holds. That caller is qits-bootstrap-cli, which creates every platform
repository on the git host before this service exists to be asked. A person has no storage id to
supply and `POST /projects/{projectId}/repositories` is their route.

**A method-level `@RolesAllowed` REPLACES the class-level one; it does not add to it.** That is the
defect class to watch for here, and both of the routes above were live 403s found that way: the
controllers are class-level `qits:admin`, so annotating one method `qits:system` to let a machine in
locks every browser out of exactly that route and nothing else. A route with two kinds of caller
spells both roles.

**Two doors, and which one shuts says what is missing.** No user header at all is anonymous and
answers **401** at the mechanism's challenge; a named caller without the role authenticates and
answers **403**. `EpicsAuditIdentityTest` pins both.

**The suite goes through the mechanism, never around it.** `qits-auth-core` ships a `%test` dev user
granted all four platform roles, so a plain `given()` already is an admin session and the ordinary
test needs no fixture. A test about a *particular* caller sends what the edge sends — `X-Qits-User`
plus `X-Qits-Roles`, as the MCP suites and the two role-pinning tests do — and a test under
`NoDevUserProfile`, which blanks the dev user to reach the deployed posture, has no identity until it
sends them.

The identity exists to name `changed_by`; `EpicsAuditIdentityTest` is what pins that, and it uses
the real header rather than `@TestSecurity` on purpose. The header **is** the contract under test —
nothing else ever produces a principal in a deployed service — so `@TestSecurity` would install an
identity without going through the mechanism and prove a path the deployment never takes. That is
also how the bug ran unseen: this repo shipped `SecurityIdentity` with no mechanism behind it,
`changed_by` was null on every row, and an annotation that fabricates an identity would have gone on
passing the whole time.

Do not lift `projects/security` into a shared qits-auth javalib. Every repo builds from a clone of
itself alone, so ~115 lines duplicated per service is cheaper than a jar that has to travel to all of
them; the duplication is the decision, not an oversight.

## The wrapper is the project

A project's wrapper repository (`PROJECT` archetype, named `<slug>-<slug>`) carries the project's
configuration in its `.gitmodules`: one submodule per component, under the directory its archetype
names, with a relative url. Three rules follow, and every one of them is enforced in code:

- **The path says what the entry is, and there are two grammars.** `WrapperPath` is the single
  reading of one, and the reconcile applies it:
  - `<directory>/<name>` — the directory **is** the archetype (`RepositoryArchetype.fromDirectory`),
    and moving a submodule between directories is how a component changes kind. The row's
    `component` stays null.
  - `components/<component>/<name>` — the second segment is the row's `component` (V6, an **open
    set**: no enum, no check constraint). No directory says the kind here, so **an existing row
    keeps the archetype it has** — the flip must not re-type a live platform's rows — and a row the
    reconcile mints takes its archetype from the name's role suffix
    (`RepositoryArchetype.fromRepositoryName`: `-service`, `-daemon`, `-frontend`, `-oci`, `-cli`,
    `-javalib`/`-jslib`), or **null** when the name declares none. Null is deliberate and is the
    least destructive answer: a null-archetype row is never reported `UNDECLARED`, so it is never
    put in front of the delete that destroys the repository, while a guessed archetype would be a
    wrong label nothing in this service can correct.

  A **mixed** wrapper is the ordinary state on the way, not an edge case, and both grammars are read
  in one pass. A relative url stays `../<name>.git` however deep the path is — git folds it against
  the superproject's *remote*, never the gitlink's directory — so the backup twin a three-segment
  entry derives is the same one a two-segment entry did.

  Creation follows the wrapper rather than a preference (`WrapperSubmoduleWriter.addToWrapper`): a
  stated component places under `components/<component>/<name>`, a wrapper that already mounts
  anything under `components/` places a componentless create at `components/<name>/<name>`, and
  everything else lands under the archetype's directory as before.

  **The template seeds `components/` and nothing else now.** `project-template/` used to carry the
  six archetype directories, each with a README teaching its role; it carries one
  `components/README.md` teaching the component grammar instead. `fromDirectory` and
  `placeableDirectories` (which was called `skeletonDirectories` while that claim was true) are
  **kept in full** — legacy wrappers still mount entries under the six and the reconcile still has
  to read them. `RepositoryArchetypeTemplateSyncTest` holds both halves: the template seeds exactly
  `components/`, and the role suffixes that README teaches are exactly the ones
  `fromRepositoryName` reads.
- **A repository the wrapper does not name is not part of the project.** Write paths refuse it
  (`requireWrapperMembership`), the reconcile reports it `UNDECLARED` and the listing marks it
  `declared: false`. **The reconcile deletes nothing** (2026-08-26): a delete now destroys the
  repository on the git host, and an edit to one file is not consent to that, so a person decides in
  the UI.
- **An empty `.gitmodules` is not a manifest.** A wrapper declaring no submodules enforces nothing
  and reports nothing undeclared. Without that, shipping either rule would have bricked every
  project that had not adopted the model yet.

`WrapperSubmoduleWriter` is the only writer of that file and `WrapperGitmodules` the only editor —
textual, one section at a time, every other byte where it was, because this is a file people review.

## Renaming a repository

`PATCH /projects/api/repositories/{repoId}` with `{"name": "<new>"}` — the platform's only operation
that changes a repository's public identity, and phase 2 of the wrapper reorganisation is ordered
after it. `RepositoryService.rename` is the whole of it. Five things about it:

- **Nothing is asked of the git host.** A bare is keyed by the row's opaque id (the 2026-08-21
  identity ruling) and `GitHostRepositories`' three verbs all take a repoId, so there is no rename
  to make there: `/git/<project>/<newName>` serves the same bare the moment the row commits, because
  that path resolves through `GET …/repositories/by-name/{repoName}` and the alias table.
- **The repository answers to exactly the new name afterwards, and every old alias goes.** Keeping
  the old one is the tempting alternative and it is wrong in three ways at once: it would keep
  `/git/<project>/<oldName>` resolving, which is what a rename must stop; it would keep the old name
  taken against every other repository in the project; and `nameFor` — which *is* the DTO's `name` —
  would be free to answer either one.
- **The archetype is re-derived from the new name** (`fromRepositoryName`), because under the
  component layout the name is what says the kind. A suffix-less new name leaves the stored archetype
  alone: "the new name says nothing" is not "this repository is nothing", and a nulled archetype is
  one nothing here could correct.
- **Refused:** the wrapper (its name is `<slug>-<slug>` and the slug is immutable), an illegal
  name, and a name another repository in the project answers to. Renaming to the name it already has
  is a 200 that writes nothing and announces nothing (`changed: false`).
- **The wrapper is NOT rewritten, and the backup twin is not either.** That is step 3 of the per-repo
  runbook — update the `.gitmodules` entry and push — and until it happens the row reads
  `declared: false` and the reconcile reports it `UNDECLARED`. `Repository.url` genuinely self-heals:
  the twin is derived, never stored as a decision, so the first reconcile after the entry is renamed
  folds `../<newName>.git` against the wrapper's forge url and reports `SYNC_TARGET_UPDATED`.
  `RepositoryRenameTest` asserts the lag rather than leaving it documented, so a future change that
  quietly started writing the wrapper shows up there.

**Archetype is optional at creation for the same reason**: `POST …/repositories` reads it off the
name's role suffix when none is stated (the url's basename for the attach arm), and refuses when
neither the request nor the name says anything — a guessed kind is the one thing nothing downstream
could correct. An explicit archetype is obeyed unchanged, which is what keeps the SPA's create form
working.

## Project slugs

`Project.slug` is **unique** (V6) and immutable (`@Column(updatable = false)`). Each project has its
own upstream backup organisation and the slug names it; it also names the project's wrapper
repository (`<slug>-<slug>`) and its agent container (`qits-proj-<slug>`).

It was deliberately non-unique until 2026-08-08. The correction had to be its own migration then —
V1's column comment said the opposite and an applied file is checksummed — but the move to postgres
restarted both lineages, so the constraint now sits in `V1__init.sql` beside a comment that agrees
with it.

**A set of slugs is RESERVED, and it has two families with two different reasons.**
`ReservedSlugs` is the union and the only thing anything asks; each family keeps its own refusal
message, because "reserved" without the reason leaves a caller guessing which of two unrelated
mechanisms they walked into. A supplied reserved slug is a **400** naming the word and the reason; a
derived one suffixes like any other collision, so "Docs" still creates, as `docs-2`, and so does
"Dev".

- **Routing segments** — `ProjectService.RESERVED_SLUGS`, static: the six repository categories,
  `components`, `api`, `q`, `main-navigation`, and every application segment the platform routes. A
  slug is the first path segment of every address on every application host, and those hosts
  path-route every application's segment too — so a project called `projects` would be shadowed by
  this service's own API with nothing to say so. **A new service segment belongs in that list on the
  day it is routed.**
- **Platform environment names** — `qits.projects.reserved-slugs` (comma-separated, unset shipped,
  arriving as `QITS_PROJECTS_RESERVED_SLUGS`), seeded by the bootstrap. Configured rather than
  compiled in, because environments are created and removed on a running platform. The reason is a
  **host** reading and not a path one: the web editor is served at `editor.<project>.<domain>` while
  every application is served at `<app>.<environment>.<domain>`, and the edge tells the two apart by
  reading the first two host labels — so a project slugged `dev` makes `editor.dev.<domain>` parse
  as the application `editor` in the environment `dev`, and the project label does not survive the
  reading. An **application** name colliding with a slug is harmless (the first label is `editor`,
  never the slug) and is deliberately not guarded.

The configured list can grow past a project that already exists, and a slug is immutable, so
`startup/ReservedSlugAudit` reports at boot — one `ERROR` per colliding project, never failing or
blocking boot (a virtual thread, the way `StartupSelfSeed` runs). It carries no launch-mode gate,
unlike its two neighbours in that package: it reads one table and reaches no network, and a gate
would mean the check runs nowhere but production. Today it finds nothing — the sole project is
`qits-qits`, slugged `qits`.

**There is no rename path to guard.** `Project.slug` is `@Column(updatable = false)`, `update` takes
only a name and a description, and `resolveSlug` — reached from `create` alone — is the only writer
of the column. The create path is the whole enforcement.

Uniqueness is reached two ways, and the difference is what the caller said:

- **no slug given** — derived from the name, then the next free `-2`, `-3`, …, exactly like an epic
  slug within its project. The caller stated nothing about the value and two projects called
  "Checkout" must both be creatable.
- **a slug given** — a collision is a **409**. It is a statement, not a default: it names the
  upstream the wrapper is backed up to, so a silent rename would create a project whose wrapper does
  not match the upstream the caller meant, and nothing would say so until a push failed.

There is no dedupe backfill in V6, and not only because the live platform holds one project: a slug
is immutable because things are named after it, so rewriting one in SQL would leave that project's
wrapper addressable under a name the project no longer derives. A duplicate is a person's decision.

## Branch naming

Work on an epic, feature or task happens on a branch named after the planning row:

    epic/<epic>
    feature/<epic>/<feature>
    task/<epic>/<feature>/<task>

Every level carries its **own** prefix so no branch is ever a path prefix of another. Git stores
refs as files, so `epic/planning` and `epic/planning/slugs` cannot both exist; the per-level
prefixes are what make the three depths coexist.

The segments are the `slug` columns on `Epic`, `Feature` and `Task` (V2). A slug is minted from the
title at **create** and never changes — `@Column(updatable = false)`, and no `update` path touches
it — because a rename must not orphan the branches already cut. `Slugs.slugify` is the derivation, a
deliberate copy of domain's `ProjectService.slugify` (epics depends on `domain` nowhere, and stays
that way); `Slugs.unique` then adds `-2`, `-3`, … within the scope. The scope is the parent: an
epic's slug is unique per project, a feature's per epic, a task's per feature — two siblings sharing
one would name the same branch. `Project.slug` is unique too (V6), with the whole service as its
scope, so the same suffixing runs there; the difference is what a *supplied* slug means, and it is
in **Project slugs** below.

**Open, in another repo:** qits-workspaces-service's `CaptureService` mints capture branches named
`feature/<timestamp>`. Directory-wise that collides with `feature/<epic>/<feature>` — a capture
branch is a *file* at `refs/heads/feature/<timestamp>` while a feature branch needs
`refs/heads/feature/<epic>/` to be a directory, so the first of the two to be created blocks the
other. Renaming the capture prefix is a qits-workspaces-service workstream; do not change it from here.

## Epic lifecycle

An epic is in one of four stored statuses (V3): `REFINING`, `IMPLEMENTATION`, `SUPERSEDED`,
`ABANDONED`. New epics start `REFINING`, and `POST /epics/{id}/transition` is the only thing that
moves the status. Four moves are legal — `REFINING→IMPLEMENTATION` (the scope freeze),
`REFINING→ABANDONED`, `IMPLEMENTATION→SUPERSEDED`, `IMPLEMENTATION→ABANDONED` — and everything else,
including a target that names no status, is a 409.

**"Done" is not stored.** It is derived: an `IMPLEMENTATION` epic with at least one feature and every
feature's `implementedOn` set, which is the derivation the SPA already does. A fifth status would
give the same fact two sources that can disagree.

**The freeze is enforced in the services, per field rather than per endpoint.** `EpicLifecycle` holds
the rules and all three services obey them — a task's phase is the phase of its feature's epic.
Structural changes (the epic's title/description, and any feature/task create, update or delete,
`dependsOn` included) need `REFINING`; the implemented markers (`implementedOn`/`implementedAt`) need
`IMPLEMENTATION`. Those two rules alone reject every write in the terminal statuses, and a call
carrying both kinds always fails. Deleting an *epic* stays allowed in every status: it removes the
row rather than editing a frozen scope, and the audit log outlives it.

**Superseding copies the whole discarded scope** into a successor draft — a new `REFINING` epic with
the old title, description and feature/task tree, fresh ids, implemented markers reset, `dependsOn*`
remapped to the new rows, and `supersededByEpicId` on the old row pointing at it. The old row keeps
its frozen scope as the record of what was discarded, which is why superseded epics stay in the
list. Features and tasks keep their slugs, because the new epic and its features are new scopes; the
successor *epic's* slug cannot, because its scope is the project and the old row still holds it, so
it mints the next free suffix like any other create.

### Starting implementation dispatches an agent

**"Start implementation" is not a status move any more.** `POST /projects/api/epics/{id}/dispatch-agent`
(`projects/api/EpicDispatchController`) freezes the scope *and* stands an implementing agent up on
it, in one press — the ticket door's shape one planning level higher, so read
`TicketDispatchController` first: everything the two share is explained there. The workspace is the
project's **wrapper** with `branchTree` true, on `epic/<epicSlug>`, because an epic spans the estate
and names no single component (its *tasks* name repositories, one each, and no one of them is what
the epic is about). It lives in `projects.api` for the ticket door's reason: it needs `domain`, and
the epics jar depends on `domain` nowhere.

Four things are rules rather than details:

- **The transition comes first and the dispatch second, and that order is load-bearing.**
  `mark_task_implemented` is only open while the owning epic is in `IMPLEMENTATION`, so a dispatch
  that raced the transition would hand the agent a tool its own epic refuses — discovered halfway
  through the first task, from inside a container, with no way to fix it. The cost of that order is
  accepted deliberately: a dispatch that then fails leaves the epic in implementation with no agent
  on it, which is a *legitimate* state (the scope really is frozen) and a re-pressable one, since the
  far side adopts the workspace already standing on the branch. What runs **before** the transition
  is only what is knowable without attempting anything — the epic (404), a status whose work is over
  (409), a project with no wrapper (409), and no workspaces context at all (503).
- **A re-press is the retry, so `IMPLEMENTATION` is not a refusal.** The transition runs only from
  `REFINING`; an epic already in implementation is dispatched onto as it stands, because
  `EpicService.planTransition` would 409 on `IMPLEMENTATION→IMPLEMENTATION` and a door whose retry
  answered 409 would strand a failed dispatch. `IMPLEMENTED`, `SUPERSEDED` and `ABANDONED` are a 409
  naming the status. The move goes through `EpicResolutions` and never `EpicService.transition` —
  and since `REFINING→IMPLEMENTATION` does not resolve, no refinement is discarded here.
- **Nothing is written on the epic.** That is the whole difference from the ticket door, which
  stamps a comment: an epic has no thread, and its *description is the plan*, so a dispatch appending
  its own bookkeeping to it would be this door editing somebody's plan. What lands is the `EPICS`
  hint on the status move and the returned DTO — a failed dispatch has written nothing to undo.
- **The preamble is a snapshot and the instruction is the brief.** `refinementhost/EpicOutline`
  renders both this door's `# Implement: <title>` and a refinement's `# Refine: <title>`; the heading
  verb is the only thing they disagree about, and one renderer is why they cannot drift. The
  instruction sends the agent to `get_epic` for the live tree, to work the features and tasks in
  `dependsOn` order, to mark each task with `mark_task_implemented` **as it lands**, and to treat the
  work as unfinished until the changes are **released**. Its closing move stops short of the epic's
  own close: the agent reports, and *Mark implemented* stays a person's press — declaring an epic
  done stamps every unimplemented feature and task in one transaction, which is a decision about
  scope and not a report about work.

**`mark_task_implemented` on `EpicMcpTools` is a dedicated tool and a deliberate interim.** It is not
a widening of `update_task`, whose refusal ("the implemented marker is not editable here") is a
stance about the *refining* agent and stays intact — one that is drafting a plan must not also be
able to declare parts of it shipped. Two agents, two stances, two tools. It lands on
`TaskService.update`'s marker arm alone, so `EpicLifecycle.requireImplementation` is the guard that
runs and its message is what a draft's task answers with, and it returns its own small result record
rather than widening `TaskSummary` (which three tools return and none of which can ever carry a
marker). It is in `ReadOnlyRepositoryToolFilter.MUTATING_TOOLS`: an unattended run steered by an
untrusted commit message must not declare somebody's task shipped. **It goes when merge-derived
markers exist** — a consumer that reads a landed change back to the task it implements — and the
marker's semantics are identical either way; only the writer moves from a prompt to an event.

## Tickets

A **second root beside `Epic`, not a row under it** (V4). A ticket is a bug or an improvement small
enough that a plan would be overhead: `Ticket` + `TicketComment`, project-scoped exactly as an epic
is, with the same slug rule (minted from the title at create, unique within the project, never
re-derived) and no join to the epic tree anywhere. A ticket that turns out to need a plan is an epic
somebody writes, not a foreign key somebody sets.

**Almost everything here is the epics module's idiom applied again** — `TicketService` on
`ReadPatience`/`WritePatience` with no `@Transactional`, in-service cascade delete so each removed
comment gets its own audit row, the `value` + `clear*` pairing on the two nullable fields, a target
naming no status answering 409 while an absent one answers 400. Three things are *different*, and
each one is a decision rather than a simplification:

- **Nothing freezes.** `EpicLifecycle`'s whole subject is which fields a phase still permits,
  because an epic carries a scope that was committed to. `TicketLifecycle` has no `requireOpen` and
  must not grow one: a resolved ticket stays editable, commentable and reopenable, and the
  alternative — refusing writes once resolved — only means filing a duplicate whenever a resolution
  turns out to be wrong. `OPEN ↔ RESOLVED` both ways, no terminal status.
- **`created_by` and `author` are columns, and they are STAMPED.** Every other actor in this module
  lives only in the audit log. These two are duplicated onto the live rows because a ticket list
  wants a reporter and a thread wants a writer without a join per row — and they are read from the
  request identity at the seam (`EpicsPrincipal.changedBy`, or the MCP session's), never from a
  request body, so nobody can file as somebody else. An edit does not re-stamp either: who wrote it
  and who last changed it are different facts, and the second one is the log's.
- **The MCP surface HAS the transition.** `EpicMcpTools` deliberately exposes no lifecycle move,
  because freezing a plan is a human decision about committing to scope. `transition_ticket` is on
  the server, because resolving a ticket is a statement about work that is done — which the agent
  that did it is the one who knows — and it is reversible, so a wrong answer costs a click.
  `update_ticket_comment` is there for the front desk's sake — an agent that came back knowing more
  corrects its own earlier note rather than stacking a contradiction under it — and it obeys the
  rule above rather than bending it: an edit moves `updatedAt` and never `author`. All five ticket
  write tools are in `ReadOnlyRepositoryToolFilter.MUTATING_TOOLS`, which fails closed; deleting is
  on neither surface, since an agent that could delete what it disagrees with could erase the record
  of its own mistake.

**`AuditEntry.epic_id` is the subtree key, not a foreign key**, and tickets are what make that
visible: a `TICKET` row and every `TICKET_COMMENT` row under it carry the *ticket's* id there, so
"the whole history of this thing" stays one indexed query and still answers after the live rows are
gone. The column is not renamed — renaming it across an applied lineage and a live log buys a better
word and nothing else. V4 also widens `auditentry`'s entity-type check constraint, which V1 wrote
inline and unnamed, so the drop names postgres' derived `auditentry_entity_type_check` and the
replacement is named `ck_audit_entity_type`.

**Comments read oldest first** (`created_at`, id tie-break) — the opposite of the audit log's
newest-first, and deliberately: a log is scanned from the top, a conversation is read from the
start.

The SSE topic is its own (`ProjectChangeHint.Topic.TICKETS`, `tickets` on the wire) and every ticket
and comment mutation fires it, through `TicketChangeHints` — a sibling bean to `EpicChangeHints`
rather than four more methods on it, because the two announce different channels. Firing on `EPICS`
would redraw a board because somebody commented on a bug.

**A ticket can be handed to an agent, and that door is the one ticket route not in `epics.api`.**
`POST /projects/api/tickets/{id}/dispatch-agent` (`projects/api/TicketDispatchController`) stands an
aggregate workspace on `ticket/<slug>` at the project's **wrapper** — a ticket names no repository,
so the whole estate is the answer and `branchTree` is true — and launches a coding agent in it over
the `control/WorkspaceAgentDispatch` port. It lives in `projects.api` because it needs `domain` (the
project, the wrapper, the port) and the **epics jar depends on `domain` nowhere and must keep not
depending on it**; the service layer may cross, which is the crossing `ProjectTicketsController`
already makes. `EpicsPrincipal` is public for that one caller rather than copied into a second
package.

Three things travel with it:

- **The preamble is a snapshot and the instruction is the brief.** The workspace goal is rendered
  from the row (`# Ticket: <title>`, a type/status/assignee/reporter line, the description), the
  shape `RefinementService.preamble` carries; the agent's first turn then sends it to read the
  ticket *live* with `get_ticket`, because the thread moves and those bytes do not.
- **The agent is told what "done" means here, and told to say so on the ticket.** Report on the
  thread with `add_ticket_comment` and keep that **one** comment current with
  `update_ticket_comment`; the work is not finished until the changes are **released**, not merely
  merged; and once they are, **resolve the ticket with `transition_ticket`**. All three are the
  platform's own conventions and an agent left to itself gets the last two wrong — until 2026-09-08
  the instruction stopped at "released" and every successful dispatch left an OPEN ticket for a
  person to notice and close by hand. The resolve is **conditional and last**: an agent that was
  blocked, refused or released only in part leaves the ticket OPEN and says on the thread what is
  missing, and the sentence names that resolving is reversible through the same door so an unsure
  agent has a cheap correct move. Two seams make it an instruction rather than a dead letter, and
  both are stated in `instruction(...)`'s javadoc: qits-workspace-daemon lists `transition_ticket`
  in its own `TICKET_RESOLUTION_TOOLS` bucket (on the kimi path `enabledTools` is the whole tool
  surface, so an unlisted tool does not exist), and a dispatch keeps connecting **without**
  `agentReadOnly=true`, so `ReadOnlyRepositoryToolFilter` still fences all five ticket writes off
  every unattended run.
- **A dispatch that succeeded stamps the thread; one that failed writes nothing.** The comment is
  stamped from the caller's identity like any other, and a re-dispatch that qits-workspaces answered
  `SKIPPED_RUNNING` says it found an agent already working rather than claiming a second one. A
  failure surfaces on the door instead — 502 from the far side, 503 with no workspaces context at all
  — because a comment saying an agent is on it when none is would be worse than the error.

## Project agent harness

One container per project, holding a clone of that project's wrapper repository and running
`qits-projects-daemon` over it, so a refinement agent can read and build the project it is drafting
epics for. The host side is `service/…/agenthost/`; the container's process is its own repository
(`qits-projects-daemon`), and that repo's `AGENTS.md` is the source of truth for every value below.
The whole shape is qits-workspaces-service's daemon harness — registry, tunnels, proxy — adapted
rather than reinvented, so read that repo before changing anything structural here.

**Two path contracts, and both are append-only.** They are baked into every container as env at
creation, and only a container recreate re-injects them:

    control socket   ws://<host>:<port>/projects/daemon/<projectId>
    proxy prefix     /projects/container/<projectId>/

Neither is a literal in this tree. Both live in the vendored `DaemonProtocol` constants and are
asserted on both sides by `DaemonCodecTest`, which is also why `projects-daemon-protocol/` exists
here at all: it is a **source copy** of the daemon repo's module, same java package, different
artifactId. A protocol change is three edits in order — the record and the constants in the daemon
repo, `DaemonCodecTest` there, then the same files here — and it bumps `CAPABILITY_VERSION`.

**The daemon has no address.** `ProjectsApi` binds `127.0.0.1` from capability 1, so there is no
direct branch anywhere: qits sends an `OpenStream` over the control socket, the daemon dials *out*
to `/projects/daemon/stream/{nonce}`, and `DaemonStreamRoute` marries that WebSocket to the parked
loopback connection `AgentTunnels` accepted. The nonce is the whole authentication — host-minted,
single-use, short-lived — because the control socket names its caller with a path parameter and a
dial-back that named its own project would reproduce that weakness in a second place. That
token-free control socket is inherited from qits-workspaces and closes with it when qits-idp machine
auth lands; do not add an interim token.

**Nothing rewrites a path.** `ContainerProxyRoute` forwards `/projects/container/{id}/commands` byte
for byte, and the daemon is *told* which leading part is its own address
(`QITS_PROJECTS_DAEMON_API_BASE_PATH`, rendered by `ContainerProxyPath.base`). Do not add a
`substring` here: a hop that rewrites leaves the two ends disagreeing about the destination's own
address, and the disagreement surfaces a long way from the rewrite. A **WebSocket upgrade does not
go through `vertx-http-proxy` at all** — `proxyUpgrade` does it by hand, because the library skips
its whole interceptor chain on an upgrade (so the bearer never arrives) and pipes with no
`writeQueueFull`/`pause`/`drainHandler` at all.

**The env contract**, read from the daemon repo and asserted by `AgentContainerFactoryTest`. Getting
one wrong fails silently: no url leaves the daemon idle, no token leaves its API unbound.

    QITS_PROJECTS_DAEMON_URL             the control socket, dialled verbatim
    QITS_PROJECTS_DAEMON_API_BASE_PATH   /projects/container/<projectId>/
    QITS_PROJECTS_DAEMON_PROJECT_ID      the project served
    QITS_PROJECTS_DAEMON_REPO_NAME       the wrapper, <slug>-<slug> — the clone is name-addressed
    QITS_PROJECTS_DAEMON_GIT_BASE        stated, never derived: the git host is qits-githost
    QITS_PROJECTS_DAEMON_API_TOKEN       qits.projects.daemon-api-token
    QITS_PROJECTS_DAEMON_API_PORT        13338, also the authority the proxy pins
    QITS_PROJECTS_DAEMON_HOOKS_PORT      13337
    QITS_PROJECTS_DAEMON_CLAUDE_MOUNT    /claude-home
    QITS_REPOSITORY_MCP_URL              the one MCP server a launch attaches — this service
    QITS_PROJECTS_DAEMON_AGENT_CONFIGURATION       the resolved document — see "Injecting the document"
    QITS_PROJECTS_DAEMON_AGENT_CONFIGURATION_PATH  where the daemon writes it before it starts anything
    QITS_COMMISSIONED_CLIENT_ID          this container's OWN idp client — absent with no idp
    QITS_COMMISSIONED_CLIENT_SECRET      its secret, answered once and stored here
    QITS_PROJECTS_DAEMON_AUTH_TOKEN_URL  the idp token endpoint used before dial-home
    QITS_PROJECTS_DAEMON_AUTH_AUDIENCE   this qits-projects service's environment client id

**The last two are a credential per container, not a shared one.** They are commissioned from
qits-idp's `POST /idp/api/clients` as `{agent-container, <projectId>}` and handed back when the
container is gone, so what a container authenticates its pulls, its maven/npm resolution and its git
reads with has the container's lifetime and no other. Read the section below before touching them —
in particular, they are the one part of this table whose *absence* is a supported configuration.

**The harness gets exactly one MCP server, and it is this one.** `QITS_REPOSITORY_MCP_URL` names
this service's `repository` server at `/projects/mcp`, which carries `EpicMcpTools` beside
`RepositoryMcpTools` — the epic surface is why the container exists. It is composed from
`qits.projects.own-host`/`own-port` (`qits.projects.agent-mcp-url` overrides), so it is *stated*
rather than left to the daemon's derivation; the daemon keeps that derivation as a fallback, so
containers created before this env still work and nothing had to be recreated. The name carries no
`QITS_PROJECTS_DAEMON_` prefix because it is the daemon's existing `qits.repository-mcp.url` key.

The exclusion is the other half of the decision: qits-workspace-daemon wires **three** servers into
a workspace container (`actions`, `repository`, `observability`) and a project agent gets neither of
the other two — its job is the project's plan, not workspace actions or another service's telemetry.
Nothing can add them back at runtime: the daemon addresses `repository` alone and refuses any other
name, and Claude is launched `--strict-mcp-config`, so the shared `/claude-home` volume's own MCP
entries are ignored.

**The token is not a boundary.** `qits.projects.daemon-api-token` is peer authentication behind a
loopback bind — it says "qits is calling", never "this user is calling" — so the proxy *sets* it,
replacing whatever the caller sent, and a forwarded one would be a credential leak. It ships with a
default so a deployment needs no configuration; the other end is still fail-closed, because a daemon
handed no token does not bind its API at all.

### The commissioned credential

**One idp client per container, and its lifetime is the container's.** `AgentCommissions` gets it
from qits-idp's commission API — `POST /idp/api/clients` with `{"contextKind":"agent-container",
"contextId":"<projectId>","claims":{"project":"<projectId>"}}`, HTTP Basic with **this service's
own** oidc client id and secret,
because a caller there already holds an idp credential and that is how the API authenticates one.
`idphost/IdpAgentCredentials` is the adapter and `agenthost/AgentCredentials` the seam; the adapter
is `@DefaultBean`, so the suite's `FakeAgentCredentials` wins the injection and no test reaches an
idp. Everything is read from the keys the oidc-client block already ships
(`client-enabled`, `client-id`, `credentials.secret`, `auth-server-url`) — there is no second address
and no second credential to configure.

**The `claims` member is the scope, and it is not the same fact as `contextId`.** The context id
says which container this credential belongs to — what the reconcile compares against live places —
and the claim says what the credential may act on, which qits-idp puts on every token the pair mints
and every resource service reads back (`QitsClaims.PROJECT`). qits-ci's manual trigger uses exactly
this to decide which repositories a caller may have evaluated, so an agent reaches its own project's
pipelines and nobody else's. For the agent harness the two are the same string, because an agent
container's context *is* a project; for the refinement harness beside it they are not, which is why
`RefinementCredentials.commission` takes both. Neither ever states `"*"` — qits-idp refuses a
commission that widens itself, and asking would be asking for the thing the scoping exists to stop
granting. A refinement whose project cannot be named is commissioned unscoped, as every credential
here was before.

Four things bite.

- **Absent is the shipped configuration and must stay byte-identical.** With
  `quarkus.oidc-client.client-enabled=false` this process holds no secret, so it can authenticate to
  nothing: nothing is commissioned, the two names are simply not in the env map, and the spec a
  container is started with is the spec it was before any of this existed. Same answer, plus one
  WARN, when the switch is on and the secret is blank.
- **The fresh arm commissions and the wake arm must not.** `AgentContainerFactory.forProject` mints
  a credential; `forRestart` reads back the pair the container already holds and sends it unchanged.
  That is not a cache: qits-containers hashes a workload's whole spec, **environment included**, so
  a wake that minted a fresh secret would be a spec change and would replace the container on every
  wake — the exact defect `ContainerRuntime.restart` records, reintroduced through the one door left
  open. `AgentCommissioningTest` compares the two arms' whole env maps for that reason.
- **The pair is a row in this database (`agent_credential`, V3), secret included**, and that follows
  from the point above rather than from convenience: the wake arm has to reproduce a value idp
  answers exactly once. The row is keyed on the project id with **no foreign key** to `project`,
  because an agent container outlives its project and a cascade would drop the row while the
  container still held the credential.
- **Decommissioning is a sweep here, and that is a fact about this repository.** Nothing in this
  service removes an agent container: stop and the idle sweep both stop, deleting a project leaves
  its container standing, and `ContainerRuntime` has no removal verb at all. So the lifecycle hook
  the model asks for has no call site. The two real paths are `forFreshContainer`, which hands a
  project's previous credential back before minting the replacement container's, and
  `AgentCredentialReconcile`, which at boot and hourly compares idp's own listing of what this
  service commissioned against what the orchestrator says exists. It asks
  `ContainerRuntime.inspect` **per commissioned project** rather than reading the listing, because
  the listing answers an empty list both for "no containers" and for "could not ask" and reaping on
  the second would revoke every live agent's credential at once; `inspect` is empty for a true 404
  and throws otherwise, and a pass that cannot ask reaps nothing. If a removal verb is ever added,
  it decommissions there too and this becomes the belt it should be.

A commission holds through 401, 403 and nothing answering for
`qits.projects.agent-credentials.commission-patience` (PT30S) — the same classifier and the same
measured idp-cutover window as `ContainersAgentRuntime.holdThrough`, shorter because it sits in
front of an image pull somebody is waiting on. Past that it throws an `AgentCredentialException`,
which is a plain `RuntimeException` **on purpose**: `AgentContainers.ensure` rethrows a
`DomainException` with its status and turns everything else into `FAILED` with the reason on
`failureDetail`, and this belongs in the second arm.

**The shared volumes carry qits-workspaces' names on purpose** — `qits_shared_dot_claude`,
`qits_shared_m2`, `qits_shared_pnpm`. They are platform-wide: a divergent credential volume here
would give project agents their own unauthenticated agent home. The per-project checkout volume is
`qits_project_<projectId>`, keyed on the id, while the container is named `qits-proj-<slug>` so
`docker ps` reads well. `Project.slug` is unique (V6), but only among *live* projects, and deleting
a project does not remove its agent container — so a later project taking the freed slug finds the
old container on the name. The name therefore proves nothing, which is why it is not the address:
a place is `owner/project-agent/<projectId>` and a row found under this project's id *is* this
project's. What survives of the old label check is one arm down, in
`ContainersAgentRuntime.run`: before provisioning it asks whether another of this owner's places
already holds the name, and answers 409 rather than letting the registry refuse it as a unique
constraint nobody can act on.

**The stop policy is stop, never remove.** `POST …/agent-container/stop` and the
`qits.projects.agent-idle-timeout` sweep (PT4H) both leave the container in place, so the next ensure
starts *that* container again — same docker id, same writable layer. The checkout would survive a
replacement too (it is a named volume the orchestrator will not remove under `IDLE_STOP`, and the
daemon skips its self-clone on an already-populated `/workspace`), which is what makes a wake safe
even when an image bump turns it into one — see "The container orchestrator" below. Idleness is
measured from the
last thing the daemon said — `Hello`, heartbeat, agent activity — so it means "nobody is using this
project", not "nothing is happening": a long silent build still heartbeats. A container this process
has never heard from is stamped on sight and ages out one window later, rather than being immortal
or reaped immediately.

**A failed provision is reported, not swallowed.** The daemon clones the project into
`/workspace` on boot; when that fails it says `ProvisionFailed`, and docker still calls the
container healthy. So the frame is *recorded* per project and the agent-container read answers
`FAILED` with a `failureDetail` rather than `RUNNING` — otherwise the panel opens a terminal onto
an empty checkout. The record lives in a map beside `lastActivity`, not on the connection, because a
daemon that cannot clone usually drops its socket right after saying so and a failure held on the
socket would vanish exactly when somebody came to read it. A `Provisioned`, a reconnect or a stop
clears it. **There is no re-provision**: `ensure` no-ops on a running container and the daemon
latches its attempt for the life of its process, so recovery is to remove the container and ensure
it again — deliberately not automatic, since the `/workspace` volume a remove orphans is where
uncommitted work lives. The detail is a field and not a sixth `AgentRuntimeStatus`: the SPA switches
on those five strings and they are a published contract.

### Agent surface configuration

**What a session runs as is a row here now, not a constant in a daemon.** A *session surface* is
where in the product a session was started from — `project.epics`, `project.tickets`, `epic.chat`,
`epic.agent`, `workspace.chat`, `workspace.agent`, `epic.autonomous`, `ticket.dispatch` — and one
configuration per surface, **platform-wide**, holds the harness, model, effort, remote control,
permission mode, activity tracking, the system prompt, the initial prompt, and which of the three
built-in MCP servers attach with what narrowing. Schema in `V16`; entities, store and shipped
defaults in `domain`; two controllers and the boot seed in `service`.

- **The seed is the safety.** `control/AgentSurfaceDefaults` carries the eight surfaces seeded from
  what the two daemons hardcode today, so turning the store on changes nothing: the tickets desk's
  text block byte for byte, an **empty** system prompt on the epics desk (a value, not an absence),
  skip-permissions everywhere because every launch renders it unconditionally, and per surface
  exactly the servers its host daemon's `serversFor` attaches at the scope it launches with.
  `AgentSurfaceDefaultsTest` asserts every one of those against the daemons' literals **copied in**
  — deliberately not derived, and it names the file and line each came from.
  <br>**The real test is not written yet and the class says so.** What this feature wants is each
  seeded configuration rendered through `eu.wohlben.qits:qits-coding-agents` and compared against
  what that library renders with no configuration at all; the library is still being extracted from
  the two daemons and is not published, so the literals stand in until it is. The TODO names the
  task and the coordinate.
- **Two surprises in the seed, both faithful.** Remote control is seeded **on for chat and off for
  interactive**, the opposite of the intuition: `--remote-control` is dropped under `--print`, so
  both daemons enable it over the SDK control channel in `claudeChatProtocol` and an interactive
  launch enables nothing. And the two composed surfaces carry **different** bootstrap sentences
  ("this project" vs "this workspace"), because their two daemons spell the constant differently.
- **A read never 404s.** A surface with no row answers its shipped default; a surface outside the
  vocabulary answers a neutral one with **no** servers. That is what lets a daemon ship ahead of
  this store and a ninth surface be added one repository at a time — which is also why
  `surface_key` carries no check constraint and why the seed is a boot bean
  (`startup/AgentSurfaceSeed`, insert-if-absent) rather than an `insert` in V16. A DDL seed would
  have made the constants and the rows two copies free to disagree.
- **The pre-approval tool lists are stored per attachment and are not operator-editable.** Storing
  them is forced rather than chosen: the two daemons' lists for the *same* `repository` key differ
  (the workspace one carries four write exceptions), so a constant keyed by server could not seed
  both. The editor's write body has no field for them and `validated()` strips whatever a request
  carried; a write keeps what the row held and falls back to the shipped constant.
- **Two doors, and they are different subjects.** `api/AgentSurfaceConfigurationController`
  (`/agent-surfaces`, `qits:admin`) is the editor's — list, read, replace, plus the revision trail;
  it validates a known harness, a known permission mode, an MCP attachment naming a server that
  exists, and the same server attached twice (both harnesses render one `key → config` object, so a
  repeat silently displaces). `api/AgentConfigurationController` (`/agent-configuration`,
  `qits:admin` + `qits:system`) is the container's — one versioned snapshot of **every** surface,
  which qits-workspaces fetches at provision and this service will read for its own agent container.
  A snapshot, not a subscription: a container keeps what it was born with and an edit applies to the
  next one, deliberately and with nothing in the UI about staleness.
- **The revision trail is `AuditEntry`'s shape**, because that is this estate's answer for edited
  text with a history and an author: append-only, one row per write, the principal beside a JSON
  snapshot of the whole configuration afterwards, and **not** foreign-keyed to the row it describes
  so a retired surface's history survives it.
- **The attachment set is replaced by an explicit delete–flush–insert**, not a cascaded collection.
  The unique `(surface_key, server_key)` constraint is why: a Hibernate flush is free to insert
  before it deletes, which turns a no-op edit into a constraint violation.
- **`agenthost/AgentConfigurationWireReflection` is the native-image registration**, the third
  member of `bus/EventWireReflection`'s family. The REST return type would have been registered
  anyway; what would not is the same records going through the injected `ObjectMapper` outside a
  request — every revision snapshot today, and the mounted document next.

### The harness capability catalogue

**What fills the model and effort dropdowns is discovered, never hardcoded.** The valid values belong
to the harness binary inside the image: they differ per harness and change when the image is rebuilt.
So a daemon probes its harnesses once at container start, reports through `GET /agents/available`,
and this service caches the report — `V17`, `entity/AgentHarnessCapability`,
`control/AgentCapabilityCatalogueService`, `api/AgentCapabilityController` at `/agent-capabilities`
(`qits:admin` + `qits:system`).

- **Two doors on one noun, facing opposite directions.** The `PUT` is the daemon's report and is the
  **only** writer; there is no editor door onto this store and there must not be one, because a
  hand-typed model list is exactly the hardcoded catalogue this feature removes. The `GET` is the
  editor's and must never spawn a process or wait on a container — the editor is a platform-wide
  route with no container in front of it — so it is one query and a fold.
- **The ingest body IS the daemon's `/agents/available` body**, with `imageVersion`, `reportedBy` and
  `capabilities[]` added; `agents` and `defaultAgent` may be present and are ignored. Pass-through
  rather than a translated shape, so the relay (this service for its own agent container,
  qits-workspaces for a workspace's) has no opinion to drift.
- **Keyed by harness AND image version, and the union is refused on purpose.** Two containers can run
  two builds at once, so merging their answers would offer a model set no single binary has. The
  newest report wins **whole**, names its `imageVersion`, and the losers appear as
  `otherImageVersions` — named, never folded in. **Newest by arrival, not by version string**: an
  image version is a name, a report is an event, and only the second has a time.
- **Three booleans, each distinct from an empty list.** `modelsEnumerated: false` — the harness has
  no listing command and this is a shipped alias set (Claude Code), so the editor leads with the
  free-text escape. `effortSupported: false` — no effort flag exists at all (Kimi), so the editor
  shows **no** effort control rather than a disabled one carrying Claude's values. `probeFailed` —
  these lists are a fallback rather than a reading of the binary, which the editor can say.
- **An empty cache is supported, not degraded.** `control/AgentCapabilityDefaults` answers a harness
  nobody has reported, flagged `shipped`, so the editor works on a fresh estate. Those values are a
  deliberate **copy** of the library's, which is where the probes live; the copy is only ever read
  while the cache is empty, so a drift lasts one container start rather than for ever. It reports
  `authenticated: false` — fail-closed, since nobody has looked.

### The external MCP server catalog

**Servers this platform does not own, defined once and attached per surface.** `V18`;
`entity/AgentMcpCatalogEntry` + `entity/AgentSurfaceExternalMcpAttachment`,
`control/AgentMcpCatalogService`, `api/AgentMcpCatalogController` at `/agent-mcp-catalog`
(`qits:admin`). A surface names the entries it attaches in its ordinary write body
(`externalMcpServers`, keys only).

- **A sibling attachment table, not a `kind` column.** A built-in attachment carries three narrowing
  booleans, a read-only marker and a per-attachment tool list; an external one carries none of them —
  the url is the entry's, there is no narrowing to apply to somebody else's server,
  `agentReadOnly=true` is this platform's own marker, and the tools belong to the entry. One table
  would have been five always-null columns and a discriminator deciding which half of the schema
  applies.
- **Three refusals on write, each closing a silent failure.** A reserved key (`repository`,
  `observability`, `actions`) would displace a platform server in the rendered `key → config` object
  and the session would look normal while talking to somebody else's server. A non-http(s) URL cannot
  be carried: Kimi's ACP shape is `(key, url, tools)` with no place for a stdio command. And a
  credential reference qits-configuration does not hold is a 400 naming it — but **"could not ask" is
  a third answer**: an outage of that service is not a validation error, so the write is accepted
  with a WARN and the strict gate stays where it protects a running agent.
- **`AgentMcpCatalogEntryDto` never carries a value; `AgentResolvedMcpServerDto` is the only shape
  that does.** The first goes into the editor's answers, revision snapshots and the OpenAPI document;
  the second exists only inside `AgentDocumentSurfaceDto`, in the document mounted into a container.
  `AgentMcpCatalogControllerTest.theEditorsAnswersNeverCarryAHeaderValue` is the load-bearing
  negative here, and `theDocumentCarriesTheFullyRenderedServer` is the positive it pairs with.
- **The document is version 2 and a surface is `{configuration, externalMcpServers}`.** The previous
  feature answered both doors with one record so operator and container could not drift, and the
  configuration record is still shared — what could not be shared is the *bytes*, because the
  container needs a header value the editor must never see.
- **An unresolvable reference fails the whole document build, naming the key and the surface.** Never
  a partially-resolved document: a server rendered without its credential 401s on the agent's first
  tool call, which surfaces as a confused agent hours later with nothing pointing back at the store.
- **A delete is refused while any surface attaches the entry**, naming them — which is why
  `agent_surface_external_mcp_attachment.catalog_key` carries no foreign key. An FK would make that
  message unreachable and a 500 inevitable.
- **`allowedTools` IS operator-editable here**, unlike the built-ins' shipped lists: the platform
  ships no pre-approval constant for a server it has never heard of. Under
  `--dangerously-skip-permissions` it is the only lever until the permission-mode knob is used.

#### The credential key namespace: `env.<VAR>` under the reserved application `qits-agent-mcp`

The epic left this open ("the config epic's declared keys are `env.`-prefixed because they render as
container environment variables, and an MCP credential is not an env var of any app — pick a
non-colliding namespace, or an operator-class entry, and write down which"). It is settled in
`control/AgentMcpCatalog`, and the reasoning is short:

- **It cannot be a new key prefix.** qits-configuration addresses a value by `(env, application,
  key)` and its key grammar is **closed** — `ConfigurationKeys.requireKey` accepts `env.<VAR>` and
  the four indexed families (`mounts[i]`, `publishes[i]`, `groups[i]`, `aliases[i]`) and answers 400
  to everything else. A `secrets.` or `mcp.` key could not be *written* over there, and widening that
  grammar is another repository's change.
- **So it is the application segment** — the axis that is open (`requireApplication` checks a dns
  label and nothing more) and, better, the axis the concern is actually about. The worry is not the
  three characters `env`; it is that the key would be an environment variable **of an application**,
  injected into that application's container by the deployer. Under `qits-agent-mcp`, which nothing
  deploys, it is not: no deployer renders these keys anywhere, and no application's declaration can
  collide with them. That is precisely an operator-class entry, spelled with the one grammar the
  store has.
- **The application name is a constant, deliberately not configurable.** A per-deployment namespace
  would be a per-deployment place for a credential to hide.
- **Forward-compatible with secrets by construction.** The entry is `plain` today because
  qits-configuration's `secret` class does not exist yet, and that is accepted on purpose — *the
  reference is the point*. When secrets land there the same `(application, key)` pair is served as a
  secret, no field moves and no editor changes.
- **The cost, stated rather than hidden:** `qits-agent-mcp` has no declaration, so its entries read
  as `orphaned` in qits-configuration's own listing. That flag means "no declaration accounts for
  this key", which is true and harmless — nothing is written or removed on the strength of it.

The hop itself is `confighost/` — the fifth `@DefaultBean` HTTP client in this service, seam
`control/McpCredentials`, address `qits.projects.agent-mcp.configuration-url` plus
`-configuration-env` (**both unset shipped, and there is deliberately no default env**: a guessed one
would read another tier's credential). **No header fallback**, unlike the ci and maintenance hops:
qits-configuration's entry routes take `qits:admin` or `qits:system` and a forwarded `X-Qits-*` pair
from a machine-driven document build carries neither honestly, so with the named oidc client off the
read is not attempted and the document fails naming the key. **Nothing in that package logs a value
at any level** — a log line names the key, the status code or the exception, and the value is not
interpolated into any message, including an exception's.

### Injecting the document into the project's agent container

**A project agent container is born holding what its sessions run as.** `AgentContainerFactory`
builds the resolved document from the store **in process** — no fetch, because the store is here —
and puts it in the container's spec beside the path it is to land at. The two surfaces this
container serves are `project.epics` and `project.tickets`; what goes in is *every* surface, which
is the container door's own decision and its javadoc carries the argument.

    QITS_PROJECTS_DAEMON_AGENT_CONFIGURATION       the whole resolved document, serialized
    QITS_PROJECTS_DAEMON_AGENT_CONFIGURATION_PATH  /tmp/qits/agent-configuration.json
                                                   (qits.projects.agent-configuration-path)

- **Both or neither.** A path naming a file nothing wrote must fail the daemon at boot rather than
  read as "this container was given no configuration" — which is a real and different state (every
  container created before this shipped is in it) and has to stay distinguishable.
- **The shape is the estate's, landed first by qits-workspaces-service (38534c4)**, whose pair is
  `QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION` / `…_PATH` at the same `/tmp` path. The names carry
  each daemon's own prefix and that costs the library nothing: each daemon reads its own environment
  and hands the library a **path**, so `AgentConfigurationDocument.readFrom(String)` stays one
  contract. What must not diverge is the shape — two variables, both or neither, bytes plus path —
  and it does not. Append-only once a container exists, like the two path contracts above.
- **It is an environment variable and not the mounted file the epic specified, because nothing here
  can mount one.** `ContainersWire.Spec` carries `volumeMounts` and `sharedMounts` and *no* way to
  materialize host content, and this service holds no docker socket and writes nothing to the docker
  host at all — so neither host that creates a container (this one or qits-workspaces, which uses the
  same client) can produce the file. The daemon writes it at boot from the value it was created with
  and hands that path to the library's `readFrom`, so every decision the epic made survives — the
  file the library validates at boot, the path passed in by the host, the snapshot taken at creation,
  the recreate-only reach — and only the transport moves. **The cost is stated rather than hidden**:
  a value in env is readable in `docker inspect` and the document carries resolved external-MCP
  credentials. This container's env already carries `QITS_COMMISSIONED_CLIENT_SECRET` on those terms.
- **`/tmp` because that file is derived, not durable**, and because it is the one place writable by
  the arbitrary host uid the container runs as: `/workspace` is the project's git checkout (a stray
  file there lands in somebody's `git status`) and `/claude-home` is the platform-wide credential
  volume every other container mounts, where a per-container document would be overwritten.
- **The document is stamped with the store's last change, never with `now`, and that is
  load-bearing.** `AgentSurfaceConfigurationService.documentForContainerSpec()` reads the newest
  revision instant instead of the wall clock the container door uses. These bytes are hashed into the
  spec qits-containers stores, and `forRestart` sends `Recreate.ifChanged`: a document that differed
  on every render would make **every wake a container replacement** — the exact defect this repo
  carried while that service had no start verb, reintroduced through a timestamp.
  `AgentContainerFactoryTest.aRestartPermitsAReplacementAndIsOtherwiseTheSameRequest` is what
  notices. Nothing else needs a stamp: a catalog entry's url or a resolved credential changing moves
  the bytes themselves.
  <br>**qits-workspaces solves the same problem by storing the document on the workspace row**
  (38534c4) — it has to, because over there the document arrives from a *fetch* and re-fetching per
  ensure would be a new answer every time. Here the document is built from a database this service
  owns, so making the build deterministic is the whole fix and there is no column to keep in step.
  Same rule, different half of it: the spec must be reproducible from what the container already is.
- **That same hash IS the epic's "an edit applies to the next container".** A store edit changes the
  document, so the next wake's `ifChanged` replaces the container and the edit takes effect. No push,
  no poll, no staleness flag, and nothing in the UI about it.
- **A document that cannot be built fails the ensure, loudly.** A surface attaching an external MCP
  server whose qits-configuration credential does not resolve throws out of `document()` naming the
  key and the surface, and that reaches `AgentContainers.ensure`, which reports the container
  `FAILED` with the reason on `failureDetail`. **This is deliberately the opposite of
  qits-workspaces' policy**, and the difference is what fails: over there the document arrives over
  the network from a peer that can be down, so refusing to create the workspace would trade a
  configuration outage for a work outage. Here the build reaches a database this service already
  cannot run without, and the only way it fails is a catalog entry somebody attached with a
  credential that is not there — a configuration error a person made and can undo, which would
  otherwise become an agent talking to a server that 401s on its first tool call.
- Blank `qits.projects.agent-configuration-path` switches the whole injection off: neither variable
  is set and the daemon falls back to the library's shipped constants, which is the state every
  container created before this shipped is in.

### The capability relay: this service's one call site of the ingest door

`PUT /agent-capabilities` was built as a **relay** whose body is byte-identical to a daemon's
`GET /agents/available`, and `agenthost/AgentCapabilityRelay` is qits-projects' carrier of it for its
own agent container. qits-workspaces writes the matching one for a workspace's.

- **It fires on the daemon's `Hello`**, from `AgentDaemonRegistry`, on a virtual thread. That is the
  first moment the container is reachable at all — the daemon binds loopback and is only addressable
  through `AgentTunnels`, so a live control socket is the earliest evidence there is anything to ask.
  It is structurally off every request path: **not** from `AgentContainers.ensure` (which returns
  while the container is still pulling an image, long before any daemon has spoken, so a relay there
  would either block the browser or read nothing) and **not** from the capability GET, which is the
  editor's and must never wait on a container. The one process spawn per harness happens inside the
  container at its own boot, not here.
- **`Hello` is NOT the moment the daemon can answer, and the read is retried because of it.**
  Measured live 2026-09-09 (service 2026.909.115344, daemon 2026.909.114544): the probe answered a
  complete report through the tunnel, the ingest door recorded it, and a genuine fresh `Hello` filled
  nothing for minutes. The cause is on the other side of the socket and is structural rather than a
  narrow race — `ControlSocket.start()` kicks the boot self-clone onto a worker and dials home **in
  parallel**, and it is that worker which, when the clone finishes, calls `wireCapabilities()`: probe
  the harnesses, *then* `projectsApi.start()`, the loopback bind. So at `Hello` the daemon's API is
  not listening at all, its own dial-back finds nothing on `127.0.0.1:13338`, the read fails at
  DEBUG, and nothing ever asks again. **There is no better moment to fire at**: no frame announces
  the bind, `Provisioned` is sent from *inside* the clone (still ahead of the bind and the probe) and
  is not sent at all on a reconnect, and inventing one is a protocol change that would strand every
  container already running. So the relay keeps asking **while the daemon says "not yet"** —
  `relay-attempts` (12) reads on a backoff capped at `relay-retry-max-ms` (30s), about four minutes,
  sleeping on the virtual thread. The `inFlight` guard is held for the whole window, so a flapping
  daemon cannot stack windows.
- **The classification is the fix, and it keeps "absent is quiet" intact.** A **404** is a daemon
  that does not serve the route: absence, terminal, quiet. A **503** (what `ProjectsApi` answers on
  every agent route while `agentLaunch` is null), a hop that failed outright, and a **2xx with an
  empty body** are **not ready** and are asked again. A body that *parses* with no `capabilities` is
  an older daemon that answered, so it is terminal and quiet on attempt one; a warm container
  (populated `/workspace`, API up almost at once) still records on attempt one too. **A window that
  ends unanswered is a WARN naming the project and what the last attempt saw** — the whole point of
  bounding it, since every other arm is DEBUG and that silence is exactly how this ran unseen for a
  release.
- **An empty body is NOT a malformed one, and conflating them cost a second release
  (2026.909.130640).** That fix classified by status and then handed everything 2xx to Jackson — but
  the shape the daemon's boot window actually presents through this tunnel is a **200 with nothing in
  it**, not a 503 and not a failed hop. Jackson answers `MismatchedInputException: No content to map
  due to end-of-input`, which landed in the *broken* arm: a terminal WARN, one attempt, no retry.
  Live on 2026-09-09 the entire log for every container start was the HELLO line and that WARN,
  saying the daemon spoke a contract this service could not read — the opposite of true. So the blank
  check runs **before** Jackson, in `ingest` as well as in `read`, and the rule is: a body that is
  absent says nothing about capabilities and can never be the reason to stop asking; only a body
  genuinely present and unparseable is broken. `AgentCapabilityRelayTest` pins the three apart as
  outcomes (`NOT_READY` / `ABSENT` / `BROKEN`) rather than as row counts, because none of them writes
  a row and what separates them is only whether the relay asks again.
- **The read goes through the tunnel like everything else**, at
  `/projects/container/<projectId>/agents/available` — the full proxied path, because no hop rewrites
  one and the daemon serves its API under the address it was told is its own. It presents the same
  `qits.projects.daemon-api-token` bearer `ContainerProxyRoute` sets.
- **Absent is quiet, broken is loud.** Until both daemons are released the ordinary answer carries
  none of `imageVersion`, `reportedBy` or `capabilities` — DEBUG, no row, no failure, and the
  catalogue keeps answering the shipped fallback, which is a state it is designed to be in. A body
  that will not parse, or one naming a harness this platform does not know, is a WARN. A non-2xx is
  never a failure reported to anyone — there is no caller to report a status code to — but it is no
  longer all one answer either; see the status split below. **Nothing here throws into its caller and
  nothing here can fail a container start.**
- **It writes through `AgentCapabilityCatalogueService` in process, but not around the door's
  mapping.** A loopback PUT would need this service to hold a machine bearer for one of its own
  roles and would traverse the whole auth stack to reach the same method; calling
  `AgentCapabilityController.report` directly is not available either — the class is `@RolesAllowed`,
  the interceptor runs on an in-process call too, and a virtual thread reacting to a control socket
  carries no identity (measured: `UnauthorizedException`, which is the door working correctly). So
  the translation moved onto the body record itself, `CapabilityReportRequest.reports()`, a pure
  function the door and the relay share. **Do not write a second one** — that is the third place the
  contract can drift, which is what the ingest door exists to prevent.
- **Two blanks are filled and only two.** `imageVersion` from this host's own image pin and
  `reportedBy` from the project, *when the daemon named neither*. The image version is half the key
  the catalogue stores under and this service chose the pin, so a report keyed on the empty string is
  one that cannot be told apart from another build's. A daemon that names them wins, always.

## Refinement containers

One container per REFINING epic — the refining route's whole backend, which used to be an ordinary
qits-workspaces workspace on a `refining/*` branch (epic refinement-improvements, part 2). The host
side is `service/…/refinementhost/`; the container runs the WORKSPACE image and daemon, unchanged —
`qits-workspace-daemon` dials home to whatever `QITS_WORKSPACE_DAEMON_URL` names, and this service
is that home now. `workspace-daemon-protocol/` is that daemon's wire contract, vendored beside
`projects-daemon-protocol/` (two daemons, two vocabularies, two modules; the codec test travels with
each).

The shape deliberately mirrors the project-agent harness one section up — control socket, registry,
reverse tunnel, verbatim proxy with the hand-rolled websocket upgrade — on refinement's own paths,
all three append-only once a container exists (`RefinementPaths`):

    control socket   ws://<host>:<port>/projects/refinement-daemon/<rowId>
    dial-back        /projects/refinement-daemon/stream/<nonce>
    proxy prefix     /projects/refinement-container/<rowId>/

Where it differs from the agent harness, each difference is the domain line:

- **Keyed by epic, addressed by row id.** `refinement` (V4) holds one row per epic (unique), with
  the branch (`refining/<epicSlug>`), the parent (the wrapper's default branch — a refinement always
  forks it, which is why there is no parent/child tree and no integrate door), the preamble computed
  from the epic tree at create, and the commissioned credential — ON THE ROW, because
  `Recreate.ifChanged` hashes the whole spec and a resume must reproduce the pair byte for byte.
- **A refinement runs no code.** `BOOTSTRAP_AUTORUN=false`, `SERVICES_AUTOSTART=false`, no
  `SERVICE_PROXY_BASE`, no actions MCP server — the tab set this backs has no Services or Actions
  tab, and its web view frames the deployed environment, not a dev server.
- **There is a removal verb.** Discard tears down container → volume → credential → branch → row,
  in that order; the agent harness deliberately has no removal at all. `RefinementCommissions`
  decommissions at the explicit seams; `RefinementCommissionReconcile` reaps `refinement`-kind idp
  clients no row claims (its own CONTEXT_KIND, invisible to the agent reconcile and vice versa).
- **Resolving the epic is what calls that verb, and it is a rule of the service rather than a
  browser dance.** `refinementhost/EpicResolutions` is the only thing a door may use to move an
  epic's status: it previews the move (`EpicService.planTransition`, which throws every refusal the
  transition would), discards the refinement when the target **resolves** the epic — `IMPLEMENTED`,
  `SUPERSEDED`, `ABANDONED`, never the `REFINING→IMPLEMENTATION` freeze — and only then transitions.
  The order is the point: a resolved epic must never own a workspace nothing can reach, so a failed
  teardown leaves a still-refining epic with a UI to retry from. Until 2026-09-08 the cleanup was
  `refining-page.ts` discarding before transitioning, and only for `ABANDONED`: the epics board, the
  REST API and any machine caller all leaked a container, a volume, a commissioned credential and a
  `refining/<slug>` branch, and `findOrCreate` refuses a non-`REFINING` epic so the stranded row
  could not even be adopted back. Do not call `EpicService.transition` from a door again.
- **The ensure ladder runs off the request thread** (`RefinementService`): the browser gets a
  technical-process id to watch instead of a request that hangs behind an image pull. One
  `Semaphore` permit per row — a semaphore and not a lock, because the permit is taken on the
  request thread and released on the worker. The daemon's provision output (`CommandChunk`s tagged
  `provision`) streams into the narration via `RefinementDaemonRegistry`, and its terminal
  `Provisioned`/`ProvisionFailed` settles it.
- **`processhost/` is the technical-process port's live implementation** — the piece the domain
  port's javadoc always said an assembling application supplies. Standing it up for refinement also
  lights the repository-scoped narration (pull/push/sync leases) that ran unnarrated before, and
  `api/TechnicalProcessEventsController` is the SSE controller the port was waiting for. Everything
  in it is this process's memory; an evicted id answers the 404 the frontend reads as "expired".
- **The image pin rides qits-workspace-daemon's releases through qits-configuration**: that repo's
  release publishes `qits/workspace`, qits-configuration's `bus/SoftwareReleaseListener` consumes the
  `SoftwareRelease` and writes this application's `QITS_PROJECTS_REFINEMENT_IMAGE_VERSION` extra, and
  the deployer injects it at the next deploy. SmallRye maps it onto
  `qits.projects.refinement-image-version` and lets the env win, so the committed property is the
  clone-alone default and nothing rebuilds this service to move a version.
  `.config/qits/ci-event-upstream-workspace-daemon.yml` used to carry that follow by rewriting the
  property and releasing this service; it was retired on 2026-09-03, the way the library follows went
  to qits-platform-maintenance on 2026-09-02/03.
- **Git reaches the edge** (`qits.projects.refinement-git-url`, default
  `http://qits-platform-edge:8080`): the workspace image's credential helper speaks oauth2 Basic and
  the edge rewrites it to a Bearer, exactly as a workspace's does. The three registry keys
  (`refinement-maven-repository-url` / `-npm-registry-url` / `-npm-proxy-url`) ship blank like
  qits-workspaces' — unset injects nothing.

The REST surface is under `/projects/api`: `POST /refinements` (find-or-create keyed by epic —
adopt-existing is the create's ordinary path, not an error dance), `GET/verbs /refinements/{id}`,
`GET /projects/{projectId}/refinements` (the LIGHT projection — live halves, no git drift, because
the list redraws on every activity hint), the prompt draft and attachments (content URLs are
embedded into epic markdown, so attachment ids are never renumbered), the per-row SSE hint channel,
and the technical-process stream. The suite's seams are `FakeRefinementRuntime` and
`FakeRefinementCredentials`, winning over the `@DefaultBean` adapters exactly as the agent fakes do
— and read through METHODS, never public fields, because a client proxy does not proxy field access.

**No read on that surface performs a git operation, and the single-row read is where that had to be
fixed.** `RefinementService.view()` opened with `mirror.refresh()` until 2026-09-08 — a `git fetch`
warm, a **full clone cold**, and cold is exactly what cutting `refining/<slug>` leaves the mirror,
so the first read of a brand-new refinement was the slowest read this service had and the refining
page rendered nothing until it answered. Measured live that day: the listing 12 ms, an established
row 18 ms, a **freshly created row 139 ms** — with a warm mirror, and unbounded without one.
`RefinementDrift` holds the three numbers (`ahead`, `behind`, `conflictsWithParent`) as a cached
fact instead: `of()` answers what was last computed and schedules a background pass when that is
missing or older than `qits.projects.refinement.drift-staleness-ms` (30s), and it reads the cache
*before* it decides to schedule, so what a caller gets never depends on a worker's timing. Three
rules ride with it — a cold mirror degrades to `null`, "not known yet", and never to a slow
response; a pass that could not ask keeps the previous answer and re-stamps it, so an unreachable
host is retried once a window rather than once a read; and a drift that **changed** fires the
existing per-row `GIT_STATUS` hint, which the daemon already publishes for clean/dirty and the SPA
already maps to "re-read this row", so a late arrival needs no new channel and no poll. The frontend
half of the same defect is qits-spa-projects 2026.908.183933, which stopped withholding the page's
first paint on this read.

**Designs are frozen HTML kept with the refinement** (`refinement_design`, V5) — one self-contained
document per page, styles inline, cascading from the row like the draft and attachments do.
`RefinementDesigns` holds the writes and `/refinements/{id}/designs` serves them; a list leaves the
document out and only the single read carries it.

There is deliberately **no content route**. Agent-authored HTML served same-origin would be an XSS
door into the platform's own session, so the bytes only ever travel as a JSON field and the SPA
renders them in a sandboxed iframe with scripts off. Do not add a `text/html` route here.

**A row is ACTIVE or PROPOSED, and only a person crosses that line.** A REST capture is ACTIVE at
once; the three MCP tools on the `repository` server (`list_designs`, `get_design`,
`propose_design`) let a refinement agent read the designs and propose a revision, which lands
PROPOSED with the agent's note on it. `POST …/{designId}/resolve` is the decision: `REPLACE` copies
the proposal onto the design it revises and drops the proposal, `KEEP` makes the proposal a design
of its own, and discarding is a plain `DELETE`. `propose_design` is in
`ReadOnlyRepositoryToolFilter`'s mutating set — an unattended run must not fill the tab with work
nobody asked for.

## The container orchestrator

**This service holds no docker socket and spawns no process.** Every container verb the harness has
— provision, bring back, stop, stamp, list, make a volume — is one HTTP call to **qits-containers**,
which owns the daemon. `agenthost/ContainerRuntime` is still the seam; its sole implementation is
`containershost/ContainersAgentRuntime`, and `DockerAgentRuntime` (a `ProcessBuilder` shelling
`docker`) and the `AgentContainer` argv builder are **deleted**, not retired. If a docker argv ever
reappears in this repository, that is the regression.

Five things bite.

- **A place is `owner/workload/ref`, and this service's ref is the project id.** So the seam takes a
  project id where it used to take a container name, and `qits-proj-<slug>` travels as the spec's
  `explicitName` — a hint for `docker ps`, never an address. `qits.projects.containers.owner`
  **must equal the machine token's `sub`** once the far side's gate is on (its `OwnerGuard` compares
  them), which is why it defaults to reading `quarkus.oidc-client.client-id`. Two instances must not
  share it; two environments sharing one docker daemon are `dev-qits-projects` and
  `prod-qits-projects` and neither one's rows name the other's containers.
- **The client never throws, and its four answers are the whole vocabulary.** A refusal and an
  unreachable service mean opposite things — one is evidence about the request, the other about
  nothing at all — so `inspect` answers empty for a **404 only** and throws for everything else. The
  docker CLI it replaces could not tell those apart (a broken binary and an absent container both
  exited non-zero), and reading "we could not ask" as "there is nothing there" is what would send the
  ladder to provision a second container. Do not add a fifth outcome by catching something.
- **A bring-up holds through 401, 403 and nothing answering, and through nothing else.**
  `ContainersAgentRuntime.holdThrough` is qits-ci-service's classifier copied verbatim, and the measurement
  behind it is that repository's: across a qits-platform-idp cutover those three are statements about
  the moment rather than about the request, and each attempt asks the `TokenSource` again, which is
  the only way a post-cutover token is ever picked up. Retrying is safe because `ensure` is a PUT per
  place. `SPEC_CONFLICT`, `IMAGE_MISSING` and a 400 on a value are one attempt each — no window fixes
  them. **A 2xx whose observed state is `MISSING`/`GONE` is a failed bring-up**, not a started one.
- **Waking a stopped agent is a start in place, and a replacement only if the spec really changed.**
  One `ensure` does both: qits-containers starts the container the row already names when the spec is
  unchanged (same docker id, everything outside the volumes intact), and replaces it when it differs.
  So `forRestart` sends `Recreate.ifChanged` and nothing else — that permission is what lets an
  agent-image bump landing while the agent slept be applied at wake, which is the one moment it can
  be applied without taking a container away from somebody working in it. The running arm asks for no
  recreate at all.
  <br>**This arm was a forced re-create until 2026-08-13**, and the reason is worth keeping: that
  service had no start verb, its `RESTART` step fell through to a second `docker run` under a name
  docker already held, and a stopped place asked for again settled `MISSING` behind a **200**. The
  workaround here was an env stamp that differed per call, so the spec was never "unchanged" and the
  recreate step ran instead. qits-containers-service 354fd7f fixed it — a bounded `start` on its driver seam,
  a real-daemon test that stop-then-ensure returns the same docker id — and the stamp is gone with
  it. Do not reintroduce a per-call value into this spec: a request that differs every time is a
  request that can never be started in place.
  <br>A delete-then-ensure was never the alternative: `ct_container`'s `container_name` is unique
  across **every** row including the settled ones, and a deleted row keeps the name for
  `qits.containers.row-prune-horizon` (P7D).
- **The idle sweep stays here, and it resolves identity itself.** Its tunnel teardown and
  `registry.forget` are in-memory state of this process that the orchestrator cannot touch. The spec
  still carries an `IDLE_STOP` policy with the same window as the belt for a qits-projects that died
  holding a container — and the sweep `touch`es what it keeps, because that clock is only ever
  stamped when a row is written and would otherwise stop a container somebody is working in. The
  listing carries no labels and no refs, so a container **name** is matched back against the live
  projects' own `qits-proj-<slug>`; one that matches none is **skipped**, because every action past
  the stop is addressed by a project id there is no longer one of.

**Nothing here reaches an orchestrator under test.** `ContainersAgentRuntime` is `@DefaultBean`, so
`FakeContainerRuntime` simply wins the injection, and the test config points `qits.containers.url` at
`http://127.0.0.1:1` so a call that escaped the fake fails fast instead of reaching a real
orchestrator on the developer's own machine. There is no startup observer left to gate: the network
is the bootstrap's and this service creates none.

**`containershost/ContainersWireReflection` is the native-image registration**, the second member of
`bus/EventWireReflection`'s family and there for the identical reason — the client jar builds its own
`ObjectMapper`, so the wire records are invisible to the build step that scans for what needs
reflecting on, and without it the JVM suite stays green and the binary fails on every call. The list
is the client's README's list; keep them the same.

## Schema changes

`domain/src/main/resources/db/projects/migration/`, hand-written, its own lineage on its own
datasource. Never touch the monorepo's `db/migration` — that is a different database. Epics has its
own lineage under `epics/src/main/resources/db/epics/migration/`; the two never mix.

Entities live in a **named** persistence unit (`projects`), not the default one. An `EntityManager`
injection therefore needs `@PersistenceUnit("projects")`.

**Two PostgreSQL databases, and both lineages restarted at V1 to say so.** This application declares
`resources: postgresql:db, postgresql:epics:qits_epics` in `.config/qits/deployments.yml`;
qits-platform-deployments creates a role and a database for each before the container starts and
injects `QITS_RESOURCE_DB_*` and `QITS_RESOURCE_EPICS_*`. The two library jars map those onto
`quarkus.datasource.projects.*` and `quarkus.datasource.epics.*` in their own shipped defaults —
that mapping is the application's job, never the deployer's — and neither triple has a fallback: an
unset variable leaves the expression unresolvable and the process dies at Flyway naming it.

**Every postgresql datasource carries a three-line resilience block, and `DatasourceBaselineTest`
fails the build if one loses a line.** The lines are `jdbc.driver=eu.wohlben.qits.db.PatientPgDriver`,
`jdbc.validate-on-borrow=true` and `jdbc.acquisition-timeout=15S`, and they only work as a set:
stock Agroal does *not* wait for a database that is gone (a failed connection **creation** goes
straight to the caller, so `acquisition-timeout` alone bounds nothing but a starved pool), the
patient driver is what holds the request while postgres comes back, and validation at borrow is what
turns a dead pooled connection into a fresh creation attempt for it to be patient about. The
measurements are in the superproject's `db-patience-plan.md`.

Three datasources, three places the block is written, and the third is the odd one: `projects` in
domain's jar, `epics` in the sibling's, and `eventstream` in **this application's own**
`application.properties` — the one exception to "nothing the bus needs is spelled here", because the
baseline belongs to the deployed application whoever shipped the datasource, and qits-eventstream's
jar does not carry it yet. Drop those three lines when it does.

The enforcement lives in `service` and **not** one per module, the opposite placement to
`ArchRulesTest` above and for the mirror-image reason: those rules judge classes a module owns, while
this one judges configuration only the deployable has all of. It is a `@QuarkusTest` on purpose —
`application.properties` is a Quarkus config source, so a plain unit test would read the jars'
defaults and none of this module's own lines.

**`DbRetry` (qits-db-core) wraps read seams a cutover would otherwise turn into a wrong answer.** One
is wrapped here: `RepositoryService.findByProjectAndName`, the read behind
`GET …/repositories/by-name/{repoName}` — qits-githost's 404 by proxy, and a git client caches "no
such repository" as an answer rather than as an outage. `RepositoryNameResolver.resolve` is wrapped
too, around its own unique-constraint loop rather than inside it: that loop is the alias race and
retries with no pause, and it used to swallow connection losses for three attempts and then report
them as a race that never happened. Two rules govern every new wrap — **outside**
`QuarkusTransaction.requiringNew()` and never inside an open transaction or a `synchronized` monitor
— and the retried block must be re-runnable, which is why these are reads. `RepositoryNameCutoverTest`
is the proof, and it pins both halves: the read survives a severed connection, and a name that
resolves to nothing still answers 404 on the first attempt.

**The epics board's list reads are wrapped too, and `epics` routes them through one bean.**
`control/ReadPatience` holds the deadline (`qits.epics.read-deadline`, 15S) so the five seams —
`EpicService.listByProject`, `FeatureService.listByEpic`, `TaskService.listByFeature` and
`AuditService`'s two histories — cannot drift apart, and so a suite can shorten it: a give-up test at
fifteen seconds is a fifteen-second test. What they are worth: a severed connection would draw a
project with no epics, a feature with no tasks, or an audit log saying nothing ever happened, and
every one of those reads as an answer.

The identical repository calls **inside** this module's writes are deliberately left unwrapped —
slug uniqueness in `insert`/`create`, the cascade deletes, and `listDependents`, which no read path
reaches at all. They run inside `@Transactional`, where a retry would re-run statements on a
connection already marked rollback-only. That is why the wraps sit in the services and not in the
repositories: a repository-level wrap would catch both callers and there is no way to tell them
apart from down there. `EpicService.get` and its two siblings are unwrapped for the same reason —
the write paths call them.

`EpicListCutoverTest` is the proof, one seam standing for the five since they share the bean: the
list answers after a severed connection, and a database that stays gone still fails at the deadline
rather than never.

**`DbRetry.inNewTx` holds WRITES through the same cutover, and it is a different offer from
`DbRetry.call`.** It owns the transaction — every attempt is `QuarkusTransaction.requiringNew()` —
so it can separate an attempt that *certainly* did not commit (the body threw a connection-class
failure; Quarkus rolls a failed body back and never commits it) from one nobody can place (anything
the transaction manager reports, a `RollbackException` included, because Narayana spells a lost
commit and a real rollback the same way). Only the first is retried. Three rules govern every wrap:

- **The body is database-only.** It re-runs, so an SSE hint, an HTTP call or a git push inside it
  would happen twice. That is the rule that decides which seams are wrapped here and which are not.
- **It replaces `@Transactional`, never joins it.** A method that kept the annotation would run in a
  transaction the retry cannot open again, and a caller already in one is a wrap that must not exist.
- **Flush last.** Hibernate flushes at commit by default, which puts the write on the far side of the
  line `inNewTx` can classify — the whole write would land in the undecidable commit phase and never
  be retried. `WritePatience` does it for the epics seams; the two domain seams do it by hand.

**`epics` routes all ten writes through one bean, `control/WritePatience`** (`qits.epics.write-deadline`,
15S) — `EpicService`'s create/update/transition/delete, `FeatureService`'s and `TaskService`'s
create/update/delete. Every one is rows and nothing else, and no caller is transactional:
`EpicMcpTools` is deliberately transaction-free (two persistence units, non-XA) and the controllers
are too, with their change hints fired *after* the service returns. `AuditService.record` keeps its
`@Transactional` and joins, exactly as it joined the annotation's.

**`domain` wraps the two writes that are only rows, and leaves the rest alone on purpose.**
`ProjectService.update` (a rename) and `RepositoryService.recordBackupOutcome` (the bookkeeping after
the push to the twin has already happened — the case `inNewTx` exists for) call `DbRetry` directly,
the module's existing idiom. Everything else in those two services reaches out of the database inside
its transaction and a retry would do it a second time: `cloneRepository`, `cloneWrapperOrigin`,
`createBlankRepository`, `initWrapperOrigin` and `adoptExistingOrigin` clone, push and call the git
host over HTTP; `setMainBranch` and `deleteBranch` run `ls-remote` and a push; `deleteInternal` and
`ProjectService.delete` tear down workspaces, call the git host's delete and remove mirror
directories;
`attachBackupRemote` is rows only but its sole caller `adoptWrapperRepository` is `@Transactional`,
so a wrap there would nest. `RepositoryNameResolver` keeps its `DbRetry.call` around the race loop.
Honest partial coverage, not a contorted wrap.

`EpicWriteCutoverTest` is the proof, one seam standing for the ten since they share the bean.
`FailingEpicWrites` fails **after** `super.persist`, which is what makes exactly-once a real
question: the create lands one epic row and one audit row, a failure that is not the connection is
reported after exactly one attempt, and a cutover that never ends still gives up at the deadline.

The H2 lineages (V1..V6 and V1..V3) were **deleted rather than continued**, which needed one
precondition: the move is an unwrap and a re-bootstrap, so no database anywhere was on either and no
appended migration would have had a reader. Each fresh V1 is where its lineage arrived, translated,
and both files carry the decisions in their headers — the ones worth knowing without opening them:
`repository_submodule` is simply absent (V4 had dropped it), `last_backup_at` gained the time zone
`Instant` always meant, `clob` became `text`, the archetype check constraint stayed while every other
enum column still refuses one, and both epics backfills went because every database reaching the file
is empty. **A second clean start is not a precedent** — this one cost a re-bootstrap, and the
ordinary rule (keep appending, never edit an applied migration) is back from V1 onward.

**`V2__causation.sql`, once per lineage, is that rule being followed.** Both add the platform's
generic `causation_id uuid` column (qits-eventstream's `CausedRow`): nullable, in no constraint,
never a foreign key — the event it names lives in qits-events' store — and with no backfill, since
no existing row has an answer to invent. Seven of the eight entities take it (the eighth,
`AgentCredential`, arrived later with the column already in its own `create table` — V3). The stamp fills it from
the ambient `CausationScope` at persist, and **nothing here sets it explicitly**, because no insert
crosses a thread hop: the backup executor and the pull executor only ever UPDATE, and the stamp is
insert-only. Where the decisions land, and why:

| entity | | |
| --- | --- | --- |
| `Project` | `CausedRow` | Created on the request thread (`ProjectService.create`, from REST or MCP); the boot-time self-seed is rootless, which is the honest answer. |
| `Repository` | `CausedRow` | The one worth tracing. All four mint paths run on the request thread, and `WrapperReconcileService` is the machine-driven one — a reconcile records, per adopted or cloned member, what asked for it. |
| `RepositoryName` | `@Uncaused` | The only opt-out. An alias is derived, idempotent state; the repository it FKs to is a `CausedRow` one join away and is the row that was actually caused. Decisively, `RepositoryNameResolver` mints the self-name in its own transaction off any request context — the provision worker, or container creation — where no scope stands, so a stamp would record null forever. No event id is ever in reach to set as data. |
| `Epic`, `Feature`, `Task` | `CausedRow` | `EpicMcpTools` reaches the same services the SPA does, on the same thread: an agent minting a task is exactly the flow worth tracing. |
| `AgentCredential` | `CausedRow` | V3, and the column ships in its own `create table`. The only insert is `AgentCommissions.forFreshContainer`, on the request thread that asked for the container; the reconcile only ever deletes. |
| `AuditEntry` | `CausedRow` | Covers what the live rows cannot. The stamp is insert-only, so an epic row records the cause of its own creation and never of an update; and a deleted row is gone while its DELETE entry stays (audit rows are deliberately not FK'd back). |

The decisions are **enforced, not documented**: `ArchRulesTest` (qits-arch-rules) sits in each entity
module — `domain` and `epics`, one per module rather than one in `service`, because a module owns its
entities and `epics` depends on nothing, so a guard downstream of it would neither see its classes
nor survive its lift-out. A new `@Entity` that neither implements `CausedRow` nor declares
`@Uncaused` fails the build naming the class.

**`epics` needed an `archunit.properties` and no longer does.** Every epics entity participates, so
no class there carries `@Uncaused` and the rule set's negative rule ("nothing `@Uncaused` may
implement `CausedRow`") matched zero classes — which ArchUnit fails by default, and the module went
red for being in the best state the rules describe. The module-scoped
`archRule.failOnEmptyShould=false` bought time; the real fix was `allowEmptyShould(true)` on that
rule in qits-arch-rules, it shipped in 2026.811.152803, and the file went with the pin. Do not
reintroduce it: a rule that matches nothing anywhere else is still a typo worth failing on.

## Tests

- App-level config lives in `service/src/main/resources/application.properties` and **the tests
  inherit it** — Quarkus reads main's copy during a test run and merges the test resources over it,
  so `quarkus.rest.path`, the MCP root-path and the rest are already in effect. Never re-declare
  them in `src/test/resources/application.properties`: a test copy is free to drift from the shipped
  one, and then a green suite proves nothing about what actually starts. That file is for genuine
  test-only overrides (the persistence-unit wiring, `clean-at-start`, the test port, `target/`
  paths, the test-jar index entry).
- **No dev services and no containers, ever.** A dev service is a container start, and the first
  rule here is that a clone tests green with no docker. The stores being postgres does not change
  that answer: `testdb/EmbeddedPg` starts **zonky's** postgres — real binaries resolved as Maven
  artifacts, spawned as a child process — and a config source per module hands its url, username and
  password to every `@QuarkusTest` at an ordinal above `application.properties`, because the port is
  chosen at run time and cannot be written down. Testcontainers is not on this classpath and must
  not arrive. Every **(module, datasource) pair names its own database** (`qp_domain_projects`,
  `qp_epics_epics`, `qp_svc_projects`, `qp_svc_epics`, and the IT's two) so no two suites can mean
  one schema. `EmbeddedPg` travels to `service` in `domain`'s test-jar; `epics` keeps a **copy**,
  because that module depends on nothing and a test-scoped edge is still an edge.
- **The service suite runs on port 0.** 8081 is Quarkus' default test port and also the address the
  platform's own npm registry is published on, so on the machine this is most likely built on the
  whole suite dies with `Port already bound: 8081` — which reads like a code failure and is not one.
  `quarkus.http.test-port=0` in the service test properties is the answer; the flake below is the
  same rock from the other side.
- **A deleted class stays in `target/classes` and can still win a bean lookup, so the gate is
  `./mvnw clean verify`.** Measured here on 2026-08-17: half the REST suite answered 403 and no
  forwarded role ever reached an identity, while the same tree had passed minutes earlier. The cause
  was `eu/wohlben/qits/projects/security/ForwardAuth{Mechanism,IdentityProvider}.class` — this
  repo's own pre-`qits-auth-core` mechanism, whose **source is gone** while an incremental build left
  the compiled classes behind. Two `HttpAuthenticationMechanism` beans then compete, and the stale
  one reads `X-Qits-User` and no roles at all; which of them wins varies between builds, so the same
  working tree is green in one run and red in the next. A `clean` is not a ritual here — it is the
  difference between testing this repository and testing what it used to be.
- `OpenApiSchemaExportTest` writes `docs/openapi.yml`. Regenerate and commit whenever the REST
  surface changes:

      ./mvnw -pl service -am test -Dtest=OpenApiSchemaExportTest -Dsurefire.failIfNoSpecifiedTests=false

  Both extra flags are load-bearing on a fresh clone, which is the only state this repo promises:
  `-am` because `domain` and `epics` are 1.0.0-SNAPSHOTs published nowhere, so `-pl service` alone
  cannot resolve them, and `failIfNoSpecifiedTests=false` because `-am` then walks those two modules,
  which have no test by that name. (A plain `./mvnw verify` regenerates it too — the export is a test.)
  Note also that renaming a class the document names needs a `clean`: a stale nested-record `.class`
  in `target/` fails augmentation with `disagree on InnerClasses attribute`, which reads like a
  dependency conflict and is not one. This is the largest
  published surface of the six services and the one a client is generated from, so the diff is the
  review. It runs as a `@QuarkusTest` and indexes the test classpath, so any `@Path` resource under
  `src/test` lands in the document unless it is `@Operation(hidden = true)` — hence the annotation
  on `IdentityEchoResource`.
- **`mvn verify` passing does not mean the app starts.** Augmentation runs per `@QuarkusTest`
  regardless of packaging, so a missing `quarkus-maven-plugin` goal is invisible to the suite — it
  was in fact missed here once, an `<executions>` block under a `<build>` whose `<testResources>`
  came first, and only a boot caught it. `<packaging>quarkus</packaging>` is what closed that hole:
  it binds the goals to the lifecycle, and removing `<extensions>true</extensions>` now fails with
  "Unknown packaging: quarkus" rather than quietly building nothing. After touching
  `service/pom.xml`, still boot it and hit a route — and boot the **binary**, since a native-only
  failure is invisible to every JVM run:

      java --enable-native-access=ALL-UNNAMED -jar service/target/quarkus-app/quarkus-run.jar
      ./mvnw package -Dnative && ./service/target/qits-projects
- **`PackagedSurfaceIT` is one of the two tests that run against the artifact.** Every `@QuarkusTest`
  augments in the build JVM, with the whole classpath present and reflection unrestricted — a native
  image has none of those, and three real defects here were invisible to all of them and fatal to
  the binary (H2's `AUTO_SERVER` on a shipped datasource default, the missing `project-template/`
  resources, `RepositoryMetadata` unregistered for reflection). It runs under `-Dnative`, and
  `-DskipITs=false` runs it against the fast-jar. It launches a real process, so it reads main's
  `application.properties` and none of the test overrides: paths reach it through
  `quarkus.test.arg-line`, which is why the test resources carry that key. **The two databases
  cannot** — their urls name a port chosen at run time — so its `@TestProfile` hands the launched
  process both `QITS_RESOURCE_*` triples, the generic contract a deployment supplies, which leaves
  the jars' own `${…}` indirection under test rather than restating the datasource keys. Its
  embedded postgres reaches that profile through a **system property**, because a
  `QuarkusTestProfile` is instantiated in two classloaders and a static field is not shared between
  them.
- **`TokenValidationBootstrapIT` is the second, and it is the only place the OIDC tenant is ever
  ON.** The shipped tenant is gated —
  `quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}` — and every suite here leaves the
  gate shut, so the block this service deploys with (auth-server-url + `jwks-path` against a real
  listener, audience enforcement, the `groups` claim becoming roles) is exercised nowhere else. Its
  `@TestProfile` **extends `PackagedSurfaceIT.PackagedResources`** rather than copying it — what a
  launched qits-projects needs in order to boot is one answer — and adds only the gate, the mock
  idp's address, and the three darkening keys (otel, the eventstream bus, the self-seed). The far
  side is qits-service-mock's `MockIdp`, which serves a real JWKS for a generated keypair, mints
  tokens against it and **records what it answered**, so "the service fetched the keys at startup" is
  an assertion and not an inference.
  <br>It is also this repo's **first userflow**, and the one every other story class is ordered
  after — see **Userflows** below.
  <br>**`skipITs` stays `true` and no IT flips it**, unlike qits-githost-service's namesake:
  `PackagedSurfaceIT` is heavyweight (real git pushes, a pseudo-terminal), so the opt-in is per-run
  and per-class — `-DskipITs=false -Dit.test=<classes>`, which is exactly what the pipeline passes.
- **Anything read off the classpath by walking is a native-image question.** `ProjectTemplate` is
  the one such reader — it serves both committed skeletons, `project-template/` (a wrapper's first
  commit) and `repository-template/` (a blank component's) — and it handles three URI schemes:
  `file:` (tests), `jar:` (fast-jar) and `resource:` (native). Quarkus' own `ClassPathUtils.consumeAsPaths` handles the first two and
  rejects the third, which is how the binary came to fail every project creation while the suite was
  green. Anything new that enumerates a resource directory needs the same three, plus an entry in
  `quarkus.native.resources.includes` — a native image carries no resource it was not told about.
- **The sign-in terminal is the one thing a native build can break silently.** `ForeignPty`'s
  downcalls are the only native access in the process. `ForeignPtyTest` and `RemoteLoginSessionTest`
  drive real pseudo-terminals — including a prompt on `/dev/tty`, which is what git actually reads
  credentials from — and both are `@EnabledOnOs(OS.LINUX)`. `domain`'s surefire block passes
  `--enable-native-access=ALL-UNNAMED` for them; a JVM-mode `quarkus-run.jar` needs it on the
  command line, and the binary needs nothing.
- **Never let this process open a pts.** `ProcessBuilder`'s file redirects are opened by the
  *calling* process, not the child, and they carry no `O_NOCTTY` — so
  `redirectOutput(new File("/dev/pts/N"))` makes that terminal **this service's controlling
  terminal**, because the service is a session leader (PID 1 in its container, exec-form
  entrypoint). Closing the master then hangs *us* up, and Quarkus registers SIGHUP as a stop signal
  alongside TERM and INT: the container shut down gracefully with exit 129, three times, ~20-30s
  into every life somebody opened the sign-in dialog. The open belongs in the child
  (`RemoteLoginSessions.terminalProcess` wraps it in `sh -c 'exec 0<>"$0" …'`), where a
  non-session-leader doing it is harmless and `setsid --ctty` then claims the terminal deliberately
  for the child's own session. `RemoteLoginCttyTest` guards both halves — and note its symptom test
  has to relaunch under `setsid`, because a JVM that is not a session leader passes on the broken
  code too, which is exactly why the suite stayed green while production died. `HangupImmunity` is
  the backstop: SIGHUP is a WARN here, never a shutdown.
- A `Failed to start quarkus` / `Port already bound: 8081` failure is the known flake
  (`migration-plan.md` §9 item 14) — `@QuarkusTest` restarts racing for the test port. Re-run first.
- `GitFixtures.path("<name>.git")` is how a test gets a git origin to clone. It returns the
  committed bare fixtures (`submodule-*.git`) as they are and builds the derived ones
  (`testing-repo.git`, `demo-demo.git`, `qits-qits.git`) on first use. Never reintroduce the
  monorepo's `derive-fixture-bares` antrun step: it needs submodules a fresh clone has not got.
- `domain/src/test/java/…/testsupport/` holds the port implementations the suite runs against —
  `InMemoryProcessRegistry` (the monorepo's technical-process framework, vendored) and
  `RecordingWorkspaceLifecycle`. They are **test scope only**; nothing under `src/main` references
  them, and the published jars ship no implementation of any port. `service`'s suite reuses them
  through domain's test-jar rather than carrying a second copy. A fake for a port whose method
  **returns a result** must be `@Alternative @Priority` (`RecordingProjectDomainRegistrar`): a
  second implementation of the port would leave the caller reporting whichever the container hands
  it first. `ProjectDomainRegistrar` has no implementation under `src/main` today — the registrar in
  `notify/` went with qits-platform-dns — so the fake stands alone and the annotations are kept as
  the rule rather than repaired away. A recording fake for a `void` port does not need this, which is
  why the older two do not have it.
- Where a monorepo assertion queried another context's table, it is re-expressed against the seam —
  "did this context ask?" rather than "did the other context's row appear". `RecordingWorkspaceLifecycle`
  exists for exactly that.
- Integration tests (`*IT`) that need real docker default to skipped (`skipITs` in the parent pom).

## Userflows

`service/src/test/java/eu/wohlben/qits/projects/stories/` is this repository's **user-story
catalogue**. Each `@UserStory` method is a browserless walk (an `Interactions` parameter and no
`Flow`, so the transitive Playwright launches nothing) that emits
`service/target/userstories/<category>/<story>/` — a `userflow.json` sidecar, a markdown rendering
and a self-contained HTML page carrying the story's **network diagram**. The framework is
`qits-userflows`, test scope, pinned on its own `qits.userflows.version` because it is released out
of `components/qits-userflows/qits-userflows-javalib` and not out of the integrations reactor.

**Every story is a `@QuarkusIntegrationTest` against the packaged artifact, and that is not a
preference.** Inside a `@QuarkusTest` the shipped `%test` dev user holds all four platform roles and
the OIDC tenant is off, so *every door in this service is open to a plain `given()`* — a refusal
cannot be observed at all and a route assertion proves nothing about what deploys. A launched
artifact runs in `NORMAL` mode with the tenant on and no dev user, which is the first moment
`qits:admin` and `qits:system` mean different things. That is why `stories/refusals/` exists and why
its three stories cannot move into the surefire suite.

**One `@TestProfile` across every story class** — `TokenValidationBootstrapIT.PackagedWithMockIdp` —
so the whole catalogue runs against **one** launched process and one embedded postgres. A second
profile is a second boot and a second minute; every shared seam belongs in that one profile.

**Class order is load-bearing, and it is FQCN-alphabetical within the profile group.** A cumulative
capture source is attributed by a cursor, so traffic recorded before any story ran — the startup
JWKS fetch — lands in whichever story drains *first*, which must be the story about it. Hence
`…projects.api.TokenValidationBootstrapIT` (`api` sorts before `stories`) owns the boot, and the
packages under `stories/` are named so that alphabetical order **is** the intended order:
`catalogue` → `planning` → `refusals` → `review`, with the read-only review last because it reads
what the first two put there. Each story also declares `@UserflowRunsAfter` for the same order, so a
later package rename cannot silently reshuffle the diagrams. Every class is nonetheless runnable on
its own (`-Dit.test=EpicPlanningIT`), because the fixture and both far-side floors are per-JVM
idempotent rather than per-order — see below.

**The diagram is observed, never narrated.** `Interactions` records notes; nothing draws an edge by
hand. Three taps feed `NetworkCapture` and there is no fourth:

| tap | what it draws | where it lives |
| --- | --- | --- |
| `NetworkTaps.restAssured("qits-projects")` | `<actor> -> qits-projects`, one edge per request a story makes, labelled `METHOD <scrubbed path> -> <status>` | the framework ships it; installed from each story class's `@BeforeAll`, idempotent per service |
| `MockIdp.recordedRequests()` | `qits-projects -> qits-platform-idp` — the startup JWKS fetch | registered as a cumulative `NetworkCapture.source` in `TokenValidationBootstrapIT` |
| `GitHostFixture`'s access log | `qits-projects -> qits-githost` — the lifecycle `PUT`/`GET`, the smart-HTTP advertisement and pack, and the mirror fetches | `stories/support/StoryGitHost` |

The local `StoryNetworkFilter` this repo carried beside the IT is **deleted**: the framework ships
that tap now (`qits-userflows` 2026.829.201516), and a per-repo copy is exactly the thing that goes
out of step. The tap's default skip is any path with a `/q/` segment, which is right here —
`quarkus.http.non-application-root-path` is `/projects/q`, so the readiness probe is out of every
diagram and no route this service owns is.

**The launched process needs a git-host credential, and that is a real finding rather than test
plumbing.** `HttpGitHostRepositories` fails **closed**: every lifecycle call asks `IdpGitHostBearer`
for a machine token and throws `No machine bearer is available for qits-githost` rather than sending
one unauthenticated. The shipped default is `quarkus.oidc-client.githost.client-enabled=false`, so a
packaged process with no idp configured **cannot create a repository at all** — which is correct in
production and is why `PackagedWithMockIdp` now points that named client at the same `MockIdp` and
stubs `POST /idp/token` on it.

**One route is excluded from every diagram, and it is the cached-read exclusion.** That token fetch
is `client_credentials`, cached by quarkus-oidc-client for the token's whole hour, so it happens
exactly **once per run** — whichever story publishes first would draw an arrow the identical story
would not draw if it ran second, and the `networkHash` would move with nothing having changed. The
source filters `/idp/token` out and the dependency is stated here instead. The `GET /idp/jwks`
startup fetch stays: it happens once too, but the story it lands in is *about* it.

**The git-host tap is a file, and it has a floor.** `GitHostFixture` appends one
`METHOD URI STATUS` line per answered request to `target/it-git-host-fixture-access.log` — outside
the bare root, which `start()` wipes — because the caller is a packaged process on the far side of a
socket and a `QuarkusTestResourceLifecycleManager` and a story method need not share a classloader.
`StoryGitHost.install()` takes the current end of that file as its floor, and the supplier it
registers is **cumulative and prefix-stable**: it returns every edge harvested so far, in arrival
order, so a later story's slice can never shift an earlier one's. It excludes no line on merit —
unlike qits-ci-service's namesake, which drops a cached listing — because the one throttled read this
service makes is handled at the other end instead, by the story waiting the window out (see below).

**Fixture setup must be invisible to both taps.** `stories/support/StoryPlatform` builds the one
shared project and component with a plain `java.net.http.HttpClient` — the RestAssured tap is
JVM-global once installed, so a fixture built through `given()` would draw arrows nobody walked —
and it seeds the bootstrap's already-existing bare straight onto the fixture's disk with
`git init --bare`, a plane neither tap can see. Its git-host traffic is bounded by **order**
instead: `provision()` runs before `StoryGitHost.install()`, and install is what takes the floor.

**Both of those go in `@BeforeEach`, not `@BeforeAll`.** `RestAssured.port` is set by the Quarkus
integration-test extension's *beforeEach* callback and cleared back to `-1` in afterEach, so a
`@BeforeAll` that builds a URL from it produces `http://localhost:-1`. Both calls are idempotent per
JVM, so the fixture is built once, before the first story of whichever class runs first.

**What the stories claim, and where the negatives are.** A presence check cannot say "and nothing
else happened", which is most of what is worth knowing about this service:

| category | story | the claim only a negative can make |
| --- | --- | --- |
| `authentication` | the startup JWKS fetch; a stranger's token refused | — |
| `authorization` | a browser session at the two `qits:system` doors; a machine bearer at the planning surface; an anonymous caller | `assertNoEdgesTo(qits-githost)` — a refusal is decided at the door, so no work is done for a caller about to be refused |
| `catalogue` | a project created and its wrapper published; a component joined; the bootstrap's adoption | `assertEdgeCount(3)` on the adoption — it asks the git host **once** and clones, pushes and mirrors nothing |
| `planning` | a plan proposed and frozen; a task marked implemented | `assertOnlyEdgesFrom(<one person>)` — the whole planning surface is rows in this service's own `epics` database |
| `operations` | an operator reviews the catalogue; opening a project's components | `assertEdgeCount(4)` + one initiator on the reads, against the **one** read that is not free: the component list refreshes the wrapper's mirror from the git host, because membership is a file rather than a column |

That last pair is the finding worth carrying: **`GET /projects/{id}/repositories` is a git fetch.**
It joins the project's rows to the wrapper's `.gitmodules`, which lives in a repository, so
`WrapperReconcileService.view` → `WrapperSubmoduleWriter.readGitmodules` → `RepoMirror.refresh` →
`git fetch` against the git host. It is throttled — `RepoMirror.refresh()` trusts a mirror fetched
inside `qits.projects.git.mirror-freshness-ms` (5s) — so it is *at most* one fetch per wrapper per
window, not one per request. That throttle is also why the story has to **wait the window out**
before its read (`StoryPlatform.awaitMirrorFreshnessLapse`): without it, whether the arrow appears
depends on how long the neighbouring story took, which is a `networkHash` that never settles.
Anything that puts this route behind a poll faster than the window is polling the git host.

**Running them:**

    ./mvnw -pl service -am -DskipITs=false -Dquarkus.quinoa=false verify \
      -Dtest=SKIPNONE -Dsurefire.failIfNoSpecifiedTests=false \
      -Dit.test=TokenValidationBootstrapIT,ProjectCatalogueIT,EpicPlanningIT,AccessRefusalIT,CatalogueReviewIT

`-Dit.test` takes commas; `-Dtest=SKIPNONE` keeps the unit suite out of an IT-only run (run it
separately before committing — the story classes share `domain`'s fixtures). `skipITs` stays `true`
in the root pom because `PackagedSurfaceIT` is heavyweight, so the opt-in is per-run and per-class.
The userflow half of `.config/qits/ci-event-release-request.yml` runs exactly this list at every
release-request fold and publishes the bundle as the docs site `@userflows/qits-projects`. It
declares `gating: false`, so a red story shows the run red without holding the fold at the release
gate; **a new story class has to be added to that list**, or it is written and never run.

## What is deliberately absent

Grep for `SEAM (migration-plan.md` to find every place this repo cut something rather than carrying
it. Each one names what was removed and where it belongs. Do not "restore" any of them here — they
are another context's code, and the monorepo still has every line.

**The metadata sidecar is gone, not migrated.** `MetadataService`, `RepositoryDiscoveryService` and
`RepositoryMetadata` used to write `<data-dir>/<repoId>/metadata/repository.json` beside every bare
origin and restore a row's `url`/`archetype` from it at every boot. `url` and `archetype` are
columns on `Repository`, in this service's own database, in the same transaction that writes them —
the sidecar could only ever undo a row change, which is why `attachBackupRemote` and
`ProjectService.adoptWrapperRepository`'s promotion arm used to have to rewrite it. Decoupling from
the shared `qits-repositories` volume (projects-volume-decoupling-plan.md §1.4, BQ) removed the one
scenario the sidecar served ("database wiped, volume kept") along with the volume itself. Do not
bring any of the three back.
