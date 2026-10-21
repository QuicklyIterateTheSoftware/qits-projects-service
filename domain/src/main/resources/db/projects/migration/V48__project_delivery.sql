-- THE FRONT DESK'S DELIVERY LOOP (epic qits-1440, task qits-1490).
--
-- Declared in the project's wrapper at `.config/qits/project.yml` under `front_desk.delivery`
-- (ProjectConfigParser) and stored by the same reconcile that stores `front_desk_lifecycle` (V35).
-- V48 rather than V47 because epic qits-1038 takes V47 in this history in parallel.
--
-- Every column is NOT NULL with the value an absent key means, for V28's and V35's reason: every
-- row that exists predates the key, and absent means the loop is off with every value at its
-- default. `delivery_approves` is the set of approval requirements the desk may satisfy, sorted and
-- space-separated (empty is none); `delivery_interval` is an ISO-8601 duration. Ranges are the
-- parser's to refuse -- a malformed file keeps the stored values -- so no check constraint restates
-- them here.
alter table Project add column delivery_enabled boolean not null default false;
alter table Project add column delivery_max_running int not null default 2;
alter table Project add column delivery_start_refined boolean not null default false;
alter table Project add column delivery_approves text not null default '';
alter table Project add column delivery_interval text not null default 'PT10M';

-- THE DELIVERY TOKEN: a second `qits_tok_` per desk, of idp context kind `desk-delivery`, minted
-- while the project's loop is enabled and revoked when it is disabled. Nullable for the desk
-- token's reason (V37): a desk whose loop is off holds none, and a failed mint is retried.
alter table front_desk add column delivery_token_id text;
alter table front_desk add column delivery_token_value text;
alter table front_desk add column delivery_token_subject text;
