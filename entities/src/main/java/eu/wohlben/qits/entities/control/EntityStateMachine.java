package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.EntityStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * <b>The status model of every archetype that has a lifecycle, as one explicit state machine.</b>
 * The states are {@link EntityStatus}; the transitions are {@link #TRANSITIONS}, declared once, below,
 * each with its {@link TransitionKind}. Everything else that has to know a move — {@link EntityLifecycle}'s
 * guards, the per-archetype transition routes and MCP tools behind them, the multi-entity
 * transition's DONE rule, the served registry document's {@code transitions} and {@code lifecycle},
 * and the phase a status starts ({@code PhasePrompts}) — asks this class and restates nothing.
 *
 * <pre>
 *             FORWARD          FORWARD                FORWARD              FORWARD          FORWARD
 *   REPORTED ───────▶ REFINED ───────▶ IMPLEMENTING ───────▶ IMPLEMENTED ───────▶ VERIFIED ───────▶ DONE
 *      ▲  │  ◀─────── │  │  ◀─────────── │  ◀──────────────── │  ◀──────────────── │            (final)
 *      │  │    BACK   │  │     BACK      │       BACK         │        BACK        │
 *      │  │           │  └───────────────── SKIP ───────────▶ │                    │
 *      │  │ DROP      │ DROP             │ DROP               │ DROP               │ DROP
 *      │  ▼           ▼                  ▼                    ▼                    ▼
 *      └─ DROPPED ◀──────────────────────────────────────────────────────────────────
 *  REOPEN
 *
 *   starts phase:  REPORTED → refine,  REFINED and IMPLEMENTING → implement,  IMPLEMENTED → verify;
 *                  VERIFIED, DONE and DROPPED start nothing.
 * </pre>
 *
 * <p><b>The walk</b> — REPORTED → REFINED → IMPLEMENTING → IMPLEMENTED → VERIFIED → DONE — is taken
 * one step at a time: forward as each phase finishes, back when a claim turns out wrong. Asking for
 * the status an entity already has is refused rather than read as a no-op. A BACK move is a
 * correction, not how a phase reports failure: a phase that cannot finish, a failed verification
 * included, blocks the entity where it stands (qits-592).
 *
 * <p><b>IMPLEMENTING is the one step that may be skipped</b> (qits-749). The platform enters it at
 * the dispatch press, so it records a fact rather than a claim kept by hand — but an agent that
 * finished work nobody moved to IMPLEMENTING first must still be able to say so, so REFINED →
 * IMPLEMENTED stays legal as a {@link TransitionKind#SKIP}: two steps along the walk, and from
 * REFINED only. FORWARD stays one step; the skip is its own kind rather than a loosened FORWARD, so
 * no other step can be jumped.
 *
 * <p><b>DONE is final: it has no exits</b> — not back to VERIFIED, not to DROPPED, not anywhere.
 * Acceptance could only ever throw a done item further back than VERIFIED, which is not a flow to
 * support; a done development that later turns out wrong is a <em>new</em> ticket or epic, which may
 * refer to the done one. Below DONE the walk stays reversible.
 *
 * <p><b>DROPPED is off the walk.</b> It is reachable (DROP) from every state that is still open —
 * REPORTED, REFINED, IMPLEMENTING, IMPLEMENTED, VERIFIED — because a decision not to do the work can
 * be taken at any point while the work is open, and from nowhere else. It reopens (REOPEN) to
 * REPORTED and to nothing else: somebody who has changed their mind about abandoned work is asking
 * what it is for again, which is the refine phase.
 *
 * <p><b>The declaration checks itself.</b> The class initialiser refuses a {@link #TRANSITIONS} entry
 * whose kind does not match its ends (a FORWARD that is not the next step on the walk, a DROP that
 * does not land on DROPPED, …), a state out of order, a terminal state with an exit, or an open
 * state without one — so the list cannot be edited into something this javadoc does not describe
 * without the module failing to load.
 */
public final class EntityStateMachine {

  /** What a transition does, relative to the walk. Served verbatim by the registry document. */
  public enum TransitionKind {
    /** The next step along the walk. */
    FORWARD,
    /**
     * Two steps along the walk, over {@link EntityStatus#IMPLEMENTING}: REFINED → IMPLEMENTED, for
     * work finished without being marked started. Legal from REFINED only.
     */
    SKIP,
    /** A step back along the walk. */
    BACK,
    /** From an open state on the walk to {@link EntityStatus#DROPPED}. */
    DROP,
    /** From {@link EntityStatus#DROPPED} back into the walk. */
    REOPEN
  }

  /**
   * A phase a state starts: entering a status starts the phase that belongs to it, which is what
   * lets the status decide what a dispatch runs. {@link #word()} is what a comment, a log line and
   * the SPA read.
   */
  public enum Phase {
    REFINE("refine"),
    IMPLEMENT("implement"),
    VERIFY("verify");

    private final String word;

    Phase(String word) {
      this.word = word;
    }

    public String word() {
      return word;
    }
  }

  /** One legal move: from, to, and what kind of move it is. */
  public record Transition(EntityStatus from, EntityStatus to, TransitionKind kind) {}

  /** The walk, in order. DONE, its last state, is the one terminal state. */
  private static final List<EntityStatus> WALK =
      List.of(
          EntityStatus.REPORTED,
          EntityStatus.REFINED,
          EntityStatus.IMPLEMENTING,
          EntityStatus.IMPLEMENTED,
          EntityStatus.VERIFIED,
          EntityStatus.DONE);

  /** The one state off the walk. */
  private static final EntityStatus OFF_WALK = EntityStatus.DROPPED;

  /** The states with no exits. Declared, and checked against {@link #TRANSITIONS} at load. */
  private static final Set<EntityStatus> TERMINAL = EnumSet.of(EntityStatus.DONE);

  /** Every state, in lifecycle order: the walk, then the one state off it. */
  private static final List<EntityStatus> STATES = concat(WALK, OFF_WALK);

  /**
   * <b>Every legal move, and the one place they are written.</b> Grouped by source state in
   * lifecycle order, and within a state FORWARD first, then SKIP, then BACK, then DROP/REOPEN — the order the
   * registry serves. DONE has no row, because DONE is final.
   */
  private static final List<Transition> TRANSITIONS =
      List.of(
          t(EntityStatus.REPORTED, EntityStatus.REFINED, TransitionKind.FORWARD),
          t(EntityStatus.REPORTED, EntityStatus.DROPPED, TransitionKind.DROP),
          t(EntityStatus.REFINED, EntityStatus.IMPLEMENTING, TransitionKind.FORWARD),
          t(EntityStatus.REFINED, EntityStatus.IMPLEMENTED, TransitionKind.SKIP),
          t(EntityStatus.REFINED, EntityStatus.REPORTED, TransitionKind.BACK),
          t(EntityStatus.REFINED, EntityStatus.DROPPED, TransitionKind.DROP),
          t(EntityStatus.IMPLEMENTING, EntityStatus.IMPLEMENTED, TransitionKind.FORWARD),
          t(EntityStatus.IMPLEMENTING, EntityStatus.REFINED, TransitionKind.BACK),
          t(EntityStatus.IMPLEMENTING, EntityStatus.DROPPED, TransitionKind.DROP),
          t(EntityStatus.IMPLEMENTED, EntityStatus.VERIFIED, TransitionKind.FORWARD),
          t(EntityStatus.IMPLEMENTED, EntityStatus.IMPLEMENTING, TransitionKind.BACK),
          t(EntityStatus.IMPLEMENTED, EntityStatus.DROPPED, TransitionKind.DROP),
          t(EntityStatus.VERIFIED, EntityStatus.DONE, TransitionKind.FORWARD),
          t(EntityStatus.VERIFIED, EntityStatus.IMPLEMENTED, TransitionKind.BACK),
          t(EntityStatus.VERIFIED, EntityStatus.DROPPED, TransitionKind.DROP),
          t(EntityStatus.DROPPED, EntityStatus.REPORTED, TransitionKind.REOPEN));

  /** The phase each state starts; a state absent here starts none. */
  private static final Map<EntityStatus, Phase> PHASES =
      Map.of(
          EntityStatus.REPORTED, Phase.REFINE,
          EntityStatus.REFINED, Phase.IMPLEMENT,
          EntityStatus.IMPLEMENTING, Phase.IMPLEMENT,
          EntityStatus.IMPLEMENTED, Phase.VERIFY);

  /** {@link #TRANSITIONS} indexed by source, order kept; every state has an entry. */
  private static final Map<EntityStatus, List<Transition>> OUTGOING = index();

  static {
    verify();
  }

  private EntityStateMachine() {}

  // --- the operations ---------------------------------------------------------------------------

  /**
   * Every state, in lifecycle order: REPORTED, REFINED, IMPLEMENTING, IMPLEMENTED, VERIFIED, DONE,
   * DROPPED.
   */
  public static List<EntityStatus> states() {
    return STATES;
  }

  /**
   * The walk alone, in order: REPORTED → REFINED → IMPLEMENTING → IMPLEMENTED → VERIFIED → DONE.
   */
  public static List<EntityStatus> walk() {
    return WALK;
  }

  /** Every declared transition, in declaration order. */
  public static List<Transition> transitions() {
    return TRANSITIONS;
  }

  /** The legal moves out of {@code from}, FORWARD first, then SKIP, BACK, then DROP/REOPEN. */
  public static List<Transition> transitionsFrom(EntityStatus from) {
    return OUTGOING.getOrDefault(from, List.of());
  }

  /** The declared transition {@code from → to}, or empty when the move is not legal. */
  public static Optional<Transition> transition(EntityStatus from, EntityStatus to) {
    return transitionsFrom(from).stream().filter(move -> move.to() == to).findFirst();
  }

  /** Whether {@code from → to} is a legal move. */
  public static boolean allows(EntityStatus from, EntityStatus to) {
    return transition(from, to).isPresent();
  }

  /**
   * Why {@code from → to} is refused — empty when it is legal. The reason is a clause with no
   * subject ("cannot move from X to Y…"), so a caller prefixes the kind it is talking about.
   */
  public static Optional<String> refusal(EntityStatus from, EntityStatus to) {
    if (allows(from, to)) {
      return Optional.empty();
    }
    String refused = "cannot move from " + from + " to " + to;
    return Optional.of(isTerminal(from) ? refused + ": " + finality(from) : refused);
  }

  /** Whether {@code status} is final — a state with no exits. Today that is DONE alone. */
  public static boolean isTerminal(EntityStatus status) {
    return TERMINAL.contains(status);
  }

  /**
   * Whether the stored status word names a terminal state. A word naming no status is not terminal
   * — this answers, it never throws, so a guard can ask it of any row.
   */
  public static boolean isTerminal(String statusWord) {
    for (EntityStatus status : TERMINAL) {
      if (status.name().equals(statusWord)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The sentence that says a terminal state is final and what to do instead — shared by every door
   * that refuses a move out of one, so they all say the same thing.
   */
  public static String finality(EntityStatus terminal) {
    return terminal
        + " is final and has no exits — a follow-up is a new ticket or epic, which may refer to"
        + " the "
        + terminal.name().toLowerCase()
        + " one";
  }

  /** The phase entering {@code status} starts, or empty where it starts none. */
  public static Optional<Phase> phaseStartedBy(EntityStatus status) {
    return Optional.ofNullable(PHASES.get(status));
  }

  /**
   * Whether {@code status} is {@code milestone} or beyond it on the walk. False for a state off the
   * walk (DROPPED), which is neither behind nor beyond anything.
   */
  public static boolean isAtOrPast(EntityStatus status, EntityStatus milestone) {
    int at = WALK.indexOf(status);
    int of = WALK.indexOf(milestone);
    if (of < 0) {
      throw new IllegalArgumentException(milestone + " is not on the walk");
    }
    return at >= of;
  }

  /** Whether {@code status} is off the walk — DROPPED. */
  public static boolean isOffWalk(EntityStatus status) {
    return status == OFF_WALK;
  }

  // --- the declaration, checked -----------------------------------------------------------------

  private static Transition t(EntityStatus from, EntityStatus to, TransitionKind kind) {
    return new Transition(from, to, kind);
  }

  private static List<EntityStatus> concat(List<EntityStatus> walk, EntityStatus off) {
    List<EntityStatus> all = new ArrayList<>(walk);
    all.add(off);
    return List.copyOf(all);
  }

  private static Map<EntityStatus, List<Transition>> index() {
    Map<EntityStatus, List<Transition>> outgoing = new EnumMap<>(EntityStatus.class);
    for (EntityStatus state : STATES) {
      outgoing.put(state, new ArrayList<>());
    }
    for (Transition move : TRANSITIONS) {
      outgoing.get(move.from()).add(move);
    }
    outgoing.replaceAll((state, moves) -> List.copyOf(moves));
    return Collections.unmodifiableMap(outgoing);
  }

  /**
   * Refuses to load a declaration that says something other than the class javadoc — see "The
   * declaration checks itself" there.
   */
  private static void verify() {
    if (!EnumSet.copyOf(STATES).equals(EnumSet.allOf(EntityStatus.class))
        || STATES.size() != EntityStatus.values().length) {
      throw new IllegalStateException("the machine must declare every EntityStatus exactly once");
    }
    for (Transition move : TRANSITIONS) {
      if (!kindMatches(move)) {
        throw new IllegalStateException(move + " is not a " + move.kind() + " move");
      }
    }
    for (EntityStatus state : STATES) {
      List<Transition> moves = OUTGOING.get(state);
      if (isTerminal(state) != moves.isEmpty()) {
        throw new IllegalStateException(
            state + (isTerminal(state) ? " is terminal and has exits" : " is a dead end"));
      }
      for (int i = 1; i < moves.size(); i++) {
        if (rank(moves.get(i - 1).kind()) > rank(moves.get(i).kind())) {
          throw new IllegalStateException(state + "'s moves are not FORWARD, SKIP, BACK, DROP/REOPEN");
        }
      }
    }
  }

  /**
   * Whether a move's kind matches its ends — the check {@link #verify} applies to every declared
   * row. Package-private so the self-check's rules can be tested on rows nobody declared.
   */
  static boolean kindMatches(Transition move) {
    int from = WALK.indexOf(move.from());
    int to = WALK.indexOf(move.to());
    return switch (move.kind()) {
      case FORWARD -> from >= 0 && to == from + 1;
      case SKIP -> move.from() == EntityStatus.REFINED && to == from + 2;
      case BACK -> from >= 0 && to >= 0 && to == from - 1;
      case DROP -> from >= 0 && move.to() == OFF_WALK;
      case REOPEN -> move.from() == OFF_WALK && to >= 0;
    };
  }

  private static int rank(TransitionKind kind) {
    return switch (kind) {
      case FORWARD -> 0;
      case SKIP -> 1;
      case BACK -> 2;
      case DROP, REOPEN -> 3;
    };
  }
}
