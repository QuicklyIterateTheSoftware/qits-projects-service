-- A RELEASE REQUEST STAYS OPEN UNTIL THE RELEASE IS FINALIZED (ticket b27384a3).
--
-- Until now the tag being cut finished a request. A publish run that failed after it had nothing
-- holding the request open, and a QA run still queued kept building a branch the release had just
-- deleted. RELEASED is an OPEN state from here on, and the path past it is:
--
--   READY -> RELEASED        the tag is cut; the request is still open
--         -> publish gate    the tag's own release run, where the released tree declares one
--         -> deployment gate DeploymentActive for the released version, where it declares one
--         -> FINALIZED       the tag is merged into main; only now is the request done
--
-- The two new states — FINALIZED and OBSOLETE — need no DDL: `release_request.state` is a string
-- with no check constraint, the platform's usual reasoning and the same one CONFLICTED relied on.
-- What does need columns is the two facts that used to have nowhere to live.

-- THE PUBLISH GATE, on the tag's own row. Null publish_state is the commonest answer and means the
-- released tree declares no release pipeline, so there is no gate here at all — deliberately not
-- the same as PENDING, which is a declared pipeline whose verdict has not come. The run id sits
-- beside the sentence rather than inside it because a red gate is retried by run (`qits ci retry`),
-- and a person acting on one needs the id rather than a paragraph containing it.
alter table released_tag_pending_merge add column publish_state varchar(32);
alter table released_tag_pending_merge add column publish_detail varchar(4000);
alter table released_tag_pending_merge add column publish_run_id varchar(255);

-- THE DEPLOYMENT GATE'S OWN FACT, which merge_requested_at used to be. It cannot be any more: there
-- are two post-release gates now and they pass in either order, so "the deployment happened" has to
-- survive a publish run that has not finished yet. merge_requested_at keeps its meaning for the
-- sweep — every configured gate passed, the merge is owed — and gains a second way of being reached.
alter table released_tag_pending_merge add column deployment_active_at timestamp with time zone;

-- OBSOLESCENCE. A later release of the same repository overtakes one that never finalized: the
-- successor folds the earlier tag in and supersedes it whole, so nothing will ever finish the
-- earlier request and the sweep must stop trying to merge its tag. Two columns, one per row that
-- knows something about it — the request names its successor, the tag says it was abandoned.
alter table release_request add column superseded_by varchar(255);
alter table released_tag_pending_merge add column abandoned_at timestamp with time zone;

-- No foreign key on superseded_by, although it names a row in this very table: it is the record of
-- what superseded what, and it has to outlive a delete of either side rather than cascade with one.

-- The sweep's two selections both exclude an abandoned row now, so the partial index V13 created
-- for the first of them is replaced with one that matches the predicate.
drop index IX_released_tag_pending_merge_owed;
create index IX_released_tag_pending_merge_owed
  on released_tag_pending_merge (merge_requested_at)
  where merged_at is null and abandoned_at is null;

-- THE EXISTING ROWS, and the mapping is the one the new vocabulary makes true of them:
--
--   a RELEASED request whose tag is already on main   ->  FINALIZED. It is done under the new
--       reading exactly as it was under the old one, and leaving it RELEASED would put every
--       release this platform has ever made back onto the open worklist.
--   a RELEASED request whose tag is still owed        ->  stays RELEASED, and is now OPEN. That is
--       the honest answer: the merge has not landed, so the request genuinely is not finished.
--       ReleaseFinalization's catch-up re-asks the gates of every such row and finalizes it.
--   a RELEASED request with no tag row at all         ->  stays RELEASED. Releases predating
--       released_tag_pending_merge have no row to consult and nothing may invent one; they are a
--       handful, they are old, and `state=FINALIZED` is not a thing to guess at.
--
-- Nothing is written to the publish or deployment columns for existing rows. A row already carrying
-- merge_requested_at is owed its merge on that column alone, so the sweep lands it unchanged; and
-- deployment_active_at means "a DeploymentActive arrived", which is not what a row gated by the
-- non-deployable shortcut can honestly claim.
update release_request
set state = 'FINALIZED'
where state = 'RELEASED'
  and exists (
    select 1
    from released_tag_pending_merge tag
    where tag.release_request_id = release_request.id
      and tag.merged_at is not null
  );
