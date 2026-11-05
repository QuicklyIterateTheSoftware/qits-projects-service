package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.WorkEntity;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A {@link QualityCheck} a test can arm (qits-150), and that passes every ticket otherwise — so the
 * rest of the suite runs as the shipped, empty step does. It records every ticket it was asked
 * about, armed or not, which is how a test sees that {@link QualityChecksGate} ran.
 */
@ApplicationScoped
public class ArmableQualityCheck implements QualityCheck {

  static volatile String armedWith;

  private static final List<String> JUDGED = new ArrayList<>();

  /** Refuse with {@code why} from now on; null passes. */
  static void arm(String why) {
    armedWith = why;
  }

  /** Pass again, and forget what was asked. */
  static synchronized void reset() {
    armedWith = null;
    JUDGED.clear();
  }

  static synchronized List<String> judged() {
    return List.copyOf(JUDGED);
  }

  @Override
  public String name() {
    return "ARMED_TEST_CHECK";
  }

  @Override
  public Optional<String> refusal(WorkEntity ticket, Mover mover) {
    synchronized (ArmableQualityCheck.class) {
      JUDGED.add(ticket.id + " at " + ticket.status);
    }
    return Optional.ofNullable(armedWith);
  }
}
