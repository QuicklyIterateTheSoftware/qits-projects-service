-- WHAT EACH HARNESS BINARY CAN BE CONFIGURED WITH, AS REPORTED BY A CONTAINER THAT HOLDS IT.
--
-- The model and effort dropdowns in the surface editor are not a list this platform may hardcode.
-- The valid values belong to the harness binary inside the workspace image: they differ per harness
-- (Claude Code has `--effort` and no command that lists models; Kimi has `kimi provider list --json`
-- and no effort concept at all) and they change when the image is rebuilt. So the values are
-- DISCOVERED, by running the binaries, which can only happen where they are — inside a container.
--
-- This table is the host-side cache of that discovery. A daemon probes once at container start,
-- answers the report on `GET /agents/available`, and it is written here; the editor reads only this
-- table. Nothing on the editor's request path spawns a process or waits on a container, which is the
-- whole reason the cache exists — the editor is a platform-wide route with no container in front of
-- it.
--
-- KEYED BY HARNESS **AND** IMAGE VERSION, and the second half is not bookkeeping. A report is only
-- true of the binary that produced it, and two containers on this platform can be running two
-- different builds of the workspace image at the same time (the project's agent container and a
-- given workspace's are provisioned independently). Merging their answers into one union would
-- offer the editor a set of models no single binary actually has. So each (harness, image version)
-- pair keeps its own row, the read picks the NEWEST REPORT and names the image version it came
-- from, and the older ones are listed beside it rather than folded into it.
--
-- AN EMPTY TABLE IS A SUPPORTED STATE. A fresh estate has never started a container, and the editor
-- must still work: a harness with no row reads as the library's shipped fallback
-- (`control/AgentCapabilityDefaults`), flagged `shipped`. That is the same absent-is-not-broken rule
-- `agent_surface_configuration` follows one migration back.
--
-- NOTHING HERE IS OPERATOR-EDITABLE. Every row is written by a report; there is no editor door onto
-- this table, and there must not be one — a hand-typed model list is exactly the hardcoded catalogue
-- this feature exists to remove.
create table agent_harness_capability
(
    -- A synthetic string id, as everywhere else in this module. The identity of a row is the
    -- (harness, image_version) pair below, which the unique constraint carries.
    id                text        not null primary key,

    -- CLAUDE or KIMI. No check constraint, for the reason `surface_key` carries none: the harness
    -- vocabulary lives in `entity/AgentHarness` and a third harness must be a Java constant rather
    -- than a migration. A row naming a harness this service does not know is ignored on read.
    harness           text        not null,

    -- The workspace/agent image build the reporting container runs — the CalVer the deployer pinned.
    -- Empty is allowed and means the reporter could not name its image: the row is still keyed and
    -- still readable, and it simply cannot be told apart from another unnamed build.
    image_version     text        not null,

    -- What the harness answers for `--version`, verbatim. Empty when the probe could not read it.
    harness_version   text        not null,

    -- Newline-delimited, in the order the harness enumerated them. Never joined on and never queried
    -- by — an ordered list of opaque strings, the same reading `allowed_tools` gets one table over.
    models            text        not null,
    effort_levels     text        not null,

    -- WHETHER THE HARNESS ENUMERATED ITS MODELS AT ALL, which is a different fact from the list
    -- being empty. Claude Code has no listing command, so its models are a shipped alias set and
    -- this is false: the editor must show the free-text escape as the primary way in rather than as
    -- an afterthought, because pinning a full model id is exactly what someone comes here for.
    models_enumerated boolean     not null,

    -- WHETHER THE HARNESS HAS AN EFFORT CONCEPT. False for Kimi, which has no effort flag at all —
    -- and the editor must then show NO effort control, not a disabled one carrying Claude's values.
    -- Again distinct from an empty level list, which would mean "has the concept, could not read the
    -- levels".
    effort_supported  boolean     not null,

    -- The harness's authentication state on the credential volume this container mounts, from
    -- feature e560229a: auth is a property of the harness and the volume, not of a surface, and it
    -- belongs in the same report as the rest of "what can this harness do right now".
    authenticated     boolean     not null,
    auth_detail       text        not null,

    -- A PROBE THAT FAILED STILL WRITES A ROW. The lists then hold whatever the fallback supplied and
    -- this says so, because "the dropdown is stale because the probe broke" and "the dropdown is
    -- what the binary offers" must not look alike to whoever is reading the editor.
    probe_failed      boolean     not null,
    probe_detail      text        not null,

    -- WHO reported, for the operator who wants to know which container's answer they are looking at.
    -- Free text supplied by the reporter (`qits-projects-daemon`, a workspace id); never trusted for
    -- anything but display.
    reported_by       text        not null,

    -- WHEN the report arrived here. This, not the image version string, is what "newest" means on
    -- read: an image version is a name and a report is an event, and only the second has a time.
    reported_at       timestamptz not null,

    causation_id      uuid,

    constraint uq_agent_harness_capability unique (harness, image_version)
);

create index ix_agent_harness_capability_harness on agent_harness_capability (harness, reported_at desc);

comment on table agent_harness_capability is 'Per (harness, image version) cache of what a harness binary reported it can be configured with.';
comment on column agent_harness_capability.models_enumerated is 'False when the list is a shipped alias set rather than something the binary printed.';
comment on column agent_harness_capability.effort_supported is 'False for a harness with no effort concept — the editor shows no control at all, not a disabled one.';
comment on column agent_harness_capability.reported_at is 'Arrival time. This decides which image version wins a read; the version string is a name, not an order.';
