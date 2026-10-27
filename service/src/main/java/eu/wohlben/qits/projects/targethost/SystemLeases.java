package eu.wohlben.qits.projects.targethost;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * qits-system's lease, work-volume and target doors, as the front desks use them (epic qits-1481,
 * qits-1551): a DESK lease per desk — {@code slotType DESK}, {@code ownerRef <projectId>}, {@code
 * workKey desk-<projectId>} — asked for, rolled, read, listed and ended, its work volume purged with
 * the desk, and the targets listed for a legacy desk's pin. {@link HttpSystemLeases} is the shipped
 * implementation; a suite stands in for it.
 *
 * <p>Nothing here is called inside a transaction: every method is an HTTP call.
 */
public interface SystemLeases {

  /** The slot type every lease of this service is. */
  String SLOT_TYPE = "DESK";

  /** The owner qits-system names this service's leases by. */
  String OWNER = "qits-projects";

  /** How long a desk's removal waits for its lease to finish ending before it purges the volume. */
  Duration ENDING_DEADLINE = Duration.ofSeconds(60);

  /** {@code desk-<projectId>}: the work key, and so the name of the desk's work volume on a target. */
  static String workKey(String projectId) {
    return "desk-" + projectId;
  }

  /** A lease's life, as qits-system spells it. {@link #UNKNOWN} for a word this build does not know. */
  enum LeaseState {
    QUEUED,
    GRANTED,
    RUNNING,
    EXITED,
    FAILED,
    ENDING,
    ENDED,
    UNKNOWN;

    public static LeaseState parse(String value) {
      if (value == null) {
        return UNKNOWN;
      }
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException unknown) {
        return UNKNOWN;
      }
    }

    /** Every state but ENDED holds the owner ref: qits-system refuses a second lease for it. */
    public boolean live() {
      return this != ENDED;
    }
  }

  /** {@code LeaseSpec.limits}; an unset limit is left out. */
  record Limits(String memory, String memorySwap, Long pidsLimit, String cpus, Integer oomScoreAdj) {}

  /** {@code LeaseSpec{image, env, user, limits}}. The environment carries the lease's token. */
  record LeaseSpec(String image, Map<String, String> env, String user, Limits limits) {
    @Override
    public String toString() {
      return "LeaseSpec[image=" + image + ", user=" + user + ", limits=" + limits + "]";
    }
  }

  /** A retired runner's container and volume a lease takes over (legacy adoption). */
  record Adopt(String container, String volume) {}

  /**
   * What a desk's start asks for.
   *
   * @param projectId the desk's project: its {@code ownerRef} and its work key. The lease's own
   *     {@code projectId} is not sent: the owner ref already names it, and qits-system's DESK
   *     tenant states record none
   * @param adopt the retired runner's container and volume, or null
   * @param pinTargetId the one target to consider, or null for any
   */
  record LeaseRequest(String projectId, LeaseSpec spec, Adopt adopt, UUID pinTargetId) {}

  /**
   * One lease as qits-system answers it, narrowed to what this service reads.
   *
   * @param ownerRef the project id it was asked for
   * @param slotType DESK for every lease of this service's
   * @param targetId null while QUEUED
   * @param exitCode set for EXITED
   * @param detail why a QUEUED lease waits, or why it failed or exited
   * @param specHash qits-system's hash of the spec the lease runs
   */
  record Lease(
      UUID id,
      String ownerRef,
      String slotType,
      LeaseState state,
      UUID targetId,
      Integer exitCode,
      String detail,
      String specHash,
      Instant requestedAt) {}

  /**
   * One target, as the listing shows it; {@code engineId} and {@code hostname} may be null.
   *
   * @param deskMinimum {@code minimums.desk}: how many DESK slots this target reserves, 0 when it
   *     reserves none. Read by {@code DeskLeases.legacyPin}'s sole-desk-reserving-target fallback
   *     (qits-1551).
   */
  record Target(UUID id, String name, String engineId, String hostname, int deskMinimum) {}

  /**
   * qits-system refused or did not answer a lease call.
   *
   * @param status the HTTP status, or -1 when nothing answered
   * @param code the refusal's code ({@code IMAGE_NOT_ALLOWED}, {@code LEASE_EXISTS}, …), or null
   */
  final class LeaseRefused extends RuntimeException {
    private final int status;
    private final String code;

    public LeaseRefused(int status, String code, String message, Throwable cause) {
      super(message, cause);
      this.status = status;
      this.code = code;
    }

    public int status() {
      return status;
    }

    public String code() {
      return code;
    }

    /** A refusal about the request itself (4xx but 409): asking again will not change it. */
    public boolean permanent() {
      return status >= 400 && status < 500 && status != 409;
    }
  }

  /** {@code POST /system/api/leases}: the lease, QUEUED (or further, if it moved at once). */
  Lease create(LeaseRequest request);

  /** {@code GET /system/api/leases/{id}}; empty when there is no such lease (404). */
  Optional<Lease> get(UUID leaseId);

  /** {@code GET /system/api/leases}: this service's own leases. Throws when unreadable. */
  List<Lease> list();

  /** {@code PUT /system/api/leases/{id}/spec}: the lease with its new spec — a roll. */
  Lease replaceSpec(UUID leaseId, LeaseSpec spec);

  /** {@code DELETE /system/api/leases/{id}}: ENDING or ENDED; empty when there is no such lease. */
  Optional<Lease> end(UUID leaseId);

  /**
   * {@code DELETE /system/api/work-volumes/{workKey}}: true when the volume is marked for purging,
   * false when there is none.
   *
   * @throws LeaseRefused 409 {@code WORK_VOLUME_IN_USE} while a lease that has not ENDED uses it
   */
  boolean purgeWorkVolume(String workKey);

  /** {@code GET /system/api/targets}. Throws when unreadable. */
  List<Target> targets();
}
