-- entity_membership gains a kind (epic f6c67e74, qits-412). A STRUCTURAL edge is the tree (epic >
-- feature > task, and nothing else); a CAMPAIGN edge is a campaign gathering work that already hangs
-- somewhere. An entity has at most one STRUCTURAL parent and any number of CAMPAIGN ones, exactly as
-- V9's own comment on uq_entity_membership_one_parent_per_child foretold.
--
-- WHY THE ONE-PARENT RULE BECOMES AN INDEX. It moves from a table constraint to a partial unique
-- INDEX of the same name, because postgres cannot make a constraint partial: a UNIQUE constraint
-- takes no WHERE clause, and only an index does. The name is kept because it says what it asserts,
-- and it still asserts it — for the tree, which is all it ever meant.
--
-- WHY THE IDS DIFFER BY KIND. A STRUCTURAL edge's id is the child's (V10's rule: an edge's identity
-- is the end of it that can only be in one). A CAMPAIGN edge cannot take that id — the child already
-- has a structural edge carrying it, and may join several campaigns — so it takes a random one.
--
-- WHAT THIS DOES NOT DO. No row is rewritten beyond the column default: every edge that exists is a
-- tree edge, so every one becomes STRUCTURAL, and no campaign edge exists yet.
-- idx_entity_membership_parent_position (parent_id, position) is unchanged: a campaign parent only
-- ever has CAMPAIGN edges and a structural parent only STRUCTURAL ones, so positions stay dense per
-- parent.
alter table entity_membership add column kind varchar(16) not null default 'STRUCTURAL';
alter table entity_membership add constraint ck_entity_membership_kind
    check (kind in ('STRUCTURAL', 'CAMPAIGN'));

alter table entity_membership drop constraint uq_entity_membership_one_parent_per_child;
create unique index uq_entity_membership_one_parent_per_child
    on entity_membership (child_id) where kind = 'STRUCTURAL';

-- An entity joins a given campaign once.
create unique index uq_entity_membership_campaign_child
    on entity_membership (parent_id, child_id) where kind = 'CAMPAIGN';

comment on column entity_membership.kind is
    'STRUCTURAL: the tree, one per child, id = child id. CAMPAIGN: a campaign membership, random id,'
    ' any number per child; carries the run record and owns campaign_criterion_group rows.';
