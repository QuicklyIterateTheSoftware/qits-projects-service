package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.WorkEntity;
import java.util.Optional;

/**
 * <b>One check of a ticket's IMPLEMENTED claim, made before its verification starts</b> (qits-150)
 * — what {@link QualityChecksGate} runs on every move of a ticket out of IMPLEMENTED into VERIFYING
 * or VERIFIED. Adding one is one {@code @ApplicationScoped} class: the gate collects every
 * implementation through {@code Instance<QualityCheck>}, so a new check touches neither the gate,
 * the state machine nor any other check.
 *
 * <p><b>There is none yet</b>, so the step is empty and every ticket passes it. It exists so the
 * place a quality check runs is fixed before the first one is written: between the implementer's
 * claim and the verifier's turn, on every path between the two — the agent's own move, the skip
 * straight to VERIFIED and the platform's automatic IMPLEMENTED → VERIFYING at the verify press.
 *
 * <p><b>What a failing check will do is not built yet.</b> Today a refusal is the gate's 409, as
 * any quality gate's is, and the ticket stays IMPLEMENTED. The intent is that a failing check sends
 * the ticket back to its implementer — the reason on the thread, the implement phase handed to the
 * ticket's agent again — rather than leaving it to whoever pressed verify; that hand-back is a later
 * ticket's.
 */
public interface QualityCheck {

  /** The check's name as a refusal spells it — {@code COVERAGE}, say. */
  String name();

  /**
   * Why {@code ticket} — as it stands before the move, still IMPLEMENTED (or earlier, on the
   * PUT-shaped door) — fails this check, as a clause with no subject, or empty when it passes.
   * {@code mover} is who is moving it. It may read beyond the row: a check is a bean and injects
   * what it needs.
   */
  Optional<String> refusal(WorkEntity ticket, Mover mover);
}
