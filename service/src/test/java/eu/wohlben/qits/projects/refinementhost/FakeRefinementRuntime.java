package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.entity.Refinement;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The suite's {@link RefinementRuntime}: an in-memory place table and a verb log — no docker, no
 * orchestrator, no network. It wins over {@code containershost/ContainersRefinementRuntime} for
 * free, because that bean is {@code @DefaultBean}: the same arrangement
 * {@code agenthost/FakeContainerRuntime} has.
 */
@ApplicationScoped
public class FakeRefinementRuntime implements RefinementRuntime {

  private final Map<Long, ContainerInfo> places = new LinkedHashMap<>();

  /** Every verb, in order: {@code provision:<id>}, {@code wake:<id>}, {@code delete:<id>}, … */
  private final List<String> calls = new CopyOnWriteArrayList<>();

  /**
   * A METHOD, not a public field, and that is load-bearing: the suite reaches this bean through a
   * client proxy, and a field read on a proxy answers the proxy's own (empty) field rather than the
   * contextual instance's.
   */
  public List<String> calls() {
    return calls;
  }

  /** The qualified entity id the last provision or wake of each refinement was handed (qits-614). */
  private final Map<Long, String> qualifiedEntityIds = new LinkedHashMap<>();

  /** What the last bring-up of {@code refinementId} was told the entity is called, or null. */
  public synchronized String qualifiedEntityIdOf(long refinementId) {
    return qualifiedEntityIds.get(refinementId);
  }

  /** The entity the last provision or wake of each refinement was handed (qits-614, qits-617). */
  private final Map<Long, RefinedEntity> entities = new LinkedHashMap<>();

  /** Whether the last bring-up of {@code refinementId} was told its entity is blocked, or null. */
  public synchronized Boolean entityBlockedOf(long refinementId) {
    RefinedEntity entity = entities.get(refinementId);
    return entity == null ? null : entity.blocked();
  }

  /** The whole entity the last bring-up of {@code refinementId} was handed, or null. */
  public synchronized RefinedEntity entityOf(long refinementId) {
    return entities.get(refinementId);
  }

  @Override
  public synchronized Optional<ContainerInfo> inspect(long refinementId) {
    return Optional.ofNullable(places.get(refinementId));
  }

  @Override
  public synchronized void provision(
      Refinement refinement,
      String projectSlug,
      String slug,
      String wrapperName,
      RefinedEntity entity) {
    calls.add("provision:" + refinement.id);
    qualifiedEntityIds.put(refinement.id, entity.qualifiedId());
    entities.put(refinement.id, entity);
    places.put(
        refinement.id, new ContainerInfo("qits-ref-" + projectSlug + "-" + slug, true));
  }

  @Override
  public synchronized void wake(
      Refinement refinement,
      String projectSlug,
      String slug,
      String wrapperName,
      RefinedEntity entity) {
    calls.add("wake:" + refinement.id);
    qualifiedEntityIds.put(refinement.id, entity.qualifiedId());
    entities.put(refinement.id, entity);
    places.put(
        refinement.id, new ContainerInfo("qits-ref-" + projectSlug + "-" + slug, true));
  }

  /** Test hook: the next {@link #stop} call against this refinement throws instead of pausing it. */
  private final Set<Long> throwOnStop = ConcurrentHashMap.newKeySet();

  public void throwOnNextStop(long refinementId) {
    throwOnStop.add(refinementId);
  }

  @Override
  public synchronized void stop(long refinementId) {
    calls.add("stop:" + refinementId);
    if (throwOnStop.remove(refinementId)) {
      throw new RuntimeException("fake stop failure for refinement " + refinementId);
    }
    ContainerInfo existing = places.get(refinementId);
    if (existing != null) {
      places.put(refinementId, new ContainerInfo(existing.containerName(), false));
    }
  }

  @Override
  public synchronized void touch(long refinementId) {
    calls.add("touch:" + refinementId);
  }

  @Override
  public synchronized void delete(long refinementId) {
    calls.add("delete:" + refinementId);
    places.remove(refinementId);
  }

  @Override
  public synchronized List<ContainerInfo> listRefinementContainers() {
    return new ArrayList<>(places.values());
  }

  /** Test seam: put a place in a given state without going through a verb. */
  public synchronized void place(long refinementId, String name, boolean running) {
    places.put(refinementId, new ContainerInfo(name, running));
  }

  /** Forget the recorded verbs only, keeping every place as it stands. */
  public void clearCalls() {
    calls.clear();
  }

  public synchronized void reset() {
    throwOnStop.clear();
    places.clear();
    calls.clear();
    qualifiedEntityIds.clear();
    entities.clear();
  }
}
