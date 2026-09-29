-- The tickets the platform filed about its own red gates become MAINTENANCE, the type that lets it
-- close them when their release request ends (qits-578). They were filed as BUG until now, which the
-- platform cannot tell apart from a person's report -- except by who filed them: TicketUnattendedGate-
-- Tickets stamps created_by = 'qits-projects' (its REPORTER), and nothing else ever writes that value,
-- since created_by is taken from the request identity everywhere else and never client-supplied.
--
-- No constraint to widen: ticket_type has had no check constraint since the unified table (V9); the
-- vocabulary is enforced by the TicketType enum.
update entity
   set ticket_type = 'MAINTENANCE'
 where archetype = 'TICKET'
   and ticket_type = 'BUG'
   and created_by = 'qits-projects';
