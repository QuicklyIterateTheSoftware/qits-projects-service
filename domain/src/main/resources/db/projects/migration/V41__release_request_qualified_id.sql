-- THE RELEASE REQUEST'S LOGICAL ID (qits-1158): `<repository>-rr-<n>`, e.g. `qits-ci-service-rr-12`.
-- The uuid stays the primary key and the internal key every consumer already holds; this is a
-- second name, short enough to say and to write in a branch, a commit subject or a ticket.
--
-- STORED AND FROZEN AT CREATION. The repository name in it is the one the request was made under
-- (release_request.repo_name). A rename does not rewrite old ids; the counter is keyed by repo_id,
-- so new requests go on counting under the new name.
--
-- UNIQUE PER REPOSITORY, not across the estate. Two projects may each have a repository with the
-- same name, so a global constraint would refuse a create for nothing. Within a repository the
-- number is unique, and the qualified id with it.
--
-- THE BACKING BRANCH IS STORED NOW. It was derived as `release/` + id. New requests fold onto
-- `release/<qualified id>`; every existing row keeps the branch it already has, `release/<uuid>`,
-- so an open request is not moved off its fold.
--
-- The counter copies V11 of the epics lineage (entity_number_sequence): a row per repository,
-- bumped in a transaction of its own by control/ReleaseRequestNumbers, seeded max + 1.

alter table release_request add column number bigint;
alter table release_request add column qualified_id varchar(255);
alter table release_request add column backing_branch varchar(255);

-- Numbered per repository in creation order; (created_at, id) is a total order.
update release_request r
   set number = ordered.n
  from (select id, row_number() over (partition by repo_id order by created_at, id) as n
          from release_request) ordered
 where ordered.id = r.id;

-- The name at creation where the row recorded one; else the repository's current name; else the
-- uuid itself, because an id made of a guessed name would be worse than the opaque one.
update release_request r
   set qualified_id = coalesce(
         coalesce(nullif(r.repo_name, ''),
                  (select min(n.name) from repository_name n where n.repository_id = r.repo_id))
           || '-rr-' || r.number,
         r.id),
       backing_branch = 'release/' || r.id;

alter table release_request alter column number set not null;
alter table release_request alter column qualified_id set not null;
alter table release_request alter column backing_branch set not null;

alter table release_request
    add constraint uq_release_request_repo_number unique (repo_id, number);
alter table release_request
    add constraint uq_release_request_repo_qualified_id unique (repo_id, qualified_id);
alter table release_request
    add constraint uq_release_request_repo_backing_branch unique (repo_id, backing_branch);

-- Doors accept the qualified id in any case.
create index ix_release_request_qualified_id_lower on release_request (lower(qualified_id));

create table release_request_number_sequence
(
    repo_id     varchar(255) not null,
    next_number bigint       not null,
    primary key (repo_id)
);

comment on table release_request_number_sequence is 'One counter row per repository: the next release request number. Bumped in its own transaction so a rolled-back create leaves a gap rather than reusing the number.';

insert into release_request_number_sequence (repo_id, next_number)
select repo_id, max(number) + 1
  from release_request
 group by repo_id;
