-- The ticket lifecycle becomes five phases, and the ticket gains the field the first one starts
-- from.
--
-- WHY FIVE WORDS WHERE V4 WROTE TWO. V4's status answered "is this outstanding?", which is a
-- question about a list rather than about the work. What a ticket actually goes through is
-- REPORTED -> REFINED -> IMPLEMENTED -> VERIFIED -> DONE, and the reading that makes those five
-- words a lifecycle rather than a task board is this: THE STATUS IS WHAT HAS BEEN ACHIEVED, and the
-- phase that runs while it holds is what happens next. REPORTED means somebody said what is wrong
-- (refine runs), REFINED means the ticket says what to do (implement runs), IMPLEMENTED means the
-- change is released and deployed (verify runs), VERIFIED means it no longer occurs on the platform
-- (a person closes it), DONE means closed. So no status names work in flight, there is no reject
-- verb — a failed verification is the ordinary backward move IMPLEMENTED -> REFINED — and nothing
-- is terminal: DONE reopens to VERIFIED like every other move. Moves are adjacent-only, in either
-- direction, and TicketLifecycle is where that graph lives.
--
-- The conventions are V4's, unchanged: unquoted mixed-case table names folding to lower case,
-- `text` for markdown, named ck_ constraints spelling out the closed enum vocabulary.

-- Why the ticket came about, in the reporter's or the triage agent's words. `description` was the
-- intake field and is now the REFINEMENT'S OUTPUT, so a ticket needed somewhere to keep the thing
-- that was originally asked for — a later phase must never be able to overwrite it by writing up
-- what was decided.
--
-- NULLABLE, and deliberately so although every intake surface requires one: the rows that already
-- exist were filed before the field did, and a migration that invented an impetus for them would be
-- putting words in a reporter's mouth. Backfilling from `description` would be exactly that, and
-- would also produce the essay-length impetus the field's length rule exists to prevent.
alter table Ticket add column impetus text;

-- The vocabulary widens. Dropped first, because the two updates below write words the old check
-- does not know; re-added named, exactly as V4 left it, so the next widening is an ordinary drop.
alter table Ticket drop constraint ck_ticket_status;

-- THE TWO REMAPPINGS, and the reason is one sentence: today's ticket descriptions are already
-- refinement-grade. Every OPEN ticket in this database was filed with a body saying what to do
-- about the problem — that was what `description` was for — so none of them is a bare report and no
-- open ticket goes back through phase 1. REFINED is therefore the honest landing place: the work is
-- described and waiting to be implemented.
update Ticket set status = 'REFINED' where status = 'OPEN';

-- RESOLVED said "somebody dealt with this and closed it", which is what DONE says. It is NOT
-- VERIFIED: nobody checked these against the platform, and claiming they were verified would be
-- asserting a phase that never ran. DONE reopens one step to VERIFIED if it turns out otherwise.
update Ticket set status = 'DONE' where status = 'RESOLVED';

alter table Ticket add constraint ck_ticket_status
    check (status in ('REPORTED','REFINED','IMPLEMENTED','VERIFIED','DONE'));
