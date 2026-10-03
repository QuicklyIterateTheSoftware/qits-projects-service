package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>The archetype registry, as something a client can read before it writes.</b>
 *
 * <p>A caller assembling a transition has to meet {@code Archetypes}' gate, and until this existed
 * the only way to learn what the gate wanted was to fail it: press the button, read the violations,
 * fill in the field that was named. That is the same "one problem per round trip" failure mode
 * {@code Archetypes.validate} returning every violation at once exists to avoid, one step earlier —
 * before the request rather than after it. So the registry is <em>served</em>, and a form can gather
 * what a target archetype requires as fields instead of as an error.
 *
 * <p><b>It is derived at read time and is never a second declaration.</b> Every member below is read
 * off {@link Archetypes} and {@link ArchetypeSpec} when the document is built; there is no table
 * here, nothing is written down twice, and the alternative this exists to prevent — a copy of the
 * registry in a client's own language — is a copy that drifts silently the day a property is added.
 * Adding an archetype or a property changes this document with no edit to this file.
 *
 * <p><b>Three required-lists are served and each answers a different moment.</b> {@link
 * DeclaredArchetype#required} is what a row must carry at every moment of its life and is what an
 * edit is judged against; {@link DeclaredArchetype#requiredAtCreate} is what intake demands of a row
 * being born; {@link DeclaredArchetype#requiredOnTransition} is the transition's own addition. A
 * client picks the one that matches the form it is drawing, and every one of them is exactly what
 * the server enforces at that moment — which is the property this document exists to have. It had
 * two lists until the impetus settlement, and the missing one was the reason a client gathering
 * {@code required} asked for strictly more than the server insisted on.
 *
 * <p><b>Nothing about a PAIR of archetypes is served, deliberately.</b> Whether a kind may contain
 * another is {@link Nesting#mayContain}, which is {@code parent.depth < child.depth} and nothing
 * else — so a client holding {@link DeclaredArchetype#depth} derives the whole nesting rule with the
 * same one comparison, and a served matrix would be a second spelling of it. That is the argument
 * {@code Archetypes} already makes for declaring a depth at all rather than deriving one: only the
 * ORDER of the numbers means anything, and the order is what travels.
 *
 * @param properties the whole property vocabulary, in {@link EntityProperty} declaration order. It
 *     is here because it is the language the rest of the document is written in, and because a
 *     client reading a refusal needs the <em>spelling</em>: {@code ArchetypeViolation.message()}
 *     lower-cases a property's name and turns {@code _} into a space, so "a TICKET requires impetus,
 *     and none was given" is derivable from this list rather than from a table of field names the
 *     client maintains itself
 * @param serverOwned the properties a caller neither states nor clears — {@link
 *     EntityTransitionService#SERVER_OWNED}, which is the same constant the transition's own check
 *     reads, so the served list and the enforced behaviour cannot come apart. A client renders no
 *     form field for either
 * @param archetypes one entry per {@link Archetype}, in enum order
 */
public record ArchetypeRegistryDocument(
    List<EntityProperty> properties,
    List<EntityProperty> serverOwned,
    List<DeclaredArchetype> archetypes) {

  /**
   * <b>What one archetype declares, plus the one thing the transition declares about it.</b>
   *
   * @param archetype the kind this describes
   * @param depth how deep rows of this kind sit. <b>Only the ORDER of these numbers means
   *     anything</b> — a client derives "may this contain that" as {@code parent.depth <
   *     child.depth}, exactly as {@link Nesting#mayContain} does, and must do no arithmetic on them
   * @param mayBeRoot whether a row of this kind may stand with no parent. Declared, never derived
   *     from {@link #depth}: the two come apart the moment a kind is declared above the roots
   * @param required the properties a row of this kind must carry <b>at every moment of its life</b>,
   *     in vocabulary order. This is what an UPDATE — including a transition entry, on top of
   *     {@link #requiredOnTransition}'s addition — is judged against, so a client assembling an edit
   *     may read it as the exact demand the server makes
   * @param requiredAtCreate what <b>intake</b> demands of a row being born: {@link #required} plus
   *     whatever a kind needs once and does not owe for ever, in vocabulary order. Today the two
   *     differ on exactly one entry — a {@code TICKET} requires {@code IMPETUS} at create and not
   *     afterwards, because a report consists of it and because {@code entity.impetus} is nullable
   *     so that a person can clear one. <b>A client drawing an intake form reads this list and a
   *     client drawing an edit reads {@link #required}</b>; before this axis existed there was one
   *     list, it said what intake demands, and every update path quietly demanded less than the
   *     document advertised
   * @param requiredOnTransition what a <b>transition entry</b> must carry: {@link #required} plus
   *     {@link EntityProperty#STATUS} where this kind has a lifecycle. <b>This is the transition's
   *     rule and not the registry's</b> — see {@link EntityTransitionService#requiresStatusOnTransition}
   *     for why the two differ and why an epic's status is required here and merely permitted there.
   *     It is served because the client that reads this document is filling in a transition form,
   *     and the registry's {@link #required} alone would let it submit a status-less epic and be
   *     told so only after the press
   * @param permitted every property this kind may carry, required ones included, in vocabulary
   *     order. A property outside it is refused on a write and never silently dropped
   * @param legalStatuses the status words legal on this kind, as stored; empty for a kind with no
   *     lifecycle. <b>Sorted alphabetically, and the order carries no meaning</b>: {@link
   *     ArchetypeSpec#legalStatuses} is a {@code Set<String>} and the source enum's declaration
   *     order is not recoverable from it, so a client must not read a lifecycle, an ordering or a
   *     first phase out of this list — {@link #lifecycle} is the ordered one
   * @param transitions the legal moves of this kind's lifecycle, keyed by <b>every</b> status word
   *     in {@link #lifecycle} order; each value lists the moves out of that status, FORWARD first,
   *     then BACK, then DROP/REOPEN — and DONE maps to an empty list, because DONE is final. Empty
   *     for a kind with no lifecycle. Read off {@link EntityStateMachine#transitionsFrom}, so the
   *     served moves and the enforced ones are one declaration. Added after {@link #legalStatuses}
   *     and beside it, never instead of it
   * @param lifecycle the lifecycle's states in order — the walk REPORTED → … → DONE, then DROPPED
   *     ({@link EntityStateMachine#states}); empty for a kind with no lifecycle
   * @param gathers whether this kind's children are <b>campaign memberships, never structural</b>
   *     — true for {@code CAMPAIGN} alone. A client must not draw a gathering row's children as
   *     its subtree: they hang somewhere else as well, and this row only orders them
   * @param phases what a dispatch press runs from each status, keyed by <b>every</b> status in
   *     {@link #lifecycle} order — {@link DispatchPhases}. Empty for a kind a dispatch runs no phases
   *     on: a feature or a task (no lifecycle) and a campaign (its press is its start). Read off
   *     {@link EntityStateMachine#phaseRunFrom} and {@link EntityStateMachine#flowFrom}, which the
   *     dispatch door and the FLOW hand-off follow, so the served phases and the run ones are one
   *     declaration
   */
  public record DeclaredArchetype(
      Archetype archetype,
      int depth,
      boolean mayBeRoot,
      boolean gathers,
      List<EntityProperty> required,
      List<EntityProperty> requiredAtCreate,
      List<EntityProperty> requiredOnTransition,
      List<EntityProperty> permitted,
      List<String> legalStatuses,
      Map<String, List<LegalMove>> transitions,
      List<String> lifecycle,
      Map<String, DispatchPhases> phases) {}

  /**
   * What a dispatch press does from one status.
   *
   * @param next the one phase a PHASE press runs — also the first phase of a FLOW press — or null
   *     where a press starts nothing (VERIFIED, DONE, DROPPED)
   * @param flow the phases a FLOW press runs, in order, until the flow stops at a status that starts
   *     no phase (VERIFIED, where the release is asked for). Empty where a press starts nothing. A
   *     block stops a flow early; that is not in the data
   */
  public record DispatchPhases(DispatchPhase next, List<DispatchPhase> flow) {}

  /**
   * One phase a dispatch runs.
   *
   * @param phase the phase's word: {@code refine}, {@code implement} or {@code verify} — the word
   *     the dispatch door answers in {@code phase} and {@code GET /entities/{id}/dispatch} in {@code
   *     nextPhase}
   * @param from the status the phase runs from
   * @param enters the status the platform moves the entity into when the phase starts (REFINED →
   *     IMPLEMENTING, IMPLEMENTED → VERIFYING), or null where it moves nothing
   * @param endsIn the status the phase's agent moves the entity to when the phase is done
   */
  public record DispatchPhase(String phase, String from, String enters, String endsIn) {

    static DispatchPhase of(EntityStateMachine.PhaseRun run) {
      return new DispatchPhase(
          run.phase().word(),
          run.from().name(),
          run.enters() == null ? null : run.enters().name(),
          run.endsIn().name());
    }
  }

  /**
   * One legal move out of a status, as served: where it lands and what kind of move it is.
   *
   * @param to the status the move lands on
   * @param kind FORWARD, BACK, DROP or REOPEN — {@link EntityStateMachine.TransitionKind}
   */
  public record LegalMove(String to, EntityStateMachine.TransitionKind kind) {}

  /**
   * The document as the registry stands right now.
   *
   * <p>Every list is ordered, because an answer a client diffs or a test asserts must not depend on
   * a hash order: the property sets take {@link EntityProperty} declaration order — the same order
   * {@code Archetypes.validate} reports violations in, so a form's fields and a refusal's complaints
   * read in one sequence — and the archetypes take {@link Archetype} enum order.
   */
  public static ArchetypeRegistryDocument describe() {
    List<DeclaredArchetype> declared = new ArrayList<>();
    for (Archetype archetype : Archetype.values()) {
      ArchetypeSpec spec = Archetypes.spec(archetype);
      declared.add(
          new DeclaredArchetype(
              archetype,
              spec.depth(),
              spec.mayBeRoot(),
              spec.gathers(),
              inVocabularyOrder(spec.required()),
              inVocabularyOrder(spec.requiredAtCreate()),
              inVocabularyOrder(requiredOnTransition(spec)),
              inVocabularyOrder(spec.permitted()),
              spec.legalStatuses().stream().sorted().toList(),
              transitions(spec),
              lifecycle(spec),
              phases(spec)));
    }
    return new ArchetypeRegistryDocument(
        List.of(EntityProperty.values()),
        inVocabularyOrder(EntityTransitionService.SERVER_OWNED),
        List.copyOf(declared));
  }

  /**
   * {@code required} plus {@code STATUS} where the transition demands one. The condition itself is
   * {@link EntityTransitionService#requiresStatusOnTransition} and is asked here rather than
   * restated, so the document and the check it describes are one rule.
   */
  private static Set<EntityProperty> requiredOnTransition(ArchetypeSpec spec) {
    if (!EntityTransitionService.requiresStatusOnTransition(spec)) {
      return spec.required();
    }
    // An EnumSet and not a copy of the list: a TICKET already requires STATUS, so the addition is
    // an idempotent one rather than a second entry.
    EnumSet<EntityProperty> widened = EnumSet.noneOf(EntityProperty.class);
    widened.addAll(spec.required());
    widened.add(EntityProperty.STATUS);
    return widened;
  }

  /**
   * The machine's moves, keyed by every state in lifecycle order — or nothing, for a kind with no
   * lifecycle. An insertion-ordered map behind an unmodifiable view and <b>not</b> {@code
   * Map.copyOf}, whose iteration order is salted per JVM: the key order is part of what is served.
   */
  private static Map<String, List<LegalMove>> transitions(ArchetypeSpec spec) {
    if (spec.legalStatuses().isEmpty()) {
      return Map.of();
    }
    Map<String, List<LegalMove>> served = new LinkedHashMap<>();
    for (EntityStatus state : EntityStateMachine.states(spec.archetype())) {
      served.put(
          state.name(),
          EntityStateMachine.transitionsFrom(spec.archetype(), state).stream()
              .map(move -> new LegalMove(move.to().name(), move.kind()))
              .toList());
    }
    return Collections.unmodifiableMap(served);
  }

  /**
   * What a press runs from each state, keyed in lifecycle order — or nothing for a kind a dispatch
   * runs no phases on. Insertion-ordered for {@link #transitions}' reason.
   */
  private static Map<String, DispatchPhases> phases(ArchetypeSpec spec) {
    if (spec.legalStatuses().isEmpty() || !EntityStateMachine.runsPhases(spec.archetype())) {
      return Map.of();
    }
    Map<String, DispatchPhases> served = new LinkedHashMap<>();
    for (EntityStatus state : EntityStateMachine.states(spec.archetype())) {
      served.put(
          state.name(),
          new DispatchPhases(
              EntityStateMachine.phaseRunFrom(spec.archetype(), state)
                  .map(DispatchPhase::of)
                  .orElse(null),
              EntityStateMachine.flowFrom(spec.archetype(), state).stream()
                  .map(DispatchPhase::of)
                  .toList()));
    }
    return Collections.unmodifiableMap(served);
  }

  /** The machine's states in lifecycle order, or nothing for a kind with no lifecycle. */
  private static List<String> lifecycle(ArchetypeSpec spec) {
    if (spec.legalStatuses().isEmpty()) {
      return List.of();
    }
    return EntityStateMachine.states(spec.archetype()).stream().map(EntityStatus::name).toList();
  }

  /** The members of {@code properties}, in the vocabulary's own declaration order. */
  private static List<EntityProperty> inVocabularyOrder(Set<EntityProperty> properties) {
    List<EntityProperty> ordered = new ArrayList<>();
    for (EntityProperty property : EntityProperty.values()) {
      if (properties.contains(property)) {
        ordered.add(property);
      }
    }
    return List.copyOf(ordered);
  }
}
