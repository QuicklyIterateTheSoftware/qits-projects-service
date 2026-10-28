-- A DESK TRIGGER LINE IS ADMITTED ONCE, BY THE SCHEMA (qits-1694).
--
-- Every source dedups with a count of desk_trigger_event and then inserts, and the only unique key
-- (V63 desk_trigger_event_one_line) is per window and includes the label. A request-stalled label
-- carries the elapsed time ("for 2h 5m") and a line may land in a later window, so two concurrent
-- admitters (two replicas, or a sweep racing the listener) could both insert. The count stays as
-- the fast path; these are what make it safe at more than one replica:
--
--   desk_trigger_event_once          (source, event_id, item_ref): one line per event and item,
--                                    across every window. Not (source, event_id) alone:
--                                    work-verified fans one EntityTransitioned out to several items
--                                    under one event id.
--   desk_trigger_event_one_per_agent (source, item_id) for capacity-opened: that source's own rule,
--                                    one line per agent, ever (item_id is the agent's id).
--
-- DeskTriggerWindows.admit inserts with an untargeted "on conflict do nothing", so a row either
-- index refuses is absorbed and never aborts the admitting transaction. The duplicates already
-- stored are deleted first, keeping the earliest admitted (id breaks a tie).
--
-- project.delivery_enabled_at is when front_desk.delivery.enabled last went false -> true, stamped
-- by the reconcile (ProjectConfig.DeliverySettings.applyTo). request-stalled measures a stall from
-- the later of it and the stall's own start, so turning delivery on does not admit every stall of
-- the past week at once. Nullable, no backfill: null is "no clamp", which is right for a project
-- whose delivery was already on — its desk has been there to be told all along.
delete from desk_trigger_event e
 using desk_trigger_event keep
 where keep.source = e.source
   and keep.event_id = e.event_id
   and keep.item_ref = e.item_ref
   and (keep.admitted_at, keep.id) < (e.admitted_at, e.id);

delete from desk_trigger_event e
 using desk_trigger_event keep
 where e.source = 'capacity-opened'
   and keep.source = e.source
   and keep.item_id = e.item_id
   and (keep.admitted_at, keep.id) < (e.admitted_at, e.id);

create unique index desk_trigger_event_once on desk_trigger_event (source, event_id, item_ref);

create unique index desk_trigger_event_one_per_agent on desk_trigger_event (source, item_id)
  where source = 'capacity-opened';

alter table project add column delivery_enabled_at timestamp with time zone;
