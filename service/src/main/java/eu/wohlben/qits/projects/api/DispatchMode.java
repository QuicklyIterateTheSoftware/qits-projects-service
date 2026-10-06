package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.error.DomainException;
import java.util.Locale;

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

  /**
   * The mode a press names, or a 400 naming both words — never a guessed default: the two actions
   * are both reasonable, so a caller that named neither has not said what it wants. Both dispatch
   * doors ({@code /entities/{id}/dispatch} and {@code /work/{qualifiedId}/dispatch}) read it here.
   */
  public static DispatchMode parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new DomainException(
          400, "mode is required: FLOW (run the whole flow) or PHASE (run the next phase).");
    }
    try {
      return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new DomainException(
          400, "Unknown mode " + raw + ": FLOW (run the whole flow) or PHASE (run the next phase).");
    }
  }

  /** The mode a row's stored bit reads as. */
  static DispatchMode of(boolean continues) {
    return continues ? FLOW : PHASE;
  }
}
