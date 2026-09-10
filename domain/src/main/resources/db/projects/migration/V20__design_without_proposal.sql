-- A design stops being a proposal. What V5 built was a two-state review flow -- an agent proposes,
-- a person replaces, keeps or discards -- and the states are gone: a design is now a titled HTML
-- document written and rewritten in place, like the epic's own description. The gate on a draft is
-- the epic's REFINING -> IMPLEMENTATION transition, which a person controls and which freezes the
-- whole plan at once, rather than a review queue bolted onto a scratch surface.
--
-- THIS IS A COLUMN DROP, NOT A DATA MIGRATION -- no document can be lost. Every row keeps its id,
-- its title and its html. A row that stood at PROPOSED and was never resolved simply becomes a
-- design in the list: visible, renameable, deletable, and rewritable like any other. The obvious
-- worry on reading `drop column status` is that pending proposals are discarded; they are not.
--
-- based_on_design_id is a self reference with ON DELETE SET NULL. Dropping the column drops that
-- constraint with it, so no separate `drop constraint` is wanted here.
--
-- version replaces the review flow's ordering: with nobody accepting a write, in-place writing is
-- the only writing there is, and a person editing in the SPA while an agent writes from a prompt is
-- the ordinary case. It starts at 0 and is bumped by the control class, never by a trigger.
--
-- ONE CORRECTION TO V5'S COMMENT, which cannot be edited in place because it is applied: V5 says
-- "there is no content route serving these bytes and there must not be one -- agent-authored HTML
-- served same-origin would be an XSS door". That rule still holds FOR refinement_design, and this
-- migration does not lift it. What changed is that a COPY of a design, in the epics database as a
-- dossier_asset, is served from one hardened route that sets `Content-Security-Policy: sandbox`
-- on the response itself, so the document lands in an opaque origin even when its URL is opened
-- directly. The prohibition was scoped, not lifted: refinement_design's own bytes still travel as
-- a JSON field and nothing else.
alter table refinement_design drop column status;
alter table refinement_design drop column based_on_design_id;
alter table refinement_design drop column note;
alter table refinement_design add column version bigint not null default 0;

comment on table refinement_design is 'Titled HTML designs of one refinement, written and rewritten in place; no lifecycle.';
comment on column refinement_design.version is 'Bumped on every write; a write carrying a stale version is refused with 409.';
