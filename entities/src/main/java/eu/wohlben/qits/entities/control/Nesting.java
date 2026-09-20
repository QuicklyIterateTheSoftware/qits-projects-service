package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <b>Which memberships are legal, judged over a whole post-state.</b>
 *
 * <p>It sits on {@link Archetypes} and holds no vocabulary of its own: the rule is stated in terms
 * of a declared depth and a declared rootness flag, and <b>no archetype is named anywhere in this
 * class</b>. That is what makes a fifth kind a declaration rather than an edit here.
 *
 * <h2>The rule</h2>
 *
 * <p><b>A membership is legal when the parent's depth is strictly less than the child's.</b> One
 * sentence, and three consequences worth spelling out because each is a decision:
 *
 * <ul>
 *   <li><b>Levels may be skipped.</b> An epic holding a task directly is legal — depth 0 contains
 *       depth 2 — because the rule is about containment and not about a fixed number of rungs. A
 *       "parent depth + 1" rule would forbid it, and forbidding it would mean inventing a feature
 *       nobody wanted every time an epic needs one concrete piece of work.
 *   <li><b>Equal or above is refused.</b> Epic-under-epic, ticket-under-ticket, task-under-task, and
 *       — because the two roots are declared at the same depth — ticket-under-epic. That last one is
 *       V4's "nothing joins the two tables and nothing should" said as a rule instead of as prose.
 *   <li><b>A root has no membership, and whether a kind may be one is declared</b> rather than read
 *       off the depths. See {@link Archetypes} for the argument; the short version is that a
 *       campaign declared above the epics would make the epic no longer shallowest while leaving it
 *       very much a root.
 * </ul>
 *
 * <h2>Why it is a post-state and not a pair</h2>
 *
 * <p>The operation this exists for is a promotion: a feature becomes an epic and stops hanging under
 * the one it was part of. <b>Neither half is legal on its own.</b> Re-archetype first and there is
 * an epic under an epic; reparent first and there is a feature at the root. Only the two together
 * are a legal tree, so the only question worth asking is about the state <em>after</em> the change,
 * and asking it row by row would refuse an operation that is correct. That is the whole reason the
 * multi-entity transition API exists, and this method is its gate.
 *
 * <p><b>The post-state includes entities the caller did not mention.</b> A stated fact overrides the
 * store; everything else is read from it, in both directions — upwards, because a child needs its
 * untouched parent's archetype, and downwards, because re-archetyping something re-judges every
 * child it already has. {@link EntityFacts} is where that reading comes from and why it is bulk.
 *
 * <p><b>One call, every violation.</b> A caller fixing one problem per round trip is the failure
 * mode to avoid, and it is worse here than in the property gate: the fixes are moves, so being told
 * about them one at a time means walking a tree through several invalid shapes to reach a valid one.
 */
public final class Nesting {

  /**
   * How far up an ancestor walk may go before it is called a cycle. Depth is bounded by the number
   * of declared archetypes in any legal tree, so anything past the size of the graph is already a
   * loop; the slack is so a bug here reports a cycle rather than hanging.
   */
  private static final int MAX_WALK = 64;

  private Nesting() {}

  /** A post-state that stands entirely on its own — no store behind it. */
  public static List<NestingViolation> check(Collection<EntityFact> postState) {
    return check(postState, EntityFacts.none());
  }

  /**
   * <b>Every illegal membership in the post-state {@code stated} describes</b>, in id order, or an
   * empty list.
   *
   * @param stated the facts the caller intends after its change — one per entity it is touching.
   *     Each is the <em>whole</em> fact about that entity: a null parent means a root and never
   *     "unchanged"
   * @param store where the entities the caller did not mention come from
   * @throws IllegalArgumentException if {@code stated} names one entity twice, which is a caller
   *     that has not decided what it wants rather than a tree that is wrong
   */
  public static List<NestingViolation> check(Collection<EntityFact> stated, EntityFacts store) {
    Map<String, EntityFact> intended = index(stated);

    // Everything that has to be judged: what the caller stated, plus every stored child of a stated
    // entity — because a parent's archetype changing re-judges children nobody mentioned.
    Map<String, EntityFact> subjects = new LinkedHashMap<>(intended);
    for (EntityFact child : store.childrenOfAll(intended.keySet())) {
      subjects.putIfAbsent(child.id(), child);
    }

    Resolver resolver = new Resolver(intended, store);
    // One bulk read for every parent named by a subject, so the loop below resolves from memory.
    resolver.prefetchParentsOf(subjects.values());

    List<NestingViolation> violations = new ArrayList<>();
    for (EntityFact subject : subjects.values()) {
      judge(subject, resolver, violations);
    }

    violations.sort(
        Comparator.comparing(NestingViolation::entityId)
            .thenComparing(violation -> violation.reason().ordinal()));
    return List.copyOf(violations);
  }

  /** The three questions asked of one subject; at most one violation each, and the first stops the rest. */
  private static void judge(
      EntityFact subject, Resolver resolver, List<NestingViolation> violations) {

    if (subject.isRoot()) {
      if (!Archetypes.mayBeRoot(subject.archetype())) {
        violations.add(
            new NestingViolation(
                subject.id(),
                subject.archetype(),
                null,
                null,
                NestingViolation.Reason.NOT_A_ROOT));
      }
      return;
    }

    if (Objects.equals(subject.id(), subject.parentId())) {
      violations.add(cycle(subject));
      return;
    }

    EntityFact parent = resolver.resolve(subject.parentId());
    if (parent == null) {
      violations.add(
          new NestingViolation(
              subject.id(),
              subject.archetype(),
              subject.parentId(),
              null,
              NestingViolation.Reason.UNKNOWN_PARENT));
      return;
    }

    if (Archetypes.depth(parent.archetype()) >= Archetypes.depth(subject.archetype())) {
      violations.add(
          new NestingViolation(
              subject.id(),
              subject.archetype(),
              parent.id(),
              parent.archetype(),
              NestingViolation.Reason.NOT_NESTABLE));
      return;
    }

    // Cycle safety. A graph legal by depth cannot contain one — every edge strictly increases depth,
    // so a walk upwards terminates — and the walk is a handful of map lookups over facts already in
    // hand, so it is asserted rather than assumed. It only ever fires on a post-state whose depths
    // are wrong in a way the per-edge check above did not reach, which is the case worth catching.
    if (closesOnItself(subject, resolver)) {
      violations.add(cycle(subject));
    }
  }

  private static boolean closesOnItself(EntityFact subject, Resolver resolver) {
    Set<String> seen = new HashSet<>();
    seen.add(subject.id());
    EntityFact walker = subject;
    for (int step = 0; step < MAX_WALK; step++) {
      String parentId = walker.parentId();
      if (parentId == null) {
        return false;
      }
      if (!seen.add(parentId)) {
        return true;
      }
      walker = resolver.resolve(parentId);
      if (walker == null) {
        return false;
      }
    }
    return true;
  }

  private static NestingViolation cycle(EntityFact subject) {
    return new NestingViolation(
        subject.id(),
        subject.archetype(),
        subject.parentId(),
        null,
        NestingViolation.Reason.CYCLE);
  }

  private static Map<String, EntityFact> index(Collection<EntityFact> stated) {
    Map<String, EntityFact> indexed = new LinkedHashMap<>();
    for (EntityFact fact : stated) {
      if (indexed.put(fact.id(), fact) != null) {
        throw new IllegalArgumentException(
            "the post-state states " + fact.id() + " twice — it has to say one thing about it");
      }
    }
    return indexed;
  }

  /**
   * Reads a fact by id: the caller's statement wins, then anything already fetched, then the store.
   * The store is asked in bulk where the ids are known up front and one at a time only on the
   * ancestor walk, which is bounded by the depth of a tree rather than by its size.
   */
  private static final class Resolver {

    private final Map<String, EntityFact> intended;
    private final EntityFacts store;
    private final Map<String, EntityFact> fetched = new HashMap<>();
    private final Set<String> missing = new HashSet<>();

    Resolver(Map<String, EntityFact> intended, EntityFacts store) {
      this.intended = intended;
      this.store = store;
    }

    void prefetchParentsOf(Collection<EntityFact> subjects) {
      Set<String> wanted = new HashSet<>();
      for (EntityFact subject : subjects) {
        String parentId = subject.parentId();
        if (parentId != null && !intended.containsKey(parentId)) {
          wanted.add(parentId);
        }
      }
      fetch(wanted);
    }

    EntityFact resolve(String id) {
      EntityFact stated = intended.get(id);
      if (stated != null) {
        return stated;
      }
      EntityFact known = fetched.get(id);
      if (known != null || missing.contains(id)) {
        return known;
      }
      fetch(Set.of(id));
      return fetched.get(id);
    }

    private void fetch(Set<String> ids) {
      if (ids.isEmpty()) {
        return;
      }
      Map<String, EntityFact> found = store.byIds(ids);
      fetched.putAll(found);
      for (String id : ids) {
        if (!found.containsKey(id)) {
          missing.add(id);
        }
      }
    }
  }

  /**
   * Whether {@code parent} may contain {@code child} — the rule in its smallest form, for a caller
   * that genuinely has a single pair and no state around it. <b>Prefer {@link #check(Collection,
   * EntityFacts)}</b>: a pair check cannot see the promotion case, and a caller that reaches for
   * this one in a loop has written the row-by-row validator this class exists to replace.
   */
  public static boolean mayContain(Archetype parent, Archetype child) {
    return Archetypes.depth(parent) < Archetypes.depth(child);
  }
}
