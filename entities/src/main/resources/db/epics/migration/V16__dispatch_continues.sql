-- The continue-or-stop bit of the one dispatch path (qits-394): whether the phase advance carries
-- an entity's run on by itself when its agent claims a transition, or stops after one phase.
--
-- WHY IT IS ON THE ENTITY. The press that decides it ("Dispatch" or "Run the next phase") and the
-- transition that reads it are different requests, minutes or hours apart, so the bit has to be on
-- a row. The workspace the run stands in is qits-workspaces' row, in another service's database;
-- nothing in this service persists a dispatch. The entity is the one row here that represents the
-- run of work, and every press writes the bit onto it afresh.
--
-- WHY THE DEFAULT IS TRUE AND EPICS ARE BACKFILLED FALSE. Both halves keep what each archetype did
-- before this column existed. A ticket's phases always carried themselves on (TicketPhaseAdvance
-- delivered the next prompt on every transition), so every ticket row — existing and new — says
-- true. An epic's dispatch was single-shot and no epic transition delivered anything, so an epic
-- already standing in a workspace must not start receiving prompts on its next board move: those
-- rows say false until somebody presses one of the two actions on them, which writes the bit.
-- Features and tasks are never dispatched and never read it; they take the default like any row.
alter table entity add column dispatch_continues boolean not null default true;

update entity set dispatch_continues = false where archetype = 'EPIC';

comment on column entity.dispatch_continues is
    'EPIC and TICKET: true when the last dispatch press asked for the whole flow (the next phase is'
    ' delivered on every transition), false when it asked for one phase. Written by every press and'
    ' by nothing else. The release asked for at VERIFIED does not read it.';
