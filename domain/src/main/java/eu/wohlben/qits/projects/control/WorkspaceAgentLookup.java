package eu.wohlben.qits.projects.control;

import java.util.List;
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

  /**
   * An ACTIVE agent, the work item it works on, and what places it (qits-1722).
   *
   * @param agentId the agent's uuid
   * @param workId the work item's row id, which a delivered turn names
   * @param repositoryId the wrapper repository it works in — this service's repository id, so its
   *     project is this service's to read; null when the far side named none
   * @param wrapperBranch its wrapper branch, {@code ticket/qits-1615}: it owns that branch and every
   *     {@code <wrapperBranch>-*} in every repository (qits-workspaces' {@code gitRefsFor}); null
   *     when the far side named none
   */
  record ActiveAgent(String agentId, String workId, String repositoryId, String wrapperBranch) {

    /** An agent nothing places: it owns no branch. */
    public ActiveAgent(String agentId, String workId) {
      this(agentId, workId, null, null);
    }

    /**
     * Whether this agent owns {@code branch} by the git_refs rule: {@code refs/heads/<wrapper>}
     * and {@code refs/heads/<wrapper>-*}.
     */
    public boolean owns(String branch) {
      return wrapperBranch != null
          && !wrapperBranch.isBlank()
          && branch != null
          && (branch.equals(wrapperBranch) || branch.startsWith(wrapperBranch + "-"));
    }
  }

  /**
   * Every ACTIVE agent (qits-1672), with the wrapper repository and branch that say which branches
   * it owns (qits-1722). Empty when that cannot be said; an empty list when there are none. Never
   * throws.
   *
   * <p>Every project's: a caller wanting one project's narrows by {@link
   * ActiveAgent#repositoryId}, whose project is this service's own row. The far side's {@code
   * projectId} filter is not asked for, so the wire stays the one the pact already pins.
   */
  Optional<List<ActiveAgent>> active();
}
