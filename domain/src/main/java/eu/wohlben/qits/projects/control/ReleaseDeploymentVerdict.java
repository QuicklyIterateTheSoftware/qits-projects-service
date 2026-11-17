package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.DeploymentRequests.DeploymentRequestView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * <b>Whether a release's deployment is live, counting its variants</b> (qits-2296) — the one fold of
 * qits-deployments' release listing <em>with</em> variants ({@link
 * DeploymentRequests#forReleaseWithVariants}) onto LIVE, FAILED or PENDING. Finalization, the
 * reconcile and the deployment gate all read it, so they cannot disagree about which rows count.
 *
 * <p><b>The main row still decides on its own, and the variants are a second condition</b> — never
 * a swap of one row for another. qits-1976 kept variant rows out of the release listing because a
 * variant deploying after the main one would otherwise be the newest row and decide the verdict
 * (a live variant merging {@code main} while the main application failed). That reason stands: the
 * newest <b>main</b> row ({@code variant} null) is found first, and its {@code variants} — the names
 * the release declared, written when the main was queued — say which variant rows are owed.
 *
 * <ul>
 *   <li><b>LIVE</b>: the newest main row is {@code ACTIVE} and, for every declared name, the newest
 *       row of that variant is {@code ACTIVE}.
 *   <li><b>FAILED</b>: the main row failed ({@link ReleaseFinalization#failedDeployment}, today's
 *       rule), or a declared variant's newest row is {@code FAILED}, {@code ROLLED_BACK}, {@code
 *       IMAGE_MISSING} or {@code DECLARATION_REFUSED}.
 *   <li><b>PENDING</b>: anything else — notably a declared variant with no row yet, which is the
 *       ordinary state for 10–75 s after the main went ACTIVE: qits-deployments runs the main to
 *       completion and only then queues the variants.
 * </ul>
 *
 * <p><b>A variant row older than the newest main row is not this attempt's.</b> A DEPLOY rerun
 * re-posts the release, so a fresh main row is written while the variant's newest row is still the
 * failure the rerun was asked to fix; reading that as FAILED would fail the rerun's branches again
 * the moment its main went live. The listing is newest first, so a variant row that comes after
 * the newest main row predates it and is read as PENDING — the rerun's own variant row is on its
 * way.
 *
 * <p>A main row that declares no variants — a release without any, or an older qits-deployments
 * answering no {@code variants} field — is decided by the main row alone, exactly as before.
 *
 * @param state the release's deployment as a whole
 * @param main the newest main row, or null where the listing holds none
 * @param variants one entry per declared variant name, in the main row's order
 */
public record ReleaseDeploymentVerdict(State state, DeploymentRequestView main, List<Variant> variants) {

  /** The three answers. */
  public enum State {
    LIVE,
    FAILED,
    PENDING
  }

  /**
   * One declared variant and where it stands.
   *
   * @param name the variant's name as the release declares it ({@code private})
   * @param applicationName its application — the row's own, or {@code <main application>-<name>}
   *     where no row of this attempt exists yet
   * @param state LIVE on {@code ACTIVE}, FAILED on a failure word, PENDING otherwise
   * @param newest the variant's newest row of this attempt, or null where none exists yet
   */
  public record Variant(
      String name, String applicationName, State state, DeploymentRequestView newest) {

    /** The variant's status word, or null where it has no row yet. */
    public String status() {
      return newest == null ? null : newest.status();
    }
  }

  public ReleaseDeploymentVerdict {
    variants = variants == null ? List.of() : List.copyOf(variants);
  }

  /** qits-deployments' words for a variant that ended without going live (qits-2296). */
  static final Set<String> VARIANT_FAILED =
      Set.of("FAILED", "ROLLED_BACK", "IMAGE_MISSING", "DECLARATION_REFUSED");

  /** The verdict over a with-variants listing, newest first; null or empty is PENDING, no main. */
  public static ReleaseDeploymentVerdict of(List<DeploymentRequestView> rows) {
    List<DeploymentRequestView> listed = rows == null ? List.of() : rows;
    int mainAt = -1;
    for (int i = 0; i < listed.size(); i++) {
      if (!listed.get(i).isVariant()) {
        mainAt = i;
        break;
      }
    }
    if (mainAt < 0) {
      return new ReleaseDeploymentVerdict(State.PENDING, null, List.of());
    }
    DeploymentRequestView main = listed.get(mainAt);
    List<Variant> variants = new ArrayList<>();
    for (String name : new LinkedHashSet<>(main.variants())) {
      if (name == null || name.isBlank()) {
        continue;
      }
      DeploymentRequestView newest = null;
      // Only rows newer than the main row are this attempt's — see the class javadoc.
      for (int i = 0; i < mainAt; i++) {
        if (name.equals(listed.get(i).variant())) {
          newest = listed.get(i);
          break;
        }
      }
      String application =
          newest != null && newest.applicationName() != null
              ? newest.applicationName()
              : main.applicationName() == null ? name : main.applicationName() + "-" + name;
      variants.add(new Variant(name, application, variantState(newest), newest));
    }
    State mainState =
        ReleaseFinalization.LIVE_DEPLOYMENT.equals(main.status())
            ? State.LIVE
            : ReleaseFinalization.failedDeployment(main.status()) ? State.FAILED : State.PENDING;
    State state;
    if (mainState == State.FAILED
        || variants.stream().anyMatch(variant -> variant.state() == State.FAILED)) {
      state = State.FAILED;
    } else if (mainState == State.LIVE
        && variants.stream().allMatch(variant -> variant.state() == State.LIVE)) {
      state = State.LIVE;
    } else {
      state = State.PENDING;
    }
    return new ReleaseDeploymentVerdict(state, main, variants);
  }

  private static State variantState(DeploymentRequestView newest) {
    if (newest == null) {
      return State.PENDING;
    }
    if (ReleaseFinalization.LIVE_DEPLOYMENT.equals(newest.status())) {
      return State.LIVE;
    }
    return VARIANT_FAILED.contains(newest.status()) ? State.FAILED : State.PENDING;
  }

  /** Whether the newest main row declares any variant at all. */
  public boolean declaresVariants() {
    return !variants.isEmpty();
  }

  /**
   * The variants' part of the verdict alone, the main row taken as live: FAILED where one failed,
   * LIVE where every one is live (and where none is declared), PENDING otherwise. What a main
   * {@code DeploymentActive} asks — the event already says the main went live.
   */
  public State variantsState() {
    if (variants.stream().anyMatch(variant -> variant.state() == State.FAILED)) {
      return State.FAILED;
    }
    return variants.stream().allMatch(variant -> variant.state() == State.LIVE)
        ? State.LIVE
        : State.PENDING;
  }

  /** The first declared variant whose newest row failed, if any. */
  public Optional<Variant> failedVariant() {
    return variants.stream().filter(variant -> variant.state() == State.FAILED).findFirst();
  }

  /** The application names of the declared variants still pending. */
  public List<String> pendingVariants() {
    return variants.stream()
        .filter(variant -> variant.state() == State.PENDING)
        .map(Variant::applicationName)
        .toList();
  }

  /**
   * The status word a variant's failure is recorded under — {@code "<application> <status>"}, so
   * {@link eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge#deploymentFailure} writes {@code
   * qits-edge-private FAILED: <the deployer's first line>}.
   */
  public static String variantFailureWord(String applicationName, String status) {
    String word = status == null || status.isBlank() ? "FAILED" : status.trim();
    return applicationName == null || applicationName.isBlank()
        ? word
        : applicationName.trim() + " " + word;
  }
}
