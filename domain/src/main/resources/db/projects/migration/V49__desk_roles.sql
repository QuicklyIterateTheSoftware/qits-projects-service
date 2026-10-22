-- A PROJECT HAS DESKS, EACH WITH A ROLE (qits-1482, design section 1).
--
-- Until now a project had one front desk, keyed by its project id, and the delivery loop
-- (epic qits-1440, V48) ran inside it -- so every session in that container could read the loop's
-- privileged token out of PID 1's environment. The loop now runs in a desk container of its own:
--
--   FRONT     the project's front desk, as before. desk_id = project_id, so every path, socket and
--             proxy URL already addressing it (/projects/daemon/<projectId>,
--             /projects/container/<projectId>/) and every runner key stays byte for byte the same.
--   DELIVERY  the delivery loop's desk, wanted only while the project's delivery loop is enabled.
--             desk_id = '<projectId>-delivery'.
--
-- The primary key moves from project_id to desk_id; (project_id, role) is unique, so a project has
-- at most one desk of each role. The foreign key to project(id), with its cascade, stays on
-- project_id: a project's deletion still drops every desk it has.
--
-- Every row that exists is a front desk, so the backfill is desk_id = project_id, role = FRONT. Its
-- delivery_token_* values, where it holds any, are left for the application: the token belongs to
-- the DELIVERY row now, and FrontDesks revokes a front desk's one at qits-idp before clearing it --
-- a migration can clear a column but cannot revoke a token (FrontDeskTokenReconcile reaps one
-- either way: a desk-delivery token no DELIVERY row claims is unclaimed).
alter table front_desk add column desk_id varchar(255);
alter table front_desk add column role varchar(16);
update front_desk set desk_id = project_id, role = 'FRONT';
alter table front_desk alter column desk_id set not null;
alter table front_desk alter column role set not null;
alter table front_desk alter column project_id set not null;
alter table front_desk add constraint ck_front_desk_role check (role in ('FRONT', 'DELIVERY'));
alter table front_desk drop constraint front_desk_pkey;
alter table front_desk add constraint pk_front_desk primary key (desk_id);
alter table front_desk add constraint uq_front_desk_project_role unique (project_id, role);

-- THE FRONT DESK'S SESSION TOKEN: a third qits_tok_, of idp context kind `desk-session` (qits:agent),
-- minted for the FRONT row beside its own token and revoked with it. It is in no container's
-- environment: the daemon is to be handed it over the control socket, so a desk session runs on a
-- principal of its own rather than on the daemon's. Nullable for the desk token's reason (V37): a
-- failed mint is retried by the sweep.
alter table front_desk add column session_token_id text;
alter table front_desk add column session_token_value text;
alter table front_desk add column session_token_subject text;
