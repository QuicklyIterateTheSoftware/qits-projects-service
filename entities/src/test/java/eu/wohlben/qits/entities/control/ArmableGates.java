package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Two {@link TransitionGate} beans a test can arm (qits-887), and that apply to nothing otherwise —
 * so the rest of the suite runs as if they were not there. Armed, a gate applies to every move of a
 * TICKET, answers the refusal it was given (or none), and records each time it was asked to judge.
 */
final class ArmableGates {

  private ArmableGates() {}

  /** Disarms both and forgets what they were asked. */
  static void disarm() {
    First.state.reset();
    Second.state.reset();
  }

  static final class State {
    volatile boolean armed;
    volatile String refusal;
    final List<String> judged = new ArrayList<>();

    void arm(String refusal) {
      this.armed = true;
      this.refusal = refusal;
    }

    synchronized void reset() {
      armed = false;
      refusal = null;
      judged.clear();
    }

    synchronized List<String> judged() {
      return List.copyOf(judged);
    }

    boolean appliesTo(Archetype archetype) {
      return armed && archetype == Archetype.TICKET;
    }

    synchronized Optional<String> judge(WorkEntity row, Mover mover) {
      judged.add(row.id + " by " + mover.described());
      return Optional.ofNullable(refusal);
    }
  }

  /** Named so it sorts first. */
  @ApplicationScoped
  static class First implements TransitionGate {
    static final State state = new State();

    @Override
    public String name() {
      return "A_FIRST_TEST_GATE";
    }

    @Override
    public boolean appliesTo(Archetype archetype, EntityStatus from, EntityStatus to) {
      return state.appliesTo(archetype);
    }

    @Override
    public Optional<String> refusal(WorkEntity row, Mover mover) {
      return state.judge(row, mover);
    }
  }

  @ApplicationScoped
  static class Second implements TransitionGate {
    static final State state = new State();

    @Override
    public String name() {
      return "B_SECOND_TEST_GATE";
    }

    @Override
    public boolean appliesTo(Archetype archetype, EntityStatus from, EntityStatus to) {
      return state.appliesTo(archetype);
    }

    @Override
    public Optional<String> refusal(WorkEntity row, Mover mover) {
      return state.judge(row, mover);
    }
  }
}
