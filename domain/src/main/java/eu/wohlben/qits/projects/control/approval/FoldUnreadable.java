package eu.wohlben.qits.projects.control.approval;

/**
 * A fold read an {@link ApprovalRequirementSource} needed and could not make. {@code ApprovalPolicy}
 * catches it and <b>fails closed</b>: the answer becomes one requirement named {@link
 * #requirementId} that only a person may satisfy, carrying this sentence, and the reading is marked
 * incomplete so nothing stores it as a fact about the fold.
 *
 * <p>Thrown by {@link ApprovalSubject}'s lazy reads rather than by a source, so every source that
 * reads the same thing fails the same way and the subject remembers the failure — a second source
 * asking is answered from that memory, never by asking the same mirror again.
 */
public class FoldUnreadable extends RuntimeException {

  private final String requirementId;
  private final String reason;
  private final String detail;
  private final boolean stopsReading;

  /**
   * @param requirementId the requirement the failure becomes
   * @param reason its sentence
   * @param detail what failed, or null
   * @param stopsReading whether every later source is skipped — the fold's changes could not be
   *     read, and nothing after that would be asked of a different mirror
   */
  public FoldUnreadable(
      String requirementId, String reason, String detail, boolean stopsReading, Throwable cause) {
    super(reason + (detail == null ? "" : ": " + detail), cause);
    this.requirementId = requirementId;
    this.reason = reason;
    this.detail = detail;
    this.stopsReading = stopsReading;
  }

  public String requirementId() {
    return requirementId;
  }

  /** The fail-closed requirement this failure stands for: a person, with the sentence. */
  public ApprovalRequirement requirement() {
    return ApprovalRequirement.person(requirementId, reason, detail);
  }

  public boolean stopsReading() {
    return stopsReading;
  }
}
