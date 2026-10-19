package eu.wohlben.qits.projects.control;

import java.util.Optional;

/**
 * Which work item an agent works on (qits-1448): asked once when an agent writes a comment, so the
 * thread can say "qits-1402's agent" rather than a credential name, and so the comment's
 * notification can tell an agent speaking on its own item from one speaking on somebody else's.
 *
 * <p><b>An implementation must never throw</b>, {@link WorkspaceAgentTurns}' contract: the comment
 * is written whatever this answers. An empty answer means "could not say" — an unknown agent, no
 * address, no credential, an unreachable far side — and the comment is then stored with no work item
 * of its author.
 *
 * <p>Injected as {@code Instance<T>} like every port here. Absent is supported: no lookup is made and
 * every agent comment reads as one whose agent could not be resolved.
 */
public interface WorkspaceAgentLookup {

  /**
   * The row id of the work item {@code agentId} works on, or empty when that cannot be said.
   *
   * @param agentId the agent's uuid, as its bearer's {@code agent} claim carries it
   * @return the work item's row id; never null, and never thrown through
   */
  Optional<String> workOf(String agentId);
}
