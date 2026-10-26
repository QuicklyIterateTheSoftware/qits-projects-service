-- THE REFINEMENT CONTAINERS ARE RETIRED (epic qits-770, task qits-1316).
--
-- Every refinement room is a session in its project's front desk now (V58). The legacy container
-- rooms — the rows V58 left with a `refining/<slug>` branch — become ordinary session rooms: their
-- `last_demand_at` is null, so their session is desired STOPPED until somebody ensures it. Every row
-- is KEPT: its designs, prompt attachments and draft are keyed by refinement_id and cascade from it.
--
-- What the containers left outside this database — the qits-containers places with their
-- `qits_refinement_<rowId>` volumes, the per-room commissioned idp clients of kind `refinement`, and
-- the `refining/*` branches on the wrappers — is removed at boot by
-- refinementhost/LegacyRefinementContainers, which needs none of these columns to find them.
--
--   branch, parent                                    the container room's branch and its fork
--                                                     point; nothing cuts a branch any more.
--   commissioned_client_id, commissioned_client_secret  the container's idp client pair; the room's
--                                                     own token (V50, token_*) is what a session
--                                                     runs on, and it is untouched.
alter table refinement drop column branch;
alter table refinement drop column parent;
alter table refinement drop column commissioned_client_id;
alter table refinement drop column commissioned_client_secret;
