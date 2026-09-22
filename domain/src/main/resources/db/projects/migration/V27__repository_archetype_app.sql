-- A TENTH ARCHETYPE: APP, A STANDALONE WEB APPLICATION.
--
-- `-frontend` says "served to a user at a URL" and says nothing about who serves it, which was fine
-- while every one of them was a microfrontend a service carried inside its own image. An Angular SSR
-- application is not that: it is its own server, its own image and its own deployment, and the one
-- thing that decides whether it needs a deployment of its own is exactly the thing the archetype was
-- not recording. So it is a value rather than a flag -- the name is what says the kind here, and
-- `-app` is the suffix it says it with.
--
-- APP is a component of its project (RepositoryArchetype.APP(true)), so nothing about membership
-- moves: it is declared in the wrapper, mounted at components/<component>/<name>, removed on delete
-- and reported by the reconcile exactly as a SERVICE or a FRONTEND is.

-- The constraint is inline and NAMED in V1__init.sql, which is what makes this a migration rather
-- than an edit: that file is applied and checksummed, so touching it would refuse the next boot. A
-- named check cannot be widened in place either, so the widening is a drop and a re-add over the
-- wider set -- the two statements below are one change, and Flyway runs the file in one transaction.
alter table Repository drop constraint CK_repository_archetype;

-- The final ten. INTEGRATION and APPLICATION stay out: they were folded into LIBRARY and FRONTEND
-- and are gone from the enum, so a value this allowed would be one Hibernate could no longer read
-- back. APP is the only addition.
alter table Repository add constraint CK_repository_archetype check (archetype in
  ('PROJECT', 'SERVICE', 'DAEMON', 'LIBRARY', 'FRONTEND', 'APP', 'CLI', 'IMAGE', 'SERVICE_TEMPLATE',
   'FORK'));

-- Nothing is backfilled, and there is nothing that could be. No row carries APP today -- the value
-- did not exist until this file ran -- and no existing row can be turned into one by a rule: a
-- FRONTEND row is a microfrontend unless a person says otherwise, and guessing which of them is
-- really a standalone application is the one thing nothing downstream could correct. A rename to a
-- `-app` name restamps the kind, which is the supported way across.
