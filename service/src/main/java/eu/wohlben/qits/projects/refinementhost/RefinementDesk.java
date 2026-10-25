package eu.wohlben.qits.projects.refinementhost;

import java.util.Optional;

/**
 * The project's front desk as a session room reports it (epic qits-770): the desk is where the
 * room's session runs, so the room's answer carries the desk's state beside its own. A port, so
 * {@code refinementhost} names no {@code deskhost} type; {@code deskhost} implements it.
 */
public interface RefinementDesk {

  /**
   * The desk's state word ({@code QUEUED}, {@code PROVISIONING}, {@code RUNNING}, {@code STOPPED},
   * {@code FAILED}, {@code UNAVAILABLE}, {@code ABSENT}) and why, when it says. Empty when it cannot
   * be read. Never throws.
   */
  Optional<DeskStatus> of(String projectId);

  /**
   * @param status the desk's computed state word
   * @param detail why it is FAILED, else null
   */
  record DeskStatus(String status, String detail) {}
}
