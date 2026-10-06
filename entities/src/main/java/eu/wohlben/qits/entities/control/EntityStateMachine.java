package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
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
 *   REPORTED ─▶ REFINED ─▶ READY_FOR_DEV ─▶ IMPLEMENTING ─▶ IMPLEMENTED ─▶ VERIFYING ─▶ VERIFIED ─▶ DONE
 *            ◀─         ◀─                                ◀─             ◀─           ◀─       (final)
 *                                 └──────── SKIP ─────────▶ └──────── SKIP ───────▶
 *   FORWARD one step right, BACK one step left (below DONE, and never out of IMPLEMENTING), the two
 *   SKIPs over an "-ING" status, DROP from every open state to DROPPED, and DROPPED REOPENs to
 *   REPORTED.
 *
 *   starts phase:  REPORTED → refine,  READY_FOR_DEV and IMPLEMENTING → implement,
 *                  IMPLEMENTED and VERIFYING → verify;  REFINED (it waits for a person), VERIFIED,
 *                  DONE and DROPPED start nothing.
 *   ends in:       refine → REFINED, implement → IMPLEMENTED, verify → VERIFIED ({@link #endOf}).
 *   runs phases:   EPIC and TICKET only ({@link #runsPhases}); a FLOW press chains the phases
 *                  ({@link #flowFrom}) until a status starts none — so a FLOW from REPORTED stops
 *                  at REFINED.
 * </pre>
 *
 * <p><b>The walk</b> — REPORTED → REFINED → READY_FOR_DEV → IMPLEMENTING → IMPLEMENTED → VERIFYING
 * → VERIFIED → DONE — is taken
 * one step at a time: forward as each phase finishes, back when a claim turns out wrong. Asking for
 * the status an entity already has is refused rather than read as a no-op. A BACK move is a
 * correction, not how a phase reports failure: a phase that cannot finish, a failed verification
 * included, blocks the entity where it stands (qits-592).
 *
 * <p><b>READY_FOR_DEV is a person's scheduling decision</b> (qits-887). REFINED says the entity
 * says what to do; it starts no phase and waits until a person schedules it, REFINED →
 * READY_FOR_DEV, and only from there does implement run. Scheduling can be taken back — READY_FOR_DEV
 * → REFINED — until the work starts, but not further: there is no READY_FOR_DEV → REPORTED, and
 * once the platform moved the entity to IMPLEMENTING it has no BACK move at all; the way out of
 * started work is DROP.
 *
 * <p><b>IMPLEMENTING and VERIFYING are the two steps that may be skipped</b> (qits-749). The
 * platform enters each at the press (or the FLOW hand-off) that starts its phase, so each records a
 * fact rather than a claim kept by hand — but an agent that finished work nobody moved to the "-ING"
 * status first must still be able to say so, so READY_FOR_DEV → IMPLEMENTED and IMPLEMENTED →
 * VERIFIED stay legal as {@link TransitionKind#SKIP}s: two steps along the walk, and from
 * READY_FOR_DEV or IMPLEMENTED only — never from REFINED, so the skip cannot bypass the person's
 * scheduling. FORWARD stays one step; the skip is its own kind rather than a loosened FORWARD, so
 * no other step can be jumped.
 *
 * <p><b>DONE is final: it has no exits</b> — not back to VERIFIED, not to DROPPED, not anywhere.
 * Acceptance could only ever throw a done item further back than VERIFIED, which is not a flow to
 * support; a done development that later turns out wrong is a <em>new</em> ticket or epic, which may
 * refer to the done one. Below DONE the walk stays reversible.
 *
 * <p><b>DROPPED is off the walk.</b> It is reachable (DROP) from every state that is still open —
 * REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING, IMPLEMENTED, VERIFYING, VERIFIED — because a decision not to do the work can
 * be taken at any point while the work is open, and from nowhere else. It reopens (REOPEN) to
 * REPORTED and to nothing else: somebody who has changed their mind about abandoned work is asking
 * what it is for again, which is the refine phase.
 *
 * <p><b>A campaign walks the same machine with IMPLEMENTING and VERIFYING elided</b> (qits-749). A
 * campaign enters neither — its press starts it — so for that archetype the two are taken out and
 * its lifecycle is <em>the walk with the elided states removed</em>: REPORTED → REFINED →
 * READY_FOR_DEV → IMPLEMENTED → VERIFIED → DONE (qits-887, decision 19 — a campaign's
 * READY_FOR_DEV says it is ready for development, not that it runs). A kept state's FORWARD (or a
 * SKIP that lands on the same state) goes to the next kept state, its BACK to the previous kept
 * state, and DROP/REOPEN are unchanged; however many elided states sit side by side, the gap closes.
 * It is derived from {@link #TRANSITIONS}, never declared a second time — see {@link
 * #transitionsFrom(Archetype, EntityStatus)}.
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
     * Two steps along the walk, over an "-ING" status: READY_FOR_DEV → IMPLEMENTED or IMPLEMENTED →
     * VERIFIED, for work finished without being marked started. Legal from those two only.
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
          EntityStatus.READY_FOR_DEV,
          EntityStatus.IMPLEMENTING,
          EntityStatus.IMPLEMENTED,
          EntityStatus.VERIFYING,
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
          t(EntityStatus.REFINED, EntityStatus.READY_FOR_DEV, TransitionKind.FORWARD),
          t(EntityStatus.REFINED, EntityStatus.REPORTED, TransitionKind.BACK),
          t(EntityStatus.REFINED, EntityStatus.DROPPED, TransitionKind.DROP),
          t(EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTING, TransitionKind.FORWARD),
          t(EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTED, TransitionKind.SKIP),
          t(EntityStatus.READY_FOR_DEV, EntityStatus.REFINED, TransitionKind.BACK),
          t(EntityStatus.READY_FOR_DEV, EntityStatus.DROPPED, TransitionKind.DROP),
          t(EntityStatus.IMPLEMENTING, EntityStatus.IMPLEMENTED, TransitionKind.FORWARD),
          t(EntityStatus.IMPLEMENTING, EntityStatus.DROPPED, TransitionKind.DROP),
          t(EntityStatus.IMPLEMENTED, EntityStatus.VERIFYING, TransitionKind.FORWARD),
          t(EntityStatus.IMPLEMENTED, EntityStatus.VERIFIED, TransitionKind.SKIP),
          t(EntityStatus.IMPLEMENTED, EntityStatus.IMPLEMENTING, TransitionKind.BACK),
          t(EntityStatus.IMPLEMENTED, EntityStatus.DROPPED, TransitionKind.DROP),
          t(EntityStatus.VERIFYING, EntityStatus.VERIFIED, TransitionKind.FORWARD),
          t(EntityStatus.VERIFYING, EntityStatus.IMPLEMENTED, TransitionKind.BACK),
          t(EntityStatus.VERIFYING, EntityStatus.DROPPED, TransitionKind.DROP),
          t(EntityStatus.VERIFIED, EntityStatus.DONE, TransitionKind.FORWARD),
          t(EntityStatus.VERIFIED, EntityStatus.VERIFYING, TransitionKind.BACK),
          t(EntityStatus.VERIFIED, EntityStatus.DROPPED, TransitionKind.DROP),
          t(EntityStatus.DROPPED, EntityStatus.REPORTED, TransitionKind.REOPEN));

  /**
   * The phase each state starts; a state absent here starts none. REFINED is absent on purpose
   * (qits-887): it waits for a person to schedule it, and implement runs from READY_FOR_DEV.
   */
  private static final Map<EntityStatus, Phase> PHASES =
      Map.of(
          EntityStatus.REPORTED, Phase.REFINE,
          EntityStatus.READY_FOR_DEV, Phase.IMPLEMENT,
          EntityStatus.IMPLEMENTING, Phase.IMPLEMENT,
          EntityStatus.IMPLEMENTED, Phase.VERIFY,
          EntityStatus.VERIFYING, Phase.VERIFY);

  /**
   * The "-ING" status the platform moves an entity into when the phase its status starts is
   * started (qits-749): READY_FOR_DEV → IMPLEMENTING, IMPLEMENTED → VERIFYING. Checked at load:
   * each is a FORWARD move into a state that runs the same phase.
   */
  private static final Map<EntityStatus, EntityStatus> STARTED =
      Map.of(
          EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTING,
          EntityStatus.IMPLEMENTED, EntityStatus.VERIFYING);

  /**
   * The archetypes a dispatch runs phases on: the two the dispatch door puts an agent on. A
   * campaign has a lifecycle but its press is its start (its executor dispatches its members), and
   * a feature or a task walks the lifecycle with no phase of its own (qits-763): its work runs in
   * its epic's dispatch. Read by the dispatch door and the served registry alike.
   */
  private static final Set<Archetype> PHASE_ARCHETYPES = EnumSet.of(Archetype.EPIC, Archetype.TICKET);

  /** {@link #TRANSITIONS} indexed by source, order kept; every state has an entry. */
  private static final Map<EntityStatus, List<Transition>> OUTGOING = index();

  /**
   * The states an archetype's lifecycle elides from the walk; absent means none. A campaign keeps
   * READY_FOR_DEV (qits-887, decision 19) and elides the two "-ING" statuses.
   */
  private static final Map<Archetype, Set<EntityStatus>> ELIDED =
      Map.of(Archetype.CAMPAIGN, EnumSet.of(EntityStatus.IMPLEMENTING, EntityStatus.VERIFYING));

  /**
   * The statuses a started campaign runs at (qits-887, decision 19): REFINED, and READY_FOR_DEV —
   * which means "ready for development", not "start it". The start press is accepted at either, the
   * executor claims at either, and moving between the two neither starts nor pauses anything; a
   * campaign that leaves both is paused.
   */
  private static final Set<EntityStatus> CAMPAIGN_RUNS =
      EnumSet.of(EntityStatus.REFINED, EntityStatus.READY_FOR_DEV);

  static {
    verify();
  }

  private EntityStateMachine() {}

  // --- the operations ---------------------------------------------------------------------------

  /**
   * Every state, in lifecycle order: REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING, IMPLEMENTED,
   * VERIFYING, VERIFIED, DONE, DROPPED.
   */
  public static List<EntityStatus> states() {
    return STATES;
  }

  /**
   * The walk alone, in order: REPORTED → REFINED → READY_FOR_DEV → IMPLEMENTING → IMPLEMENTED →
   * VERIFYING → VERIFIED → DONE.
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

  // --- per archetype (a campaign elides IMPLEMENTING and VERIFYING) ------------------------------

  /** The states {@code archetype}'s lifecycle holds, in lifecycle order. */
  public static List<EntityStatus> states(Archetype archetype) {
    Set<EntityStatus> elided = elided(archetype);
    return STATES.stream().filter(state -> !elided.contains(state)).toList();
  }

  /**
   * The legal moves out of {@code from} for {@code archetype}: {@link #transitionsFrom(EntityStatus)}
   * read over <em>the walk with the archetype's elided states removed</em>. A kept state that has a
   * FORWARD (or a SKIP landing on the same place) moves FORWARD to the next kept state; a SKIP to a
   * kept state further on stays a SKIP; one that has a BACK moves BACK to the previous kept state;
   * DROP and REOPEN are unchanged. So however many elided states sit next to each other, the walk
   * stays closed over the gap. Empty for a state the archetype does not hold. The order (FORWARD,
   * SKIP, BACK, DROP/REOPEN) is kept.
   */
  public static List<Transition> transitionsFrom(Archetype archetype, EntityStatus from) {
    Set<EntityStatus> elided = elided(archetype);
    if (elided.isEmpty()) {
      return transitionsFrom(from);
    }
    if (elided.contains(from)) {
      return List.of();
    }
    List<EntityStatus> kept = WALK.stream().filter(state -> !elided.contains(state)).toList();
    int at = kept.indexOf(from);
    EntityStatus next = at >= 0 && at + 1 < kept.size() ? kept.get(at + 1) : null;
    EntityStatus previous = at > 0 ? kept.get(at - 1) : null;
    List<Transition> moves = new ArrayList<>();
    for (Transition move : transitionsFrom(from)) {
      Transition collapsed =
          switch (move.kind()) {
            case FORWARD -> next == null ? null : new Transition(from, next, TransitionKind.FORWARD);
            case SKIP ->
                elided.contains(move.to()) || next == null
                    ? null
                    : move.to() == next
                        ? new Transition(from, next, TransitionKind.FORWARD)
                        : move;
            case BACK ->
                previous == null ? null : new Transition(from, previous, TransitionKind.BACK);
            case DROP, REOPEN -> elided.contains(move.to()) ? null : move;
          };
      if (collapsed != null && !moves.contains(collapsed)) {
        moves.add(collapsed);
      }
    }
    // FORWARD before SKIP before BACK before DROP/REOPEN, as the declaration orders them.
    moves.sort((a, b) -> Integer.compare(rank(a.kind()), rank(b.kind())));
    return List.copyOf(moves);
  }

  /** Whether {@code from → to} is a legal move for {@code archetype}. */
  public static boolean allows(Archetype archetype, EntityStatus from, EntityStatus to) {
    return transitionsFrom(archetype, from).stream().anyMatch(move -> move.to() == to);
  }

  /** {@link #refusal(EntityStatus, EntityStatus)} over {@code archetype}'s moves. */
  public static Optional<String> refusal(Archetype archetype, EntityStatus from, EntityStatus to) {
    if (allows(archetype, from, to)) {
      return Optional.empty();
    }
    String refused = "cannot move from " + from + " to " + to;
    return Optional.of(isTerminal(from) ? refused + ": " + finality(from) : refused);
  }

  private static Set<EntityStatus> elided(Archetype archetype) {
    return ELIDED.getOrDefault(archetype, Set.of());
  }

  /**
   * The status the platform moves {@code status} into when the phase {@code status} starts is
   * started — IMPLEMENTING from READY_FOR_DEV, VERIFYING from IMPLEMENTED — or empty where there is none
   * (an "-ING" status itself, which already says its phase was started, among them).
   */
  public static Optional<EntityStatus> startedStatusOf(EntityStatus status) {
    return Optional.ofNullable(STARTED.get(status));
  }

  /** Whether {@code from → to} is the platform's own "phase started" move — see {@link #STARTED}. */
  public static boolean isStartedMove(EntityStatus from, EntityStatus to) {
    return from != null && to != null && STARTED.get(from) == to;
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

  // --- dispatch phases, derived ------------------------------------------------------------------

  /**
   * One phase a dispatch runs.
   *
   * @param phase the phase that runs
   * @param from the status it runs from
   * @param enters the "-ING" status the platform moves the entity into when the phase starts
   *     ({@link #startedStatusOf}), or null where it moves nothing
   * @param endsIn the status the phase ends in when it succeeds ({@link #endOf})
   */
  public record PhaseRun(Phase phase, EntityStatus from, EntityStatus enters, EntityStatus endsIn) {}

  /** Whether a dispatch runs phases on {@code archetype} — an epic or a ticket. */
  public static boolean runsPhases(Archetype archetype) {
    return PHASE_ARCHETYPES.contains(archetype);
  }

  /**
   * The status {@code phase} ends in: the first status on the walk past the statuses that start it.
   * REFINE ends in REFINED, IMPLEMENT in IMPLEMENTED (it is started by READY_FOR_DEV and
   * IMPLEMENTING), VERIFY in VERIFIED. Derived from {@link
   * #PHASES} and the walk, so it moves with them.
   */
  public static EntityStatus endOf(Phase phase) {
    boolean seen = false;
    for (EntityStatus status : WALK) {
      boolean starts = PHASES.get(status) == phase;
      if (seen && !starts) {
        return status;
      }
      seen |= starts;
    }
    throw new IllegalStateException(phase + " is started by no status on the walk, or never ends");
  }

  /**
   * The phase one dispatch press runs on {@code archetype} from {@code status} — a PHASE press, and
   * the first phase of a FLOW press — or empty where a press starts nothing: REFINED (it waits for
   * a person to schedule it), VERIFIED, DONE, DROPPED, and every status of an archetype that runs
   * no phases ({@link #runsPhases}).
   */
  public static Optional<PhaseRun> phaseRunFrom(Archetype archetype, EntityStatus status) {
    if (!runsPhases(archetype) || status == null) {
      return Optional.empty();
    }
    return phaseStartedBy(status)
        .map(phase -> new PhaseRun(phase, status, STARTED.get(status), endOf(phase)));
  }

  /**
   * The phases a FLOW press runs on {@code archetype} from {@code status}, in order, until the flow
   * stops: each phase ends in its {@link PhaseRun#endsIn}, and the next one runs from there, until a
   * status starts no phase (REFINED, where a person schedules it, or VERIFIED, where the release is
   * asked for). Empty where a press starts
   * nothing. The run also stops early when an agent blocks the entity; that is not in the data.
   */
  public static List<PhaseRun> flowFrom(Archetype archetype, EntityStatus status) {
    List<PhaseRun> flow = new ArrayList<>();
    Optional<PhaseRun> run = phaseRunFrom(archetype, status);
    while (run.isPresent() && flow.size() < STATES.size()) {
      flow.add(run.get());
      run = phaseRunFrom(archetype, run.get().endsIn());
    }
    return List.copyOf(flow);
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

  /** Whether a started campaign at {@code statusWord} runs — see {@link #CAMPAIGN_RUNS}. */
  public static boolean campaignRunsAt(String statusWord) {
    return statusWord != null
        && CAMPAIGN_RUNS.stream().anyMatch(status -> status.name().equals(statusWord));
  }

  /** The statuses a started campaign runs at, in walk order — see {@link #CAMPAIGN_RUNS}. */
  public static List<EntityStatus> campaignRunStatuses() {
    return WALK.stream().filter(CAMPAIGN_RUNS::contains).toList();
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
    for (Map.Entry<EntityStatus, EntityStatus> started : STARTED.entrySet()) {
      Optional<Transition> move = transition(started.getKey(), started.getValue());
      if (move.isEmpty()
          || move.get().kind() != TransitionKind.FORWARD
          || PHASES.get(started.getKey()) != PHASES.get(started.getValue())) {
        throw new IllegalStateException(started + " is not a forward move within one phase");
      }
    }
    for (Phase phase : Phase.values()) {
      // Every phase ends on a later step of the walk, so a flow always moves forward and stops.
      EntityStatus end = endOf(phase);
      for (EntityStatus start : WALK) {
        if (PHASES.get(start) == phase && WALK.indexOf(end) <= WALK.indexOf(start)) {
          throw new IllegalStateException(phase + " ends in " + end + ", not past " + start);
        }
      }
    }
    for (Archetype archetype : PHASE_ARCHETYPES) {
      if (ELIDED.containsKey(archetype)) {
        throw new IllegalStateException(archetype + " runs phases, so it may elide no state");
      }
    }
    for (Archetype archetype : ELIDED.keySet()) {
      for (EntityStatus state : states(archetype)) {
        List<Transition> moves = transitionsFrom(archetype, state);
        if (isTerminal(state) != moves.isEmpty()) {
          throw new IllegalStateException(archetype + ": " + state + " is a dead end or has exits");
        }
        for (Transition move : moves) {
          if (!states(archetype).contains(move.to())) {
            throw new IllegalStateException(archetype + ": " + move + " leaves its lifecycle");
          }
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
      case SKIP ->
          (move.from() == EntityStatus.READY_FOR_DEV || move.from() == EntityStatus.IMPLEMENTED)
              && to == from + 2;
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
