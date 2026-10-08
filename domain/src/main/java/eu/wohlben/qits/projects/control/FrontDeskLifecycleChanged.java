package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;

/**
 * Told when a project's stored {@link FrontDeskLifecycle} moves — the wrapper's {@code
 * .config/qits/project.yml} declared a different {@code front_desk.lifecycle} than the row held.
 *
 * <p>Called by {@link WrapperReconcileService#reconcileProjectConfig(String)}, after the transaction
 * that stored the new value and <b>only when it actually moved</b>. The front-desk feature
 * implements it by recomputing the desk's desired state (and creating the desk row for {@link
 * FrontDeskLifecycle#ALWAYS_ON}); until that lands, {@link NoopFrontDeskLifecycleChanged} is the
 * {@code @DefaultBean} and the change is merely stored and announced.
 *
 * <p><b>Nothing here may fail the reconcile</b>: the caller catches and logs what an implementation
 * throws, and the next move of the wrapper's {@code main} (or the next boot) does not re-call it for
 * a value that is already stored — so an implementation that needs to converge should also do so on
 * its own boot pass.
 */
public interface FrontDeskLifecycleChanged {

  /**
   * @param projectId the project whose front desk lifecycle moved
   * @param lifecycle the value as it now stands, never null
   */
  void onChanged(String projectId, FrontDeskLifecycle lifecycle);
}
