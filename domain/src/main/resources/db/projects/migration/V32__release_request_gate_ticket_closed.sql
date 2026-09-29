-- When the ticket filed for this request's red gate (gate_ticket_id) was dealt with for the request's
-- ENDING -- FINALIZED, WITHDRAWN or OBSOLETE. A MAINTENANCE ticket is closed by the platform when its
-- request ends; the close is a write into the epics database, a different physical database, so it
-- happens after the ending has committed and can fail on its own. This column is what makes that
-- failure recoverable: an ended request with a ticket and no stamp is the reconcile sweep's worklist.
--
-- Null on every existing row, which is the point: the requests that ended before the platform closed
-- anything are exactly the backlog the first sweep works through.
alter table release_request add column gate_ticket_closed_at timestamp with time zone;
