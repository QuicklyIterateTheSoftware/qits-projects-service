-- THE FRONT DESK'S SESSION TOKEN (qits-1482): a third qits_tok_ per desk, of idp context kind
-- `desk-session` (qits:agent), claims {project}, gitRefs []. It is minted beside the desk token
-- (token_*, V37) and revoked with the row; FrontDeskTokenReconcile reaps one no row names.
--
-- It is in no container's environment: the daemon is to be handed it over the control socket, so
-- the desk's sessions run on a principal of their own rather than on the daemon's. Nullable for the
-- desk token's reason (V37): a failed mint is retried by the sweep.
alter table front_desk add column session_token_id text;
alter table front_desk add column session_token_value text;
alter table front_desk add column session_token_subject text;
