package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.targethost.SystemLeases;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * <b>The delivery desk's slots, derived, never configured</b> (qits-2573): the one reading the
 * desk's dispatch door ({@link EntityDispatch}), the slot-opened trigger ({@code
 * desktriggers/CapacityOpenedSource}) and the Slots line every delivery trigger ends with ({@code
 * desktriggers/VerifiedSlotHolders}) all make, so the brief's dispatch step reads the same figure
 * the door refuses on.
 *
 * <ul>
 *   <li><b>N, running</b>: {@link RunningItems#read}'s running items — an ACTIVE agent whose
 *       workspace holds its lease.
 *   <li><b>K, waiting</b>: its items whose agent's workspace is QUEUED. They are not running.
 *   <li><b>F, free</b>: the WORKSPACE leases the targets can grant now, as qits-system's allocator
 *       sees their slots and memory ({@link SystemLeases#capacity}, {@code GET
 *       /system/api/targets/capacity}). Estate-wide: another project's or a person's workspace
 *       lowers it. Null when it cannot be read — unknown, never 0 and never a configured number.
 *   <li><b>M, total</b> = N + F, so N never exceeds M; null with F.
 * </ul>
 *
 * <p>Asked on demand, never cached across calls and never rebuilt here from lease events: the
 * figure is the allocator's own rule over the whole queue and memory, and a second copy of it would
 * drift.
 */
@ApplicationScoped
public class DeskSlots {

  private static final Logger LOG = Logger.getLogger(DeskSlots.class);

  @Inject RunningItems running;

  @Inject Instance<SystemLeases> system;

  /**
   * One reading of a project's slots.
   *
   * @param running N's items, with {@code runningAgentIds} their agents
   * @param waiting K's items
   * @param free F, or null when the capacity could not be read
   * @param targets each target's part of F, as qits-system answered it; empty with F unknown
   */
  public record Reading(
      List<WorkEntity> running,
      List<String> runningAgentIds,
      List<WorkEntity> waiting,
      Integer free,
      List<SystemLeases.TargetCapacity> targets) {

    public Reading {
      running = List.copyOf(running);
      runningAgentIds = List.copyOf(runningAgentIds);
      waiting = List.copyOf(waiting);
      targets = targets == null ? List.of() : List.copyOf(targets);
    }

    /** M = N + F; null while F is unknown. */
    public Integer total() {
      return free == null ? null : running.size() + free;
    }

    /** Whether F was read. */
    public boolean known() {
      return free != null;
    }

    /** A slot is open: a workspace lease can be granted now (F > 0) and none waits (K = 0). */
    public boolean open() {
      return free != null && free > 0 && waiting.isEmpty();
    }

    /** {@code running N of M}, or {@code running N} while M is unknown. */
    public String runningText() {
      return "running " + running.size() + (free == null ? "" : " of " + total());
    }

    /**
     * Why no target grants a workspace, one clause per target: {@code ws-proof runs its maximum of 3
     * WORKSPACE lease(s); localhost has no free WORKSPACE slot}. The allocator's details, their
     * leading {@code target } dropped; a target that says nothing is left out.
     */
    public String refusals() {
      return targets.stream()
          .filter(t -> t.grantable() == 0 && t.detail() != null && !t.detail().isBlank())
          .map(t -> t.detail().startsWith("target ") ? t.detail().substring(7) : t.detail())
          .collect(Collectors.joining("; "));
    }

    /** The targets that can grant, {@code ws-proof 1, lab 2}. */
    public String grantingTargets() {
      return targets.stream()
          .filter(t -> t.grantable() > 0)
          .map(t -> t.name() + " " + t.grantable())
          .collect(Collectors.joining(", "));
    }
  }

  /**
   * {@code projectId}'s slots, leaving {@code exceptId} out of N and K (null leaves out none). The
   * running items are read in an epics transaction of their own, the capacity outside it. Throws
   * whatever the agent port throws; the capacity never throws.
   */
  public Reading read(String projectId, String exceptId) {
    RunningItems.Slots slots =
        QuarkusTransaction.requiringNew().call(() -> running.read(projectId, exceptId));
    return of(slots, capacity());
  }

  /** {@code slots} beside a capacity already read (one read per sweep pass, estate-wide). */
  public static Reading of(RunningItems.Slots slots, Optional<SystemLeases.Capacity> capacity) {
    return new Reading(
        slots.running(),
        slots.runningAgentIds(),
        slots.waiting(),
        capacity.map(SystemLeases.Capacity::grantable).map(f -> Math.max(0, f)).orElse(null),
        capacity.map(SystemLeases.Capacity::targets).orElse(List.of()));
  }

  /**
   * The targets' WORKSPACE capacity now; empty when qits-system is not wired here or cannot be read.
   * Never throws.
   */
  public Optional<SystemLeases.Capacity> capacity() {
    if (system.isUnsatisfied()) {
      return Optional.empty();
    }
    try {
      return system.get().capacity(SystemLeases.WORKSPACE);
    } catch (RuntimeException e) {
      LOG.warnf("The targets' workspace capacity is unknown: %s", e.toString());
      return Optional.empty();
    }
  }
}
