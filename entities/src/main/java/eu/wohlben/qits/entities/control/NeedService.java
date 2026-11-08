package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkNeed;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import eu.wohlben.qits.entities.persistence.WorkNeedRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongFunction;
import org.jboss.logging.Logger;

/**
 * <b>What a work item needs</b> (qits-1903, epic qits-1899): "item B needs item A at a status",
 * the one ordering mechanism between work items (qits-1899). The rows are
 * {@link WorkNeed} (epics V40); whether one is met is read off the needed item's status at the time
 * it is asked ({@link NeedRule#reached}) and never latched.
 *
 * <ul>
 *   <li><b>The write</b> ({@link #replace}) states the whole set, like the tags: an empty list
 *       clears it. Neither scope nor a marker, so no status and no freeze refuses it — ordering must
 *       be fixable after REFINED — and no transition touches it. {@link #refusals} is what the
 *       doors judge a stated set by, every violation at once, before anything is written.
 *   <li><b>The reads</b> ({@link #ofEntity}, {@link #ofEntities}, {@link #ofProject}) answer
 *       {@link NeedRef}s, batched: one query for the needs, one for the needed items, never one per
 *       row of a page.
 *   <li><b>The flip</b> ({@link #announceMet}): after a transition committed, the needs on what
 *       moved that turned from unmet to met are fired as {@link NeedsMet}. A need turning unmet
 *       again, and a write naming an item already met, announce nothing — a write is not something
 *       reaching its status.
 * </ul>
 */
@ApplicationScoped
public class NeedService {

  private static final Logger LOG = Logger.getLogger(NeedService.class);

  /** The kinds a need may name: the work that has a walk of its own. */
  private static final Set<Archetype> NEEDABLE =
      Set.of(Archetype.EPIC, Archetype.TICKET, Archetype.FEATURE, Archetype.TASK);

  @Inject WorkNeedRepository needs;

  @Inject WorkEntityRepository entities;

  @Inject ReadPatience reads;

  @Inject WritePatience writes;

  @Inject Event<NeedsMet> met;

  /**
   * One stated need, as a door hands it on.
   *
   * @param named the item as the caller wrote it — what a refusal quotes
   * @param entityId the item's UUID when {@code named} resolved to one, else {@code named} itself
   *     (or null), for {@link #refusals} to name as no work item of this project
   * @param status the status it must reach, as stated
   */
  public record Stated(String named, String entityId, String status) {}

  /** The few columns of an item a need reads: never the whole row and its collections. */
  private record Item(
      String id, String projectId, Archetype archetype, String status, String title, long number) {}

  // --- the reads ------------------------------------------------------------------------------------

  /** What {@code entityId} needs, in the order written; {@code []} when nothing. */
  public List<NeedRef> ofEntity(String entityId) {
    return ofEntities(List.of(entityId)).getOrDefault(entityId, List.of());
  }

  /** What every item of {@code entityIds} needs, keyed by item id — two queries. */
  public Map<String, List<NeedRef>> ofEntities(Collection<String> entityIds) {
    if (entityIds == null || entityIds.isEmpty()) {
      return Map.of();
    }
    return reads.hold(
        "work needs read",
        () -> QuarkusTransaction.requiringNew().call(() -> refs(needs.ofEntities(entityIds))));
  }

  /**
   * What every item of {@code projectId} needs, keyed by item id — two queries for the whole
   * project, which is what a project's work listing reads (a need never leaves its project).
   */
  public Map<String, List<NeedRef>> ofProject(String projectId) {
    return reads.hold(
        "project work needs read",
        () -> QuarkusTransaction.requiringNew().call(() -> refs(needs.ofProject(projectId))));
  }

  /** The needs of {@code entityId} that are not met now, in the order written. */
  public List<NeedRef> unmet(String entityId) {
    return ofEntity(entityId).stream().filter(need -> !need.met()).toList();
  }

  private Map<String, List<NeedRef>> refs(List<WorkNeed> rows) {
    if (rows.isEmpty()) {
      return Map.of();
    }
    Map<String, Item> items = items(rows.stream().map(row -> row.neededEntityId).toList());
    Map<String, List<NeedRef>> answer = new LinkedHashMap<>();
    for (WorkNeed row : rows) {
      Item item = items.get(row.neededEntityId);
      if (item != null) {
        answer
            .computeIfAbsent(row.entityId, id -> new ArrayList<>())
            .add(
                NeedRef.of(
                    item.id(), row.status, item.title(), item.archetype(), item.status(),
                    item.number()));
      }
    }
    return answer;
  }

  /** The items {@code ids} names, keyed by id, in one projection query. */
  private Map<String, Item> items(Collection<String> ids) {
    Set<String> distinct = new LinkedHashSet<>(ids);
    distinct.remove(null);
    if (distinct.isEmpty()) {
      return Map.of();
    }
    List<Object[]> rows =
        entities
            .getEntityManager()
            .createQuery(
                "select e.id, e.projectId, e.archetype, e.status, e.title, e.number"
                    + " from WorkEntity e where e.id in ?1",
                Object[].class)
            .setParameter(1, distinct)
            .getResultList();
    Map<String, Item> answer = new HashMap<>();
    for (Object[] row : rows) {
      answer.put(
          (String) row[0],
          new Item(
              (String) row[0],
              (String) row[1],
              (Archetype) row[2],
              (String) row[3],
              (String) row[4],
              ((Number) row[5]).longValue()));
    }
    return answer;
  }

  // --- the write ------------------------------------------------------------------------------------

  /**
   * Every complaint about {@code stated} as the needs of an item of {@code projectId} — {@code
   * selfId}, or null for an item not created yet — in the sentences the doors answer, one per
   * violation, in entry order and then the set's: a duplicate, then a cycle. Empty means the set
   * may be written. {@code render} turns a per-project number into the qualified id a cycle is named
   * by. A read of its own.
   */
  public List<String> refusals(
      String projectId, String selfId, List<Stated> stated, LongFunction<String> render) {
    if (stated == null || stated.isEmpty()) {
      return List.of();
    }
    return reads.hold(
        "work needs check",
        () ->
            QuarkusTransaction.requiringNew()
                .call(() -> refusalsIn(projectId, selfId, stated, render)));
  }

  private List<String> refusalsIn(
      String projectId, String selfId, List<Stated> stated, LongFunction<String> render) {
    Map<String, Item> items = items(stated.stream().map(Stated::entityId).toList());
    List<String> refused = new ArrayList<>();
    Map<String, String> valid = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    Set<String> twice = new LinkedHashSet<>();
    for (int i = 0; i < stated.size(); i++) {
      Stated need = stated.get(i);
      String where = "needs[" + i + "]";
      Item item = need.entityId() == null ? null : items.get(need.entityId());
      boolean itemOk = false;
      if (item == null || !Objects.equals(item.projectId(), projectId)) {
        refused.add(where + ".item " + need.named() + " is not a work item of this project");
      } else if (!NEEDABLE.contains(item.archetype())) {
        refused.add(
            where
                + ".item "
                + need.named()
                + (item.archetype() == Archetype.ISSUE ? " is an " : " is a ")
                + item.archetype()
                + "; only an epic, a ticket, a feature or a task can be needed");
      } else if (item.id().equals(selfId)) {
        refused.add(where + ".item " + need.named() + " is this item itself");
      } else {
        itemOk = true;
        if (!seen.add(item.id())) {
          twice.add(need.named());
        }
      }
      boolean statusOk = NeedRule.isNeedable(need.status());
      if (!statusOk) {
        refused.add(
            where
                + ".status "
                + need.status()
                + " is not a status of the walk ("
                + String.join(", ", NeedRule.needable().subList(0, NeedRule.needable().size() - 1))
                + ") or DROPPED");
      }
      if (itemOk && statusOk) {
        valid.putIfAbsent(item.id(), need.status());
      }
    }
    for (String named : twice) {
      refused.add("needs names " + named + " twice");
    }
    if (selfId != null && !valid.isEmpty()) {
      cycle(projectId, selfId, valid.keySet(), render).ifPresent(refused::add);
    }
    return refused;
  }

  /**
   * The sentence naming a cycle the set would close, or empty: a walk from each needed item along
   * the project's stored needs (the item's own replaced by the stated set) that comes back to it.
   */
  private java.util.Optional<String> cycle(
      String projectId, String selfId, Collection<String> stated, LongFunction<String> render) {
    Map<String, List<String>> graph = new HashMap<>();
    for (WorkNeed row : needs.ofProject(projectId)) {
      if (!row.entityId.equals(selfId)) {
        graph.computeIfAbsent(row.entityId, id -> new ArrayList<>()).add(row.neededEntityId);
      }
    }
    graph.put(selfId, List.copyOf(stated));
    // Breadth first from the item itself, so the cycle named is a shortest one.
    Map<String, String> cameFrom = new HashMap<>();
    Deque<String> queue = new ArrayDeque<>();
    for (String next : graph.get(selfId)) {
      if (cameFrom.putIfAbsent(next, selfId) == null) {
        queue.add(next);
      }
    }
    while (!queue.isEmpty()) {
      String at = queue.poll();
      if (at.equals(selfId)) {
        List<String> path = new ArrayList<>();
        String step = cameFrom.get(selfId);
        path.add(selfId);
        while (!step.equals(selfId)) {
          path.add(0, step);
          step = cameFrom.get(step);
        }
        path.add(0, selfId);
        Map<String, Item> named = items(path);
        List<String> words = new ArrayList<>();
        for (String id : path) {
          Item item = named.get(id);
          words.add(item == null ? id : render.apply(item.number()));
        }
        return java.util.Optional.of("needs would close a cycle: " + String.join(" → ", words));
      }
      for (String next : graph.getOrDefault(at, List.of())) {
        if (!cameFrom.containsKey(next)) {
          cameFrom.put(next, at);
          queue.add(next);
        }
      }
    }
    return java.util.Optional.empty();
  }

  /**
   * Replaces what {@code entityId} needs by {@code stated}, whole — an empty list clears it. The
   * set is the one {@link #refusals} passed; each {@link Stated#entityId} is a UUID. A transaction
   * of its own, rows and nothing else, so a retry may run it again. A write that must also touch
   * the item's own row runs {@link WorkEntityService#replaceNeeds} instead (qits-1965).
   */
  public void replace(String entityId, List<Stated> stated) {
    writes.run("work needs write", () -> replaceIn(entityId, stated));
  }

  /**
   * The body of {@link #replace}, in the caller's transaction: database-only, so a retry of that
   * transaction may run it again. {@link WorkEntityService#replaceNeeds} runs it beside the row's
   * touch, so the needs and the touch commit together or not at all (qits-1965).
   */
  void replaceIn(String entityId, List<Stated> stated) {
    needs.clearOf(entityId);
    needs.getEntityManager().flush();
    Set<String> written = new HashSet<>();
    for (Stated need : stated == null ? List.<Stated>of() : stated) {
      if (written.add(need.entityId())) {
        WorkNeed row = new WorkNeed();
        row.entityId = entityId;
        row.neededEntityId = need.entityId();
        row.status = need.status();
        needs.persist(row);
      }
    }
  }

  // --- the flip -------------------------------------------------------------------------------------

  /**
   * Fires {@link NeedsMet} for every need on an item of {@code batch} that the move turned from
   * unmet to met: {@code NeedRule.reached(statusBefore, status)} false and {@code reached(status,
   * status)} true, one entry per such need. Called after the transition committed, never inside
   * it; the read is a transaction of its own and the event is observed after it commits. Never
   * throws: the move it describes is already answered for.
   */
  public void announceMet(List<TransitionedEntity> batch) {
    if (batch == null || batch.isEmpty()) {
      return;
    }
    Map<String, TransitionedEntity> moved = new LinkedHashMap<>();
    for (TransitionedEntity entity : batch) {
      if (entity != null
          && entity.statusBefore() != null
          && !entity.statusBefore().equals(entity.status())) {
        moved.put(entity.id(), entity);
      }
    }
    if (moved.isEmpty()) {
      return;
    }
    try {
      reads.hold(
          "work needs met",
          () ->
              QuarkusTransaction.requiringNew()
                  .call(
                      () -> {
                        List<NeedsMet.Met> flipped = flipsIn(moved);
                        if (!flipped.isEmpty()) {
                          met.fire(new NeedsMet(flipped));
                        }
                        return null;
                      }));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read which needs the move of %s met", moved.keySet());
    }
  }

  private List<NeedsMet.Met> flipsIn(Map<String, TransitionedEntity> moved) {
    List<WorkNeed> waiting = needs.onNeeded(moved.keySet());
    List<WorkNeed> turned = new ArrayList<>();
    for (WorkNeed row : waiting) {
      TransitionedEntity entity = moved.get(row.neededEntityId);
      if (!NeedRule.reached(entity.statusBefore(), row.status)
          && NeedRule.reached(entity.status(), row.status)) {
        turned.add(row);
      }
    }
    if (turned.isEmpty()) {
      return List.of();
    }
    Map<String, List<NeedRef>> all =
        refs(needs.ofEntities(turned.stream().map(r -> r.entityId).toList()));
    Map<String, Item> waiters = items(turned.stream().map(r -> r.entityId).toList());
    Instant now = Instant.now();
    List<NeedsMet.Met> answer = new ArrayList<>();
    for (WorkNeed row : turned) {
      TransitionedEntity entity = moved.get(row.neededEntityId);
      Item waiter = waiters.get(row.entityId);
      answer.add(
          new NeedsMet.Met(
              entity.projectId(),
              row.entityId,
              waiter == null ? 0 : waiter.number(),
              row.neededEntityId,
              entity.number(),
              row.status,
              entity.status(),
              NeedRef.unmet(all.getOrDefault(row.entityId, List.of())) == 0,
              now));
    }
    return answer;
  }
}
