package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.ProjectConfig.DeliverySettings;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads {@code .config/qits/project.yml} — the wrapper's declaration <em>about the project</em>,
 * as opposed to {@link QitsConfigParser}'s {@code repository.yml}, which is about one repository.
 *
 * <p>Shaped exactly after {@link ReleaseRequestSettingsParser}: a pure, framework-light helper that
 * is unit-testable without a clone, over SnakeYAML's {@link SafeConstructor} so repository content
 * can never instantiate a class, and with no {@code readConfig} arm of its own — <b>the caller does
 * the reading</b> and this class only ever sees bytes. {@link ReleaseGates} is the existing example
 * of that calling pattern; here the caller is {@code WrapperReconcileService}, which already has the
 * wrapper's mirror open to read {@code .gitmodules} out of.
 *
 * <p><b>It never fails open.</b> Bad YAML, a document that is not a mapping, a {@link
 * #SUPPORTS_ENVIRONMENTS} that is not a boolean, a {@link #FRONT_DESK} that is not a mapping and a
 * {@code front_desk.lifecycle} that is neither {@code ALWAYS_ON} nor {@code ON_DEMAND}
 * (case-sensitive) all throw {@link ProjectConfigException}, naming {@link #CONFIG_PATH}. An absent
 * {@code front_desk} or an absent {@code lifecycle} is {@link FrontDeskLifecycle#ON_DEMAND}. The
 * same holds for {@code front_desk.delivery} (epic qits-1440): absent is {@link
 * DeliverySettings#OFF}, an absent key inside it is that key's default, and a value of the wrong
 * type, out of its range or (for {@code approves}) outside {@link DeliverySettings#APPROVABLE}
 * throws. Absence of the file, absence of the key and an explicit {@code true} are one
 * answer — {@link ProjectConfig#DEFAULT}, the project supports environments — and only an explicit
 * {@code false} changes anything. Degrading a malformed declaration to either value would let a typo
 * re-route a project's deployments silently, which is the one outcome this seam exists to prevent;
 * absence is decided by the caller's listing rather than here.
 *
 * <p><b>An unknown key is ignored</b>, so a reader that predates a key added later still answers,
 * and a file written for a newer platform does not brick an older one.
 */
@ApplicationScoped
public class ProjectConfigParser {

  /** The committed location, in the project's wrapper. There is no legacy path: the file is new. */
  public static final String CONFIG_PATH = ".config/qits/project.yml";

  /**
   * The environment key. Spelled with an underscore because that is how the platform's project-level
   * declaration was specified; {@code release-requests.yml} beside it uses a hyphen, and neither
   * file reads the other's spelling.
   */
  public static final String SUPPORTS_ENVIRONMENTS = "supports_environments";

  /** The nested map that declares the project's front desk (qits-767). */
  public static final String FRONT_DESK = "front_desk";

  /** The key under {@link #FRONT_DESK}: {@code ALWAYS_ON} or {@code ON_DEMAND}. */
  public static final String LIFECYCLE = "lifecycle";

  /** The nested map under {@link #FRONT_DESK} that declares the delivery loop (epic qits-1440). */
  public static final String DELIVERY = "delivery";

  /** Under {@link #DELIVERY}: a boolean, default {@code false}. */
  public static final String DELIVERY_ENABLED = "enabled";

  /** Under {@link #DELIVERY}: an integer 1..20, default 2. */
  public static final String DELIVERY_MAX_RUNNING = "max_running";

  /** Under {@link #DELIVERY}: a boolean, default {@code false}. */
  public static final String DELIVERY_START_REFINED = "start_refined";

  /** Under {@link #DELIVERY}: a list, a subset of {@link DeliverySettings#APPROVABLE}. */
  public static final String DELIVERY_APPROVES = "approves";

  /** Under {@link #DELIVERY}: an ISO-8601 duration from PT2M to PT1H, default PT10M. */
  public static final String DELIVERY_INTERVAL = "interval";

  /** A structural problem in {@link #CONFIG_PATH}. It names the file; see the class javadoc. */
  public static class ProjectConfigException extends RuntimeException {
    public ProjectConfigException(String message) {
      super(message);
    }

    public ProjectConfigException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * The configuration the given file content declares.
   *
   * <p>An empty or whitespace-only file, and one whose document is empty ({@code ---} alone, a file
   * of comments), are {@link ProjectConfig#DEFAULT}: the file exists and declares nothing, which is
   * the same answer as declaring {@code true}. Everything else that will not read as {@code
   * supports_environments: <boolean>} and {@code front_desk: {lifecycle: ALWAYS_ON|ON_DEMAND}} throws.
   */
  public ProjectConfig parse(String content) {
    if (content == null || content.isBlank()) {
      return ProjectConfig.DEFAULT;
    }
    Object root;
    try {
      root = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
    } catch (Exception e) {
      throw new ProjectConfigException("Invalid YAML in " + CONFIG_PATH + ": " + e.getMessage(), e);
    }
    if (root == null) {
      return ProjectConfig.DEFAULT;
    }
    if (!(root instanceof Map<?, ?> raw)) {
      throw new ProjectConfigException(
          "Expected a mapping at the root of "
              + CONFIG_PATH
              + ", got: "
              + root.getClass().getSimpleName());
    }
    Map<String, Object> map = new LinkedHashMap<>();
    raw.forEach((k, v) -> map.put(String.valueOf(k), v));

    return new ProjectConfig(
        supportsEnvironmentsOf(map), frontDeskLifecycleOf(map), deliveryOf(map));
  }

  private static boolean supportsEnvironmentsOf(Map<String, Object> map) {
    Object supportsEnvironments = map.get(SUPPORTS_ENVIRONMENTS);
    if (supportsEnvironments == null) {
      // Absent key: the same answer as `true`, and deliberately not an error. A file that carries
      // only a key this reader does not know yet is exactly this case.
      return true;
    }
    if (!(supportsEnvironments instanceof Boolean flag)) {
      throw new ProjectConfigException(
          "Expected a boolean at '"
              + SUPPORTS_ENVIRONMENTS
              + "' in "
              + CONFIG_PATH
              + ", got: "
              + supportsEnvironments.getClass().getSimpleName());
    }
    return flag;
  }

  private static FrontDeskLifecycle frontDeskLifecycleOf(Map<String, Object> map) {
    Object frontDesk = map.get(FRONT_DESK);
    if (frontDesk == null) {
      return FrontDeskLifecycle.ON_DEMAND;
    }
    if (!(frontDesk instanceof Map<?, ?> desk)) {
      throw new ProjectConfigException(
          "Expected a mapping at '"
              + FRONT_DESK
              + "' in "
              + CONFIG_PATH
              + ", got: "
              + frontDesk.getClass().getSimpleName());
    }
    Object lifecycle = desk.get(LIFECYCLE);
    if (lifecycle == null) {
      return FrontDeskLifecycle.ON_DEMAND;
    }
    for (FrontDeskLifecycle value : FrontDeskLifecycle.values()) {
      if (value.name().equals(lifecycle)) {
        return value;
      }
    }
    throw new ProjectConfigException(
        "Expected ALWAYS_ON or ON_DEMAND at '"
            + FRONT_DESK
            + "."
            + LIFECYCLE
            + "' in "
            + CONFIG_PATH
            + ", got: "
            + lifecycle);
  }

  private static DeliverySettings deliveryOf(Map<String, Object> map) {
    Object frontDesk = map.get(FRONT_DESK);
    if (!(frontDesk instanceof Map<?, ?> desk)) {
      // Absent is OFF; a front_desk that is not a mapping has already thrown in the lifecycle read.
      return DeliverySettings.OFF;
    }
    Object delivery = desk.get(DELIVERY);
    if (delivery == null) {
      return DeliverySettings.OFF;
    }
    if (!(delivery instanceof Map<?, ?> loop)) {
      throw new ProjectConfigException(
          "Expected a mapping at '"
              + FRONT_DESK
              + "."
              + DELIVERY
              + "' in "
              + CONFIG_PATH
              + ", got: "
              + delivery.getClass().getSimpleName());
    }
    return new DeliverySettings(
        booleanAt(loop, DELIVERY_ENABLED, false),
        maxRunningOf(loop.get(DELIVERY_MAX_RUNNING)),
        booleanAt(loop, DELIVERY_START_REFINED, false),
        approvesOf(loop.get(DELIVERY_APPROVES)),
        intervalOf(loop.get(DELIVERY_INTERVAL)));
  }

  private static boolean booleanAt(Map<?, ?> loop, String key, boolean absent) {
    Object value = loop.get(key);
    if (value == null) {
      return absent;
    }
    if (!(value instanceof Boolean flag)) {
      throw invalid(key, "a boolean", value);
    }
    return flag;
  }

  private static int maxRunningOf(Object value) {
    if (value == null) {
      return DeliverySettings.DEFAULT_MAX_RUNNING;
    }
    if (!(value instanceof Integer count)
        || count < DeliverySettings.MIN_RUNNING
        || count > DeliverySettings.MAX_RUNNING) {
      throw invalid(
          DELIVERY_MAX_RUNNING,
          "an integer from "
              + DeliverySettings.MIN_RUNNING
              + " to "
              + DeliverySettings.MAX_RUNNING,
          value);
    }
    return count;
  }

  private static Set<String> approvesOf(Object value) {
    if (value == null) {
      return Set.of();
    }
    String expected = "a list of " + String.join(" and ", sortedApprovable());
    if (!(value instanceof List<?> entries)) {
      throw invalid(DELIVERY_APPROVES, expected, value);
    }
    Set<String> approves = new LinkedHashSet<>();
    for (Object entry : entries) {
      if (!(entry instanceof String name) || !DeliverySettings.APPROVABLE.contains(name)) {
        throw invalid(DELIVERY_APPROVES, expected, entry);
      }
      approves.add(name);
    }
    return approves;
  }

  private static List<String> sortedApprovable() {
    return DeliverySettings.APPROVABLE.stream().sorted().toList();
  }

  private static Duration intervalOf(Object value) {
    if (value == null) {
      return DeliverySettings.DEFAULT_INTERVAL;
    }
    String expected =
        "an ISO-8601 duration from "
            + DeliverySettings.MIN_INTERVAL
            + " to "
            + DeliverySettings.MAX_INTERVAL;
    if (!(value instanceof String text)) {
      throw invalid(DELIVERY_INTERVAL, expected, value);
    }
    Duration interval;
    try {
      interval = Duration.parse(text);
    } catch (DateTimeParseException e) {
      throw invalid(DELIVERY_INTERVAL, expected, value);
    }
    if (interval.compareTo(DeliverySettings.MIN_INTERVAL) < 0
        || interval.compareTo(DeliverySettings.MAX_INTERVAL) > 0) {
      throw invalid(DELIVERY_INTERVAL, expected, value);
    }
    return interval;
  }

  private static ProjectConfigException invalid(String key, String expected, Object got) {
    return new ProjectConfigException(
        "Expected "
            + expected
            + " at '"
            + FRONT_DESK
            + "."
            + DELIVERY
            + "."
            + key
            + "' in "
            + CONFIG_PATH
            + ", got: "
            + got);
  }
}
