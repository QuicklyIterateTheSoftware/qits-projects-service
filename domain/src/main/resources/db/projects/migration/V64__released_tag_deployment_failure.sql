-- A ROLLED-BACK DEPLOYMENT IS RECORDED ON THE RELEASED TAG (qits-1690).
--
-- A release whose deployment docker swarm rolled back stayed RELEASED for ever: only
-- DeploymentActive was consumed, the rollback gate asked qits-deployments on a single read alone,
-- and the qits-1471 hold read ROLLED_BACK as "in flight". The failure is now a fact on the row,
-- the deployment gate's own counterpart to deployment_active_at (V23):
--
--   deployment_failed_at      when a DeploymentFailed for this version arrived, or the reconcile
--                             found its newest deployment ROLLED_BACK or FAILED; null otherwise,
--                             and cleared by a later DeploymentActive (a successful redeploy)
--   deployment_failed_detail  "<status>: <first line of the deployer's detail>", capped at 1000
--
-- Both nullable, no backfill: the reconcile asks qits-deployments about every released row still
-- waiting on its deployment, which is what heals the rows stranded before this column existed.
alter table released_tag_pending_merge add column deployment_failed_at timestamp with time zone;
alter table released_tag_pending_merge add column deployment_failed_detail varchar(1000);
