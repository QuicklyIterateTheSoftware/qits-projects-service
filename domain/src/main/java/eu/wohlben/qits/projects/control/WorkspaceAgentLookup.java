package eu.wohlben.qits.projects.control;

import java.time.Instant;
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
   * An ACTIVE agent, the work item it works on, what places it (qits-1722), and how long it has
   * been quiet (qits-1756).
   *
   * @param agentId the agent's uuid
   * @param workId the work item's row id, which a delivered turn names
   * @param repositoryId the wrapper repository it works in — this service's repository id, so its
   *     project is this service's to read; null when the far side named none
   * @param wrapperBranch its wrapper branch, {@code ticket/qits-1615}: it owns that branch and every
   *     {@code <wrapperBranch>-*} in every repository (qits-workspaces' {@code gitRefsFor}); null
   *     when the far side named none
   * @param activity what its harness is doing — {@code BUSY}, {@code WAITING}, {@code IDLE}, {@code
   *     ENDED} — or null: the far side keeps it in a daemon cache, so a yielded agent, one with no
   *     daemon, or a far side older than the field answers none
   * @param lastActivityAt the last turn boundary qits-workspaces stamped (a hook frame, a delivered
   *     turn, the start); null when it named none, and such an agent is never idle
   * @param waits the waits it declared, OPEN and closed alike; never null
   */
  record ActiveAgent(
      String agentId,
      String workId,
      String repositoryId,
      String wrapperBranch,
      String activity,
      Instant lastActivityAt,
      List<AgentWait> waits) {

    public ActiveAgent {
      waits = waits == null ? List.of() : List.copyOf(waits);
    }

    /** An agent nothing places: it owns no branch. */
    public ActiveAgent(String agentId, String workId) {
      this(agentId, workId, null, null);
    }

    /** An agent placed by its wrapper repository and branch, with nothing said of its activity. */
    public ActiveAgent(String agentId, String workId, String repositoryId, String wrapperBranch) {
      this(agentId, workId, repositoryId, wrapperBranch, null, null, List.of());
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
   * One wait an agent declared (qits-1618), as qits-workspaces lists it with the agent (qits-1756).
   *
   * @param label what it waits for, as the agent named it
   * @param state {@code OPEN}, or how it closed
   * @param subjectKind what the wait watches ({@code BRANCH}, {@code RELEASE_REQUEST}, …), or null
   * @param subjectRef the watched subject's id or ref, or null
   * @param subjectQualifiedId the watched subject's qualified id, or null
   * @param subjectState the subject's last known state, or null
   * @param expiresAt when the wait times out; null when it never does
   */
  record AgentWait(
      String label,
      String state,
      String subjectKind,
      String subjectRef,
      String subjectQualifiedId,
      String subjectState,
      Instant expiresAt) {

    /** Whether the wait still waits. */
    public boolean open() {
      return "OPEN".equals(state);
    }
  }

  /**
   * Every ACTIVE agent (qits-1672), with the wrapper repository and branch that say which branches
   * it owns (qits-1722), and its activity, last turn and waits (qits-1756). Empty when that cannot
   * be said; an empty list when there are none. Never throws.
   *
   * <p>Every project's: a caller wanting one project's narrows by {@link
   * ActiveAgent#repositoryId}, whose project is this service's own row. The far side's {@code
   * projectId} filter is not asked for, so the wire stays the one the pact already pins.
   */
  Optional<List<ActiveAgent>> active();
}
