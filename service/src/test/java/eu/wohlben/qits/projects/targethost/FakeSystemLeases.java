package eu.wohlben.qits.projects.targethost;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * qits-system's lease doors, in memory, for every suite (epic qits-1481): it wins the injection over
 * the {@code @DefaultBean} {@link HttpSystemLeases}, so no test reaches a network. A create answers
 * QUEUED; an end answers ENDED at once unless {@link #slowEnd} says otherwise; a test moves a lease
 * with {@link #move} and hands {@link DeskLeases#observe} what it now says.
 */
@ApplicationScoped
public class FakeSystemLeases implements SystemLeases {

  private final Map<UUID, Lease> leases = Collections.synchronizedMap(new LinkedHashMap<>());
  private final Map<UUID, LeaseRequest> requests = Collections.synchronizedMap(new LinkedHashMap<>());
  private final List<UUID> ended = Collections.synchronizedList(new ArrayList<>());
  private final List<String> purged = Collections.synchronizedList(new ArrayList<>());
  private final List<UUID> rolled = Collections.synchronizedList(new ArrayList<>());
  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
  private volatile List<Target> targets = List.of();
  private volatile LeaseRefused refuseCreate;
  private volatile LeaseRefused unreachable;
  private volatile boolean slowEnd;
  private volatile boolean hideFromListing;

  public synchronized void reset() {
    leases.clear();
    requests.clear();
    ended.clear();
    purged.clear();
    rolled.clear();
    calls.clear();
    targets = List.of();
    refuseCreate = null;
    unreachable = null;
    slowEnd = false;
    hideFromListing = false;
  }

  // --- scripting -----------------------------------------------------------------------------

  public void targets(List<Target> targets) {
    this.targets = List.copyOf(targets);
  }

  public void refuseCreate(LeaseRefused refused) {
    this.refuseCreate = refused;
  }

  /** Every call throws {@code refused} until reset. */
  public void unreachable(LeaseRefused refused) {
    this.unreachable = refused;
  }

  /** An end answers ENDING and leaves the lease ENDING. */
  public void slowEnd(boolean slow) {
    this.slowEnd = slow;
  }

  /** The listing leaves every lease out (as a listing read before the lease was made would). */
  public void hideFromListing(boolean hide) {
    this.hideFromListing = hide;
  }

  /** Moves {@code leaseId} to {@code state} on {@code targetId}, with {@code detail}; answers it. */
  public Lease move(UUID leaseId, LeaseState state, UUID targetId, String detail) {
    Lease was = leases.get(leaseId);
    Lease now =
        new Lease(
            leaseId,
            was.ownerRef(),
            SLOT_TYPE,
            state,
            targetId,
            null,
            detail,
            was.specHash(),
            was.requestedAt());
    leases.put(leaseId, now);
    return now;
  }

  /** Forgets {@code leaseId}: a GET answers 404 from now on. */
  public void forget(UUID leaseId) {
    leases.remove(leaseId);
  }

  /** Plants a lease no desk asked for, requested at {@code requestedAt}. */
  public UUID plant(String ownerRef, LeaseState state, Instant requestedAt) {
    UUID id = UUID.randomUUID();
    leases.put(id, new Lease(id, ownerRef, SLOT_TYPE, state, null, null, null, "h", requestedAt));
    return id;
  }

  public Optional<Lease> lease(UUID leaseId) {
    return Optional.ofNullable(leases.get(leaseId));
  }

  public LeaseRequest requestOf(UUID leaseId) {
    return requests.get(leaseId);
  }

  public List<LeaseRequest> requests() {
    return List.copyOf(requests.values());
  }

  public List<UUID> ended() {
    return List.copyOf(ended);
  }

  public List<String> purged() {
    return List.copyOf(purged);
  }

  public List<UUID> rolled() {
    return List.copyOf(rolled);
  }

  public List<String> calls() {
    return List.copyOf(calls);
  }

  // --- the port ------------------------------------------------------------------------------

  private void check(String call) {
    calls.add(call);
    if (unreachable != null) {
      throw unreachable;
    }
  }

  @Override
  public Lease create(LeaseRequest request) {
    check("create");
    if (refuseCreate != null) {
      throw refuseCreate;
    }
    UUID id = UUID.randomUUID();
    Lease lease =
        new Lease(
            id,
            request.projectId(),
            SLOT_TYPE,
            LeaseState.QUEUED,
            null,
            null,
            "no target has a free DESK slot",
            "hash-" + id,
            Instant.now());
    leases.put(id, lease);
    requests.put(id, request);
    return lease;
  }

  @Override
  public Optional<Lease> get(UUID leaseId) {
    check("get");
    return Optional.ofNullable(leases.get(leaseId));
  }

  @Override
  public List<Lease> list() {
    check("list");
    return hideFromListing ? List.of() : List.copyOf(leases.values());
  }

  @Override
  public Lease replaceSpec(UUID leaseId, LeaseSpec spec) {
    check("replaceSpec");
    Lease was = leases.get(leaseId);
    if (was == null) {
      throw new LeaseRefused(404, null, "no lease " + leaseId, null);
    }
    rolled.add(leaseId);
    Lease now =
        new Lease(
            leaseId,
            was.ownerRef(),
            SLOT_TYPE,
            was.state(),
            was.targetId(),
            null,
            was.detail(),
            "rolled-" + rolled.size(),
            was.requestedAt());
    leases.put(leaseId, now);
    return now;
  }

  @Override
  public Optional<Lease> end(UUID leaseId) {
    check("end");
    Lease was = leases.get(leaseId);
    if (was == null) {
      return Optional.empty();
    }
    ended.add(leaseId);
    return Optional.of(
        move(leaseId, slowEnd ? LeaseState.ENDING : LeaseState.ENDED, was.targetId(), null));
  }

  @Override
  public boolean purgeWorkVolume(String workKey) {
    check("purge");
    purged.add(workKey);
    return true;
  }

  @Override
  public List<Target> targets() {
    check("targets");
    return targets;
  }
}
