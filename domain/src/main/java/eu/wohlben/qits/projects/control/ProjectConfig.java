package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Project;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/**
 * A project's own configuration, as its wrapper's {@code .config/qits/project.yml} declares it.
 *
 * <pre>
 *     supports_environments: false
 *     front_desk:
 *       lifecycle: ALWAYS_ON
 *       delivery:
 *         enabled: true
 *         max_running: 2
 *         start_refined: false
 *         approves: [manual-review]
 *         interval: PT10M
 * </pre>
 *
 * <p>One component per key — the shape {@link ReleaseRequestSettings} has for the repository-level
 * file beside it. {@code frontDeskLifecycle} is never null: absent means {@link
 * FrontDeskLifecycle#ON_DEMAND}; {@code delivery} is never null either: absent means {@link
 * DeliverySettings#OFF}.
 *
 * <p><b>{@link #DEFAULT} is what an absent file means, and it is not a degraded answer.</b> Every
 * project on this platform predates the file and every one of them is deployed once per
 * environment, so absent, an absent key and an explicit {@code true} are one answer on purpose:
 * <em>only</em> an explicit {@code false} changes anything. What is <b>not</b> this record is a file
 * that could not be read or parsed — that is an error {@link ProjectConfigParser} throws, never a
 * quiet {@link #DEFAULT}, because a declaration that fails open on a typo re-routes a project
 * silently.
 */
public record ProjectConfig(
    boolean supportsEnvironments,
    FrontDeskLifecycle frontDeskLifecycle,
    DeliverySettings delivery) {

  public ProjectConfig {
    if (frontDeskLifecycle == null) {
      frontDeskLifecycle = FrontDeskLifecycle.ON_DEMAND;
    }
    if (delivery == null) {
      delivery = DeliverySettings.OFF;
    }
  }

  /** The two keys that predate {@code front_desk.delivery}; the delivery loop is off. */
  public ProjectConfig(boolean supportsEnvironments, FrontDeskLifecycle frontDeskLifecycle) {
    this(supportsEnvironments, frontDeskLifecycle, DeliverySettings.OFF);
  }

  /**
   * No file, no key, or {@code true}: the project is deployed once per environment, its front
   * desk is {@link FrontDeskLifecycle#ON_DEMAND} and its delivery loop is off.
   */
  public static final ProjectConfig DEFAULT = new ProjectConfig(true, FrontDeskLifecycle.ON_DEMAND);

  /** The one thing an explicit {@code false} says. */
  public static final ProjectConfig SINGLE_ENVIRONMENT =
      new ProjectConfig(false, FrontDeskLifecycle.ON_DEMAND);

  /**
   * The front desk's delivery loop (epic qits-1440), {@code front_desk.delivery} in {@code
   * project.yml}: whether the desk runs it, how many items it keeps running at once, whether it may
   * start a REFINED item itself, which approval requirements it may satisfy, and how often it ticks.
   *
   * <p>{@code approves} is a subset of {@link #APPROVABLE} — never {@code no-changes} or {@code
   * recorded}, which the desk may never satisfy. The parser refuses anything else, so a record
   * holding one was built by hand. Three of its names are requirement ids ({@code manual-review},
   * {@code artifact-declarations} and, from qits-1560, {@link #CONFIG_DIRECTORY} — the fold changes
   * {@code .config/qits/}, never the fail-closed readings that reuse its id); the other two name a
   * family (qits-1517): {@link #AUTOMATION} admits the desk to the {@code automation:<kind>}
   * requirements, and {@link #CONTRACT} to the {@code contract:<slug>} requirements whose consumer
   * is the request's own project. {@code ApprovalPolicy.admitDesk} is where the names are read.
   *
   * @param enabled whether the loop runs at all; absent is {@code false}
   * @param maxRunning how many of the project's items the desk keeps running, {@link
   *     #MIN_RUNNING}..{@link #MAX_RUNNING}; absent is {@link #DEFAULT_MAX_RUNNING}
   * @param startRefined whether the desk may move a REFINED item to READY_FOR_DEV itself
   * @param approves the approval requirements the desk may satisfy, never null
   * @param interval how often the loop ticks, {@link #MIN_INTERVAL}..{@link #MAX_INTERVAL}; absent
   *     is {@link #DEFAULT_INTERVAL}
   */
  public record DeliverySettings(
      boolean enabled,
      int maxRunning,
      boolean startRefined,
      Set<String> approves,
      Duration interval) {

    /** The fewest running items a loop may be capped at. */
    public static final int MIN_RUNNING = 1;

    /** The most running items a loop may be capped at. */
    public static final int MAX_RUNNING = 20;

    /** {@code max_running} when absent. */
    public static final int DEFAULT_MAX_RUNNING = 2;

    /** The shortest tick. */
    public static final Duration MIN_INTERVAL = Duration.ofMinutes(2);

    /** The longest tick. */
    public static final Duration MAX_INTERVAL = Duration.ofHours(1);

    /** {@code interval} when absent. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(10);

    /** {@code approves} naming every {@code automation:<kind>} requirement (qits-1517). */
    public static final String AUTOMATION = "automation";

    /**
     * {@code approves} naming every {@code contract:<slug>} requirement whose consumer is the
     * request's own project (qits-1517).
     */
    public static final String CONTRACT = "contract";

    /**
     * {@code approves} naming the {@code config-directory} requirement a fold changing {@code
     * .config/qits/} carries (qits-1560): the desk judges whether the change is what the request's
     * work should make. The fail-closed readings that reuse the id (an unreadable fold, one not
     * read yet) stay a person's whatever this holds.
     */
    public static final String CONFIG_DIRECTORY = "config-directory";

    /**
     * The names {@code approves} may hold: the three requirement ids the delivery desk may satisfy
     * ({@link #CONFIG_DIRECTORY} from qits-1560), and the two families ({@link #AUTOMATION}, {@link
     * #CONTRACT}) it may be admitted to. {@code no-changes} and {@code recorded} are never among
     * them.
     */
    public static final Set<String> APPROVABLE =
        Set.of("manual-review", "artifact-declarations", CONFIG_DIRECTORY, AUTOMATION, CONTRACT);

    /** No {@code front_desk.delivery}: everything off, every value at its default. */
    public static final DeliverySettings OFF =
        new DeliverySettings(false, DEFAULT_MAX_RUNNING, false, Set.of(), DEFAULT_INTERVAL);

    public DeliverySettings {
      approves = approves == null ? Set.of() : Set.copyOf(approves);
      if (interval == null) {
        interval = DEFAULT_INTERVAL;
      }
    }

    /**
     * The settings {@code project}'s {@code delivery_*} columns hold. A stored interval that does
     * not parse — no writer here stores one — reads as {@link #DEFAULT_INTERVAL}.
     */
    public static DeliverySettings of(Project project) {
      Duration interval;
      try {
        interval =
            project.deliveryInterval == null
                ? DEFAULT_INTERVAL
                : Duration.parse(project.deliveryInterval);
      } catch (DateTimeParseException e) {
        interval = DEFAULT_INTERVAL;
      }
      return new DeliverySettings(
          project.deliveryEnabled,
          project.deliveryMaxRunning,
          project.deliveryStartRefined,
          approvesOf(project.deliveryApproves),
          interval);
    }

    /** Write these settings onto {@code project}'s {@code delivery_*} columns. */
    public void applyTo(Project project) {
      project.deliveryEnabled = enabled;
      project.deliveryMaxRunning = maxRunning;
      project.deliveryStartRefined = startRefined;
      project.deliveryApproves = approvesColumn();
      project.deliveryInterval = interval.toString();
    }

    /** {@link #approves} as the {@code delivery_approves} column holds it: sorted, space-separated. */
    public String approvesColumn() {
      return String.join(" ", new TreeSet<>(approves));
    }

    /** The inverse of {@link #approvesColumn()}; null or blank is the empty set. */
    public static Set<String> approvesOf(String column) {
      if (column == null || column.isBlank()) {
        return Set.of();
      }
      return Set.copyOf(Arrays.asList(column.trim().split("\\s+")));
    }
  }
}
