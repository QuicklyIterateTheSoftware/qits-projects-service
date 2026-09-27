package eu.wohlben.qits.projects.api;

/**
 * The one bit that tells the two dispatch actions apart (qits-394), on the wire as the {@code mode}
 * of {@code POST /entities/{id}/dispatch} and on the row as {@code WorkEntity.dispatchContinues}.
 *
 * <p>Both start the phase the entity's status implies, in the same workspace, with the same words.
 * They differ only in what {@link PhaseAdvance} does when the agent claims the next transition — and
 * neither changes what a move into VERIFIED does: it asks for the release either way.
 */
public enum DispatchMode {

  /** <em>Dispatch</em>: run the whole flow — each transition delivers the next phase's prompt. */
  FLOW,

  /** <em>Run the next phase</em>: run one phase and stop; the next starts on the next press. */
  PHASE;

  /** The value the row stores. */
  boolean continues() {
    return this == FLOW;
  }

  /** The mode a row's stored bit reads as. */
  static DispatchMode of(boolean continues) {
    return continues ? FLOW : PHASE;
  }
}
