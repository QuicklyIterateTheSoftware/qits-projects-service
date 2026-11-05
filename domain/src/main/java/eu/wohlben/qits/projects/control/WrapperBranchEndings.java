package eu.wohlben.qits.projects.control;

/**
 * <b>A branch's delivery ended in the project's wrapper repository</b> (qits-150): told by {@link
 * WrapperBranchEndingListener} after the transaction that took the ledger step has committed, so the
 * work item the branch names can be closed. The shipped implementation ({@code
 * entitieshost/TicketWrapperDone}) moves a VERIFIED ticket whose wrapper branch it is to DONE —
 * the one way a ticket reaches DONE.
 *
 * <p>An optional port like every one here: with no implementation an ending closes nothing.
 *
 * <p><b>It must not throw, and it is called with no transaction on the thread</b> — the work item
 * lives in the entities module's own database, which is never enlisted beside this one.
 */
public interface WrapperBranchEndings {

  /** How the branch's delivery ended. */
  enum Ending {
    /** The request that carried it to the wrapper's {@code main} is FINALIZED: the ledger's LIVE. */
    FINALIZED,
    /**
     * The request carrying it was withdrawn because its fold adds nothing to main and the estate
     * pins are fresh ({@link ReleaseRequests#EMPTY_WRAPPER_WITHDRAWN}): there is nothing left to
     * release. Any other withdrawal is not an ending this port hears.
     */
    EMPTY
  }

  /**
   * One ending.
   *
   * @param projectId the wrapper's project
   * @param repoId the wrapper repository's id
   * @param branch the branch, as the ledger keys it ({@code ticket/qits-150})
   * @param requestId the request the ledger row names at the ending — after any moves and
   *     obsolescence, the one that actually ended
   * @param qualifiedId that request's qualified id, for the thread
   * @param version the version it released as; null for {@link Ending#EMPTY}
   * @param ending how it ended
   */
  record Ended(
      String projectId,
      String repoId,
      String branch,
      String requestId,
      String qualifiedId,
      String version,
      Ending ending) {}

  void ended(Ended ended);
}
