-- Whether the assignee was written by a person rather than by a dispatch (qits-2259): a person who
-- claims an epic or a ticket by writing its assignee is saying they are on it, and no press — a
-- person's or the delivery desk's — puts an agent on it while that claim stands.
--
-- WHY A FLAG AND NOT A READING OF THE ASSIGNEE. Every successful press writes the dispatched agent's
-- identity into the assignee (qits-887) and nothing clears it, so the next phase's press must still
-- go through; and the agents' identities have no one shape — legacy rows hold `claude` or
-- `tok-workspace-…` — so the text cannot tell a claim from an agent. Only the write knows which it
-- was, so the row records it.
--
-- WHO WRITES IT. The create, the PATCH and the PUT-shaped transition set it when they write an
-- assignee that differs from the one the row holds (the same value restated changes nothing, so the
-- SPA's whole-row save never turns an agent into a claim); clearing the assignee clears it; the
-- dispatch's own write clears it.
--
-- FALSE FOR EVERY EXISTING ROW. Nothing tells a claim from an agent in the rows written before this
-- column, which is the reason for it; an existing assignee keeps dispatching as it did.
alter table entity add column assignee_claimed boolean not null default false;

comment on column entity.assignee_claimed is
    'EPIC and TICKET: true when the assignee was written by a person (create, PATCH or the PUT-shaped'
    ' transition, changing it to a different non-blank value), which refuses every dispatch press'
    ' while it stands; false when no assignee is set or a dispatch wrote it. Cleared with the'
    ' assignee and by every dispatch.';
