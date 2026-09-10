-- The figures a dossier page inlines, COPIED into the epic rather than referenced.
--
-- A separate file from V5 so the storage feature and the figures feature ship independently.
--
-- WHY A COPY. Both things a page can inline -- a refinement_prompt_attachment image and a
-- refinement_design document -- live in domain's database and cascade away when the refinement is
-- discarded. Linking to them would break every dossier the day its refinement is discarded, which
-- is precisely the day the plan matters most. So inserting a figure copies its bytes here, under
-- the epic, and the page renders from this table for ever after.
--
-- THE ID IS THE SOURCE FIGURE'S ID, copied deliberately -- it is NOT a new UUID. Two things follow
-- and both are the reason: a markdown URL written once stays valid, so nothing has to rewrite
-- somebody's prose; and re-inlining the same figure is idempotent, so the same sketch pasted onto
-- two pages is one row. It also makes the Sketch and Design tabs' "in use / dangling" filter one id
-- join instead of a stored back-reference nobody would maintain.
--
-- THE BYTES ARE A SNAPSHOT, NOT A MIRROR. A design rewritten upstream does not change a page that
-- already argued from it. That is a feature: a dossier page reads months later as the argument it
-- was, not as an argument about a document that has since moved.
create table dossier_asset
(
    id           varchar(255) not null primary key,
    epic_id      varchar(255) not null references Epic (id) on delete cascade,
    kind         varchar(16)  not null check (kind in ('IMAGE', 'DESIGN')),
    mime_type    varchar(255) not null,
    label        varchar(512) not null,
    bytes        bytea        not null,
    created_at   timestamp(6) with time zone not null,
    causation_id uuid
);
create index ix_dossier_asset_epic on dossier_asset (epic_id);

-- The reverse index, rewritten on every page save: which pages name which assets. Reference
-- counting rather than a sweeper -- an asset whose last reference disappears is deleted in the same
-- transaction as the save that dropped it, so a dossier is self-contained at every instant and
-- there is no seal-at-a-milestone step that can fail to run.
create table dossier_page_asset
(
    page_id  varchar(255) not null references dossier_page (id) on delete cascade,
    asset_id varchar(255) not null references dossier_asset (id) on delete cascade,
    primary key (page_id, asset_id)
);

-- For the refcount check ("does any page still reference this asset"), which runs on every save.
-- The primary key already indexes (page_id, asset_id); this is the other direction.
create index ix_dossier_page_asset_asset on dossier_page_asset (asset_id);

comment on table dossier_asset is 'Epic-owned copies of inlined figures, keyed by the SOURCE figure''s id.';
comment on column dossier_asset.bytes is 'A snapshot: rewriting the source does not change a page that argued from it.';
