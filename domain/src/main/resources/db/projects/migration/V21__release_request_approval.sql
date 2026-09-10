-- A PERSON'S DECISION ABOUT ONE FOLD OF A RELEASE REQUEST.
--
-- A wrapper release (archetype PROJECT) is gated by a person in addition to the build gate: the
-- wrapper is the estate's own version, and letting a green build alone ship one would make the
-- single most consequential release the least deliberate. This table is what the approval gate
-- reads. Nothing reads it yet -- it lands ahead of the gate, on purpose, so the record exists before
-- anything depends on it.
--
-- A DECISION IS ABOUT A SHA. merged_sha is the fold the person looked at, and it is what makes the
-- re-arm carry the whole of the invalidation: anything that changes what the fold would produce
-- lands a new merged sha on the request, and a decision made against the old one stops matching --
-- with no column to clear and no path that has to remember to clear it. The gate asks for the newest
-- row at the request's CURRENT merged_sha; older rows are history about a fold it has moved past.
--
-- INSERT-ONLY. Nothing updates a row here and nothing deletes one. A decline, the fix that answers
-- it and the approval that follows are three facts, and keeping them as three is not only an audit
-- trail: it is the corpus the future goal-verification step gets measured against -- what a person
-- refused, what changed, what they accepted. An in-place rewrite would delete exactly that evidence.
-- So a change of mind is another row, and the index below is what makes "the newest one" cheap.
--
-- decision HAS NO CHECK CONSTRAINT, the usual reasoning: the vocabulary (APPROVED, DECLINED today)
-- grows without a migration and every historical row keeps its word.
--
-- NO FOREIGN KEY TO release_request, unlike release_request_source, and for the opposite reason to
-- that table's cascade. A source has no meaning without its request; a decision does -- it is the
-- record that a named person accepted a named sha, and it must survive the request row the same way
-- released_tag_pending_merge survives the repository that named it. A cascade here would let a bulk
-- delete of requests quietly empty the corpus.
create table release_request_approval (
  id           varchar(255) not null,
  request_id   varchar(255) not null,
  -- The request's merged_sha at the moment of the decision. Not null: there is nothing to decide
  -- about before the first merge lands, and a decision with no sha would be a standing approval of
  -- whatever the request becomes next.
  merged_sha   varchar(255) not null,
  decision     varchar(32)  not null,
  -- Who decided -- the forwarded identity. The point of the whole gate, hence not null.
  actor        varchar(255) not null,
  -- What they said; null where they said nothing, which an approval often does.
  note         text,
  decided_at   timestamp with time zone not null,
  causation_id uuid,
  constraint PK_release_request_approval primary key (id)
);

-- The gate's only query shape: the newest decision for one request at one sha. decided_at descends
-- because "newest" is the whole of the read -- the index answers it without a sort.
create index IX_release_request_approval_current
  on release_request_approval (request_id, merged_sha, decided_at desc);
