-- Dropping what V41 only unmapped (qits-1899, qits-1908). V41 said the drop was the next migration,
-- one release after the one that stopped reading these tables and this column, because a rolling
-- deploy drains the old instance against the new schema (AGENTS.md, "Expand/contract"). That
-- release is live now, so nothing on the estate still maps any of the following.
--
--   * campaign_membership_milestone, work_milestone, milestone_criterion, milestone_group,
--     milestone, campaign_start: V38's five milestone tables and V19's campaign_start, each named
--     before whatever references it (campaign_membership_milestone and work_milestone reference
--     milestone; milestone_criterion references milestone_group; milestone_group references
--     milestone; campaign_start is independent). Plain `drop table`, no CASCADE: nothing kept has a
--     foreign key into any of these six.
--   * entity_membership's run record: V19's nine columns (claimed_at, joined_running, dispatched_at,
--     dispatch_workspace_id, dispatch_branch, dispatch_agent_launch, dispatch_refusal,
--     dispatch_refused_at, dispatch_error) and the check that kept them empty off a STRUCTURAL edge
--     (ck_entity_membership_run_record_campaign_only) — moot once CAMPAIGN cannot be inserted.
--   * entity.depends_on_entity_id. V9 gave it only fk_entity_depends_on, no explicit index, so
--     dropping the column takes that foreign key with it: postgres drops a constraint of the table
--     being altered along with the column it is on, with no CASCADE needed (CASCADE is for a
--     dependency outside this table — a view, another table's foreign key — which this
--     self-reference is not).
drop table campaign_membership_milestone;
drop table work_milestone;
drop table milestone_criterion;
drop table milestone_group;
drop table milestone;
drop table campaign_start;

alter table entity_membership
    drop constraint ck_entity_membership_run_record_campaign_only,
    drop column claimed_at,
    drop column joined_running,
    drop column dispatched_at,
    drop column dispatch_workspace_id,
    drop column dispatch_branch,
    drop column dispatch_agent_launch,
    drop column dispatch_refusal,
    drop column dispatch_refused_at,
    drop column dispatch_error;

alter table entity drop column depends_on_entity_id;
