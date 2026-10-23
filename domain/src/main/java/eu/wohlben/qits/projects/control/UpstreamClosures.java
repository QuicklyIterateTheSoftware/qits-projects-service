package eu.wohlben.qits.projects.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>What is downstream of each repository, remembered for a while</b> (epic qits-897, gate 3). A
 * per-repository cache of {@link DownstreamComponents#downstreamOf} answers: the downstream
 * repository names, or "could not ask".
 *
 * <p><b>Why a cache.</b> Gate 3 ({@code UpstreamInFlightGate}) asks "is any open request upstream of
 * this one still building?", and R's upstreams are the repositories whose downstream list names R.
 * Asking qits-maintenance per candidate on every evaluation would be a call per open request per
 * sweep, and a list read must never make one at all. The closure moves only when a pin or an SBOM
 * edge does, so five minutes ({@code qits.projects.release-requests.upstream-closure-ttl-ms}) is
 * fresh enough for a gate that postpones and never protects content.
 *
 * <p><b>One call serves two readers.</b> {@code ReleaseRequests.downstreamOf} asks on every
 * announcement anyway (the closure rides on {@code ReleaseRequestChanged}) and {@link #record}s
 * each answer here, so a repository that announced lately is answered without a second call.
 *
 * <p><b>Who may ask.</b> {@link #closureOf} with {@code mayAsk=true} is for deciding callers ({@code
 * ReleaseRequests.evaluate}): on a miss, an expired entry or a stale "could not ask", it asks. A
 * display caller passes {@code mayAsk=false} and reads what is here — a miss is "could not ask" —
 * so a page never calls qits-maintenance.
 *
 * <p><b>A "could not ask" is remembered too, but briefly</b> ({@link #RETRY_AFTER}): a deciding
 * caller re-asks it only after that, so an outage of qits-maintenance costs one bounded call per
 * repository per half minute rather than one per evaluation.
 */
@ApplicationScoped
public class UpstreamClosures {

  private static final Logger LOG = Logger.getLogger(UpstreamClosures.class);

  /** How long a "could not ask" stands before a deciding caller asks again. */
  static final Duration RETRY_AFTER = Duration.ofSeconds(30);

  @Inject Instance<DownstreamComponents> downstreamComponents;

  @ConfigProperty(
      name = "qits.projects.release-requests.upstream-closure-ttl-ms",
      defaultValue = "300000")
  long ttlMs;

  /** One answer and when it was learned. {@code names} empty is "could not ask". */
  private record Entry(Optional<List<String>> names, Instant at) {}

  private final Map<String, Entry> byRepo = new ConcurrentHashMap<>();

  /**
   * Remember one answer — what the announcement path just learned. {@code null} names is "could not
   * ask", as is an empty optional.
   */
  public void record(String repoId, List<String> names) {
    if (repoId == null) {
      return;
    }
    byRepo.put(
        repoId,
        new Entry(
            names == null ? Optional.empty() : Optional.of(List.copyOf(names)), Instant.now()));
  }

  /**
   * The downstream closure of one repository: its names, nearest first, or empty where it could not
   * be read (or, with {@code mayAsk=false}, was not on record).
   *
   * @param mayAsk whether this caller may ask qits-maintenance on a miss — true on a deciding path
   *     only, never on a display path
   */
  public Optional<List<String>> closureOf(String repoId, String repoName, boolean mayAsk) {
    if (repoId == null) {
      return Optional.empty();
    }
    Instant now = Instant.now();
    Entry entry = byRepo.get(repoId);
    if (entry != null && fresh(entry, now)) {
      return entry.names();
    }
    if (!mayAsk) {
      // An expired answer is still the best a page has: the closure moves rarely, and showing it is
      // better than UNKNOWN. A deciding caller is the one that refreshes it.
      return entry == null ? Optional.empty() : entry.names();
    }
    Optional<List<String>> asked = ask(repoId, repoName);
    byRepo.put(repoId, new Entry(asked, now));
    return asked;
  }

  /** What is on record, never asking — the re-evaluation triggers' read. */
  public Optional<List<String>> cached(String repoId) {
    return closureOf(repoId, null, false);
  }

  /** Forget everything — the suite's seam. */
  public void clear() {
    byRepo.clear();
  }

  private boolean fresh(Entry entry, Instant now) {
    Duration age = Duration.between(entry.at(), now);
    return entry.names().isPresent()
        ? age.toMillis() < ttlMs
        : age.compareTo(RETRY_AFTER) < 0;
  }

  private Optional<List<String>> ask(String repoId, String repoName) {
    if (!downstreamComponents.isResolvable()) {
      return Optional.empty();
    }
    try {
      Optional<List<String>> answer = downstreamComponents.get().downstreamOf(repoId, repoName);
      return answer == null ? Optional.empty() : answer.map(List::copyOf);
    } catch (RuntimeException e) {
      // The port must not throw; a throw is a port bug and costs the answer, never the caller.
      LOG.warnf(e, "Could not read what is downstream of %s", repoId);
      return Optional.empty();
    }
  }
}
