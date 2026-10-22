-- A REFINEMENT'S OWN TOKEN (qits-1482): one `qits_tok_` per refinement row, of idp context kind
-- `refinement`, contextId the row id, claims {project, entity: <entity_id>} and gitRefs []. It is
-- the successor of the commissioned client (`commissioned_client_*`, V4), which qits-770 retires;
-- the two live side by side until then. The `entity` claim is what binds the bearer to this one
-- room (`EntitiesAgentAccess.requireEntity`).
--
--   token_id       the id to revoke it with (discard, entity resolution, the reconcile)
--   token_value    the credential itself, held for the session that will run on it
--   token_subject  the `sub` it introspects as
--
-- Typed as `front_desk.token_*` (V37, V48). Nullable: a row opened while qits-idp was unreachable
-- holds none until `RefinementTokenReconcile` mints it, and every row that exists predates the
-- column. V50 rather than V49 because the delivery-desk half of qits-1482 takes V49 in parallel.
alter table refinement add column token_id text;
alter table refinement add column token_value text;
alter table refinement add column token_subject text;
