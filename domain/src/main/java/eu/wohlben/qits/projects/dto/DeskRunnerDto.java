package eu.wohlben.qits.projects.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A front-desk runner as the runners page reads it (qits-767): qits-workspaces-service's {@code
 * WorkspaceRunnerDto} without the workspace memory limits and counts, with the desks the runner
 * holds. Built by {@code DeskRunnerMapper}, and only there. The row's columns and the runner's last
 * report come from the mapper; what is live — the connection, the pin, the login command and the
 * desks — is handed to it by the service as {@code DeskRunnerMapper.Live}.
 *
 * <p>The live members are boxed and nullable: null is "not known here", which is what a process
 * holding no socket for the runner answers.
 *
 * @param id the runner's id: what its install line, register door and socket name
 * @param name unique, {@code [a-z][a-z0-9-]{0,63}}
 * @param description the operator's words, or null
 * @param slots how many desks it may hold at once; at least one
 * @param version the runner binary's version, as it last reported it; null until it has
 * @param registered whether the register door has answered this runner
 * @param registeredAt when it registered, or null
 * @param quarantined whether it is out of service (it takes no desk)
 * @param quarantinedAt since when, or null
 * @param quarantineReason why, or null
 * @param loginState the node's agent login, {@code PRESENT} or {@code ABSENT}, as last probed; null
 *     until it has been
 * @param lastSeenAt when the runner was last heard from, or null
 * @param lastHealthCheckAt when its newest health check settled, or null
 * @param lastHealthCheckOk whether that check passed, or null
 * @param createdAt when the runner was declared
 * @param connected whether the runner holds a socket to this process right now
 * @param connectedSince since when it has, without a break; null while it does not
 * @param pinnedVersion the runner version this process pins and upgrades every runner to
 * @param loginCommand the one command that logs the node's agent home in, run on the node; null
 *     while it cannot be composed yet
 * @param desks the project front desks placed on this runner
 * @param health the newest health check's verdict and each named check's, without their data
 *     ({@code GET /runners/{id}/health} answers that); null until a check has settled
 */
public record DeskRunnerDto(
    UUID id,
    String name,
    String description,
    int slots,
    String version,
    boolean registered,
    Instant registeredAt,
    boolean quarantined,
    Instant quarantinedAt,
    String quarantineReason,
    String loginState,
    Instant lastSeenAt,
    Instant lastHealthCheckAt,
    Boolean lastHealthCheckOk,
    Instant createdAt,
    Boolean connected,
    Instant connectedSince,
    String pinnedVersion,
    String loginCommand,
    List<DeskRunnerDesk> desks,
    DeskRunnerHealth health) {

  /**
   * One desk a runner holds.
   *
   * @param projectId the project whose front desk it is
   * @param slug that project's slug, or null when it could not be read
   * @param state where the desk stands, or null
   */
  public record DeskRunnerDesk(String projectId, String slug, String state) {}

  /**
   * The newest health check, as the listing reads it.
   *
   * @param at when it settled
   * @param ok whether every check passed
   * @param detail the runner's line for a person, or why the check settled without an answer
   * @param checks each named check's outcome, in the runner's order
   */
  public record DeskRunnerHealth(
      Instant at, boolean ok, String detail, List<DeskRunnerCheck> checks) {}

  /**
   * One named check's outcome, without its data.
   *
   * @param name the check's stable name
   * @param ok whether it found what it looks for
   * @param detail its line for a person
   */
  public record DeskRunnerCheck(String name, boolean ok, String detail) {}
}
