package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkOutcome;

/**
 * What a test that drops something states about why (qits-248): every move into DROPPED carries an
 * outcome, and a suite about something else drops as a decision against — WONT_DO, naming nothing.
 */
public final class TestDrops {

  /** A drop decided against, naming no other item. */
  public static final DropOutcome WONT_DO = DropOutcome.of(WorkOutcome.WONT_DO, null);

  private TestDrops() {}

  /** {@link #WONT_DO} for a move into DROPPED, nothing for any other target. */
  public static DropOutcome of(String target) {
    return EntityStatus.DROPPED.name().equals(target) ? WONT_DO : DropOutcome.NONE;
  }
}
