package eu.wohlben.qits.projects.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.contracts.ClientUsage;
import eu.wohlben.qits.containers.client.contracts.Feature;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * <b>The guard: every qits-containers door this service's code uses has a {@link
 * ContainersContract} row</b> (qits-1163), read off the compiled classes with the
 * qits-containers-client-contracts kit's own {@link ClientUsage#scan}.
 *
 * <p>The kit's scan maps each {@code ContainersClient} method this module calls to a {@link
 * Feature}; each row maps to the feature its operation (and, for a delete, its {@code volumes}
 * query) asks for. What a class file cannot show is named by hand in {@link #HAND_NAMED}, with the
 * call that proves it; what the code uses but the provider has not recorded is named in {@link
 * #KNOWN_GAPS}, with the reason, so a gap is a visible decision rather than a silent one.
 *
 * <p>It fails when a used feature has no row, when a row stands for a feature the code does not use,
 * when the scan meets a client method the kit cannot place, and when a hand-named feature or a known
 * gap no longer holds.
 */
class ContainersClientUsageTest {

  /** The calling classes of the module, as surefire runs in {@code service/}. */
  private static final Path CLASSES = Path.of("target/classes");

  private static final String LEGACY_AGENT_PLACES = "eu.wohlben.qits.projects.deskhost.LegacyAgentPlaces";

  /**
   * Features the scan cannot see, each with the class that uses it and why. {@code
   * LegacyAgentPlaces.run} deletes the legacy agent place with {@code delete(..., true, false)}: the
   * scan sees the {@code delete} method, never the {@code volumes=true} argument.
   */
  static final Map<Feature, String> HAND_NAMED =
      Map.of(Feature.DELETE_WITH_VOLUMES, LEGACY_AGENT_PLACES);

  /**
   * Features the code uses that no row covers, with the reason. Empty: qits-containers records
   * every door this service uses, the {@code volumes=true} delete at {@code a running container with
   * its volumes} included.
   */
  static final Map<Feature, String> KNOWN_GAPS = Map.of();

  private static ClientUsage usage;

  @BeforeAll
  static void scan() {
    usage = ClientUsage.scan(List.of(CLASSES));
  }

  @Test
  void theScanPlacesEveryClientCall() {
    Set<String> unknown = new TreeSet<>(usage.unknown());
    // Constructing the client is not a call to qits-containers (ContainersClientProducer).
    unknown.removeIf(call -> call.startsWith("<init>"));
    assertTrue(
        unknown.isEmpty(),
        "ContainersClient calls the kit maps to no feature (a client newer than the kit?): "
            + unknown);
  }

  @Test
  void everyUsedFeatureHasARowAndEveryRowAUsedFeature() {
    Set<Feature> used = EnumSet.noneOf(Feature.class);
    used.addAll(usage.features());
    used.addAll(HAND_NAMED.keySet());
    Set<Feature> rowed = EnumSet.noneOf(Feature.class);
    for (ConsumerRow row : ContainersContract.ROWS) {
      rowed.add(featureOf(row));
    }

    Set<Feature> missing = EnumSet.copyOf(used);
    missing.removeAll(rowed);
    missing.removeAll(KNOWN_GAPS.keySet());
    assertTrue(
        missing.isEmpty(),
        "qits-containers doors this service uses with no ContainersContract row (add the row, or"
            + " name it in KNOWN_GAPS with the reason): "
            + missing
            + "\n  scanned: "
            + usage.calls());

    Set<Feature> stale = EnumSet.noneOf(Feature.class);
    stale.addAll(rowed);
    stale.removeAll(used);
    assertTrue(stale.isEmpty(), "ContainersContract rows for doors the code no longer uses: " + stale);
  }

  @Test
  void handNamedFeaturesAndKnownGapsStillHold() {
    for (Map.Entry<Feature, String> named : HAND_NAMED.entrySet()) {
      Feature base = named.getKey() == Feature.DELETE_WITH_VOLUMES ? Feature.DELETE : named.getKey();
      boolean called =
          usage.calls().entrySet().stream()
              .anyMatch(
                  e ->
                      Feature.forCall(
                                  e.getKey().substring(0, e.getKey().indexOf('(')),
                                  e.getKey().substring(e.getKey().indexOf('(')))
                              .map(base::equals)
                              .orElse(false)
                          && e.getValue().contains(named.getValue()));
      assertTrue(
          called,
          named.getKey() + " is named by hand for " + named.getValue() + ", which no longer calls "
              + base + ": " + usage.calls());
    }
    Map<Feature, String> rowed = new EnumMap<>(Feature.class);
    ContainersContract.ROWS.forEach(row -> rowed.put(featureOf(row), row.description()));
    for (Feature gap : KNOWN_GAPS.keySet()) {
      assertTrue(
          !rowed.containsKey(gap),
          gap + " is a known gap, but " + rowed.get(gap) + " covers it: drop the gap");
    }
  }

  @Test
  void theRowsCarryTheKitsProvider() {
    ContainersContract.ROWS.forEach(
        row -> assertEquals(ContainersContract.PROVIDER, row.provider(), row.description()));
  }

  /** The feature a row asks for: its operation, and for a container delete its volumes flag. */
  static Feature featureOf(ConsumerRow row) {
    return switch (row.operationId()) {
      case "ensureContainer" -> Feature.ENSURE;
      case "getContainer" -> Feature.STATUS;
      case "listWorkloadContainers" -> Feature.LIST_WORKLOAD;
      case "listOwnerContainers" -> Feature.LIST_OWNER;
      case "getContainerLogs" -> Feature.LOGS;
      case "stopContainer" -> Feature.STOP;
      case "touchContainer" -> Feature.TOUCH;
      case "deleteContainer" ->
          "true".equals(row.query().get("volumes")) ? Feature.DELETE_WITH_VOLUMES : Feature.DELETE;
      case "destroyWorkloadContainers" -> Feature.DESTROY_ALL;
      case "ensureVolume" -> Feature.VOLUME_ENSURE;
      case "getVolume" -> Feature.VOLUME_GET;
      case "deleteVolume" -> Feature.VOLUME_DELETE;
      default ->
          throw new IllegalStateException(
              row.description() + ": no qits-containers feature for " + row.operationId());
    };
  }
}
