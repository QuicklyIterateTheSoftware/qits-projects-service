package eu.wohlben.qits.projects.control;

/**
 * Tells the rest of the platform that a <b>branch</b> took a delivery step (qits-1719): joined a
 * request, moved to another, failed, conflicted, was withdrawn, released, went live.
 *
 * <p>A port, for the reason {@link ReleaseRequestStateAnnouncer} is one: {@code domain} does not
 * know the event bus exists. The one implementation is {@code
 * service/…/bus/BranchDeliveryChangedAnnouncer}. It is called by {@link BranchDeliveries} alone,
 * after the transaction that wrote the step committed, once per step that changed the ledger.
 *
 * <p><b>What it is for.</b> An agent works on a branch, not on a request: the request carrying its
 * branch changes when a release hands it on or a successor supersedes it, and a branch in a
 * wrapper estate rides several repositories' requests at once. {@code ReleaseRequestStateChanged}
 * answers "how does this request stand"; this answers "where is my branch", keyed by the branch, so
 * {@code qits wait} can match on it without knowing which request it is in.
 *
 * <p><b>Absent is a supported configuration</b>, like every port here. <b>Nothing here may
 * throw</b>.
 */
public interface BranchDeliveryAnnouncer {

  void onStep(BranchDeliveryStep step);
}
