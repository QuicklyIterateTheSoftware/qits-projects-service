package eu.wohlben.qits.projects.control.gate;

/** Whether a gate holds a request. */
public enum GateApplicability {
  /** The gate holds this request; it is evaluated. */
  APPLIES,
  /** The gate does not hold this request; it is left out of the answer. */
  DOES_NOT_APPLY,
  /**
   * The repository's gate configuration could not be read, so nobody can say. Reported as {@code
   * UNKNOWN} with the reason, never left out: an unreadable configuration is not "no gate".
   */
  UNREADABLE
}
