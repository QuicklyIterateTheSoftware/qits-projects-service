package eu.wohlben.qits.entities.control;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <b>Where {@link Nesting} gets the entities the caller did not mention.</b>
 *
 * <p>A post-state check cannot be answered from the caller's own facts alone, and that is not a
 * detail of the implementation — it is the rule. Two of the three questions reach outside what was
 * stated:
 *
 * <ul>
 *   <li><b>Upwards.</b> An entity whose parent the caller did not touch still needs its parent's
 *       archetype, or the depth comparison has nothing to compare against.
 *   <li><b>Downwards.</b> Re-archetyping an entity re-judges every child it already has. Promoting
 *       a feature to an epic is legal for the feature and may be illegal for a child that was fine
 *       a moment ago — and a validator that only looked at what was mentioned would pass the
 *       promotion and leave the tree broken.
 * </ul>
 *
 * <p>So this is a lookup in two directions, and it is an interface rather than a repository call
 * because the pure rules must stay testable without a database — {@link #of(Collection)} is the
 * in-memory implementation the unit tests use, and a store-backed one over {@code
 * WorkEntityRepository} and {@code EntityMembershipRepository} is what the transition task supplies.
 * Both are <b>bulk</b> by shape for the reason those repositories carry bulk reads: a tree walked
 * one node per query is the N+1 this model makes easy.
 */
public interface EntityFacts {

  /** The stored fact for each of {@code ids} that exists. Ids that name nothing are simply absent. */
  Map<String, EntityFact> byIds(Collection<String> ids);

  /** The stored children of each of {@code parentIds}, as facts. */
  List<EntityFact> childrenOfAll(Collection<String> parentIds);

  /** One id, for readability at a call site that has exactly one. */
  default Optional<EntityFact> byId(String id) {
    return Optional.ofNullable(byIds(List.of(id)).get(id));
  }

  /** Nothing is stored — the shape a check over a self-contained post-state wants. */
  static EntityFacts none() {
    return of(List.of());
  }

  /**
   * An in-memory store over a fixed set of facts. This is what makes the nesting rules unit-testable
   * with no Quarkus application and no database at all, which matters here beyond convenience: a
   * {@code @TestProfile} is a whole Quarkus app at roughly 125 MB of retained metaspace inside a 4 GB
   * CI step, and rules that are pure functions should cost none of it.
   */
  static EntityFacts of(Collection<EntityFact> facts) {
    // LinkedHashMap, not a plain HashMap: childrenOfAll below turns this map's .values() into a
    // List that feeds Nesting.check. That is harmless today only because Nesting explicitly sorts
    // the violations it builds before returning them — if that sort is ever dropped, an unordered
    // map here would reintroduce the same run-to-run order hazard Map.copyOf caused elsewhere in
    // this module. Insertion order (the order `facts` was handed in) is deterministic and cheap
    // to keep, so there is no reason to leave it to hashing.
    Map<String, EntityFact> byId =
        facts.stream()
            .collect(
                Collectors.toMap(
                    EntityFact::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    return new EntityFacts() {
      @Override
      public Map<String, EntityFact> byIds(Collection<String> ids) {
        return ids.stream()
            .filter(byId::containsKey)
            .distinct()
            .collect(
                Collectors.toMap(
                    Function.identity(), byId::get, (a, b) -> a, LinkedHashMap::new));
      }

      @Override
      public List<EntityFact> childrenOfAll(Collection<String> parentIds) {
        return byId.values().stream()
            .filter(fact -> fact.parentId() != null && parentIds.contains(fact.parentId()))
            .toList();
      }
    };
  }
}
