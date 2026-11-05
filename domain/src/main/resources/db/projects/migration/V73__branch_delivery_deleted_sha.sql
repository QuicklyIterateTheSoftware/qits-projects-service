-- WHERE A DELETED BRANCH STOOD WHEN IT WAS DELETED (qits-1869).
--
-- A WITHDRAWN row's fold_sha is the request's merged sha — a merge of main with EVERY source of the
-- request — and that commit becomes unreachable once the request refolds or its release/* backing
-- branch is deleted, so "main contains the fold" could never be answered YES for a wrapper branch
-- whose request carried anything else. The proof that nothing of a deleted branch is left to add is
-- the branch's own tip, which qits-githost's SCMDeleteBranch carries as `sha`:
--
--   deleted_sha  The tip the branch stood at when it was deleted, written by the deletion on the
--                row(s) of (repo, branch) at whatever step they hold. Cleared by every later step
--                and by a re-push, so a recreated branch never inherits an old tip. Null where the
--                deletion is not known, and on every row written before this.
--
-- The backfill below copies the tips of the qits-qits wrapper's ticket branches deleted before this,
-- off their SCMDeleteBranch events (the qits-events log keeps about eight days). It closes nothing
-- by itself: the reconcile still asks the git host whether main contains the tip, so a wrong sha
-- closes no ticket. On a fresh database the updates touch nothing.
alter table branch_delivery add column deleted_sha text;
update branch_delivery set deleted_sha = '435ca77d29d4b6384c4db3a598c08d66d5c0c993' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1767' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '435ca77d29d4b6384c4db3a598c08d66d5c0c993' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1768' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '7341a50491af1ea1a47986a1b706e50d18d8783a' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1772' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '435ca77d29d4b6384c4db3a598c08d66d5c0c993' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1773' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '85877100319e65c21eb35cf2fe74bd58345e6d6c' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1779' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = 'be2c002f70a366beabd9857ff76f8c7315b2fe46' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1782' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '85877100319e65c21eb35cf2fe74bd58345e6d6c' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1784' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '19da60da222722e08541f5b998702c076a16aa73' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1791' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '264892c3dfff79e99dccf72f193def73a7d36e23' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1802' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '85877100319e65c21eb35cf2fe74bd58345e6d6c' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1804' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '476c431dfeffa0fffeffd8e5802271418f8431c2' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1805' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '0917d038c1d9293a94ac29d3871221e48aea17eb' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1818' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '0917d038c1d9293a94ac29d3871221e48aea17eb' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1825' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '9df66df278ee46dfe8526649a61e308f0c916352' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1829' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '2e03753a5b3af789b8a6d874534885b3afef715b' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1835' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '62f12c48850175adb658a88edd6107aade195738' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1836' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = 'bda1771ed3bfb8681caa62a62c1f5f0b8ce12c98' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1838' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = 'c930b3781a0476c6af9915dd31a29e7524546809' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1840' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '0d7d2617163b4131471ae5af45bfdd1f7b8ae037' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1843' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '9cd5690f05b1e8acbaee41ff51f19ea07923919d' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1845' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '6d0abf59c2463bd5eb0ae122537c5009c4c678b6' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1847' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '0d7d2617163b4131471ae5af45bfdd1f7b8ae037' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1849' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '91ebf6bb4753339bb2e2ff67c96dee39d6d06de5' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1860' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '0863863e0f5cbfb24d1e8904683a6f7fe9e52efc' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1863' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '91ebf6bb4753339bb2e2ff67c96dee39d6d06de5' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1864' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '91ebf6bb4753339bb2e2ff67c96dee39d6d06de5' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1866' and step = 'WITHDRAWN' and deleted_sha is null;
update branch_delivery set deleted_sha = '91ebf6bb4753339bb2e2ff67c96dee39d6d06de5' where repo_id = '5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397' and branch = 'ticket/qits-1867' and step = 'WITHDRAWN' and deleted_sha is null;
