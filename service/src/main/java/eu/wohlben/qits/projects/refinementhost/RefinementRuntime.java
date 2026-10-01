package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.entity.Refinement;
import java.util.List;
import java.util.Optional;

/**
 * The container verbs the refinement lifecycle needs, as a seam — the refinement twin of
 * {@code agenthost/ContainerRuntime}, and like it a port so the suite can install a fake and reach
 * no orchestrator. The sole implementation is {@code containershost/ContainersRefinementRuntime}.
 *
 * <p><b>The place is {@code owner/refinement/<rowId>}.</b> The row id is minted by this database,
 * is unique forever, and is already a legal orchestrator ref — so unlike qits-workspaces there is
 * no name-derived ref and no hash disambiguator to need: the container <em>name</em>
 * ({@code qits-ref-<projectSlug>-<slug>}) is a {@code docker ps} hint carried as
 * {@code explicitName}, never an address. What a human-derived name can still do is collide, and
 * the provisioning arm answers that with a 409 rather than a constraint 500.
 *
 * <p>Unlike the agent runtime this one has a removal verb: a refinement is discarded when its epic
 * leaves refinement, and the teardown order — container first, then volume — is the wire contract.
 */
public interface RefinementRuntime {

  /**
   * The refined entity as its container's daemon names its sessions — {@code <status square>
   * <qualified id> <title>}, the square pale while blocked — read off the row at every bring-up, so a
   * container that slept through a change is right at its next wake (qits-614, qits-617). Labels,
   * never addresses: any of them may be unknown, and an unknown one costs the session name that part
   * and the container nothing else.
   *
   * @param qualifiedId {@code <project-slug>-<number>}, {@code qits-614}, or {@code null} when it
   *     could not be rendered
   * @param title the entity's title, or {@code null} when the row could not be read
   * @param status the status as stored — the {@code EntityStatus} name — or {@code null}
   * @param blocked the block flag; {@code false} also when the row could not be read
   */
  record RefinedEntity(String qualifiedId, String title, String status, boolean blocked) {

    /** Nothing known: the container starts named by its uuid, as before qits-614. */
    public static final RefinedEntity UNKNOWN = new RefinedEntity(null, null, null, false);
  }

  /** One container as this lifecycle reads it. */
  record ContainerInfo(String containerName, boolean running) {}

  /** What is at this refinement's place, or empty when the orchestrator holds no row for it. */
  Optional<ContainerInfo> inspect(long refinementId);

  /**
   * Bring a fresh container up — the arm that commissions. Throws when no running container could
   * be produced; a 2xx whose observed state is MISSING/GONE is a failed launch, not a retry case.
   *
   * <p>{@code entity} is the refined entity as the row stands — see {@link RefinedEntity}; never
   * {@code null}, {@link RefinedEntity#UNKNOWN} when nothing could be read.
   */
  void provision(
      Refinement refinement,
      String projectSlug,
      String slug,
      String wrapperName,
      RefinedEntity entity);

  /**
   * Wake a stopped container — a start in place, a replacement only if the spec really changed.
   * {@code entity} as {@link #provision}.
   */
  void wake(
      Refinement refinement,
      String projectSlug,
      String slug,
      String wrapperName,
      RefinedEntity entity);

  /** Stop the container gracefully, leaving it and its volume in place. Best-effort. */
  void stop(long refinementId);

  /** Stamp the orchestrator's idle clock — a no-op unless a policy reads it. Best-effort. */
  void touch(long refinementId);

  /** Remove the container (never its volumes with it), then the volume. Idempotent. */
  void delete(long refinementId);

  /**
   * Every refinement place this service owns, running or not — the refinement twin of
   * {@code agenthost/ContainerRuntime#listAgentContainers}.
   *
   * <p><b>From the orchestrator's rows, never from a label listing</b>, and scoped to this owner and
   * this workload, so two environments sharing one docker daemon cannot see each other's refinement
   * containers.
   *
   * <p><b>A listing the orchestrator would not answer comes back empty rather than throwing</b>, so a
   * caller sweeping on it does nothing this pass rather than acting on an answer nobody gave.
   */
  List<ContainerInfo> listRefinementContainers();
}
