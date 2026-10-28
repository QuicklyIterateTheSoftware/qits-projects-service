package eu.wohlben.qits.projects.control;

/**
 * <b>Hears a branch's delivery step inside the transaction that took it</b> (qits-1719) — the
 * in-process seam beside {@link BranchDeliveryAnnouncer}, which only hears it after the commit.
 *
 * <p>For a consumer whose own write must commit or roll back with the step: an outbox row owed
 * because a branch's fold FAILED or its branch MOVED (qits-1722, branch-keyed notices) is exactly
 * as durable as the ledger row that says so, and never written for a step that rolled back. Every
 * {@code @ApplicationScoped} implementation is called, in no promised order, by {@link
 * BranchDeliveries} once per step it writes — never for {@code PUSHED}, and never for a step the
 * ledger already held (the same step, request and fold).
 *
 * <p><b>It must not throw, and it must not do anything slow.</b> It runs inside other flows'
 * transactions, under their row locks: a fold, a verdict, a release. A throw is caught and logged,
 * but a failed SQL statement marks the caller's transaction for rollback all the same, so what
 * happens here is a write to this datasource and nothing else — HTTP belongs after the commit.
 */
public interface BranchDeliveryStepListener {

  void onStep(BranchDeliveryStep step);
}
