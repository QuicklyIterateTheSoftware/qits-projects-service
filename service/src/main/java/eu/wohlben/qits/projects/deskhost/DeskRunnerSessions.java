package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.DeskRunner;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the runner doors need from the runners' sockets (qits-767): whether a runner is connected,
 * and the few frames a door sends one. {@link DeskRunnerRegistry} — the sessions behind {@link
 * DeskRunnerSocket} — implements it; a door that needs a live runner and finds none connected
 * answers 409 {@code RUNNER_UNAVAILABLE}.
 */
public interface DeskRunnerSessions {

  /** Whether {@code runnerId} holds a socket to this process right now; null when not known. */
  Boolean connected(UUID runnerId);

  /** Since when it has, without a break; null while it does not. */
  Instant connectedSince(UUID runnerId);

  /** The runner version this process pins and upgrades every runner to. */
  String pinnedVersion();

  /** The login command for {@code runner}'s node, or null while it cannot be composed. */
  String loginCommand(DeskRunner runner);

  /**
   * Ask a connected runner for a health check now, answering the {@code requestId} its answer will
   * echo (a pending one's, when one is pending); empty when the runner is not connected.
   */
  Optional<String> requestHealthCheck(UUID runnerId);

  /** Send a connected runner {@code probeLogin}; false when it is not connected. */
  boolean probeLogin(UUID runnerId);

  /** The runner's row is gone: a connected runner is retired as {@code DELETED}. */
  void deleted(UUID runnerId);

  /** An administrator lifted the runner's quarantine: a connected runner is told {@code reinstated}. */
  void reinstated(UUID runnerId, String by);

  /** The runner's slots changed: a connected runner learns them from an ack. */
  void slotsChanged(UUID runnerId);
}
