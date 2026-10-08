package eu.wohlben.qits.projects.control;

/**
 * Told when a project is about to be deleted, so its front desk goes with it (qits-767): the runner
 * holding the desk is sent {@code remove}, the desk's token is revoked at qits-idp and the {@code
 * front_desk} row is dropped (the foreign key would cascade it anyway, but nothing would then be
 * left to say which runner and which token to clean up).
 *
 * <p>Called by {@link ProjectService#delete(String)} <b>before</b> its delete transaction, because
 * afterwards the row naming the runner and the token is gone. <b>Nothing here may fail the
 * delete</b>: the caller catches and logs what an implementation throws. Until the service wires the
 * front desk, {@link NoopProjectFrontDeskRemoval} is the {@code @DefaultBean}.
 */
public interface ProjectFrontDeskRemoval {

  /** @param projectId the project being deleted */
  void projectDeleting(String projectId);
}
