package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.WorkEntity;
import java.util.List;

/**
 * The branch a work item's agent stands on in the project's wrapper (qits-1152, decision D9 of
 * {@code agent-workspace-decoupling-plan.md}):
 *
 * <pre>
 *   ticket/&lt;qualified id&gt;     e.g. ticket/qits-1002
 *   epic/&lt;qualified id&gt;       e.g. epic/qits-1001
 * </pre>
 *
 * <p>The prefix is the archetype's, as before; the rest is the row's qualified id ({@code
 * <project-slug>-<number>}), no longer its slug. So every branch says which work item its changes
 * came from. In each submodule it changes, the agent names its own branch {@code
 * <wrapper branch>_<its choice>} ({@link #AGENT_SEPARATOR}); qits-workspaces derives the agent's
 * push scope from the wrapper branch, {@link #agentRefs}.
 *
 * <p><b>The separator is {@code _} and not {@code -}, on purpose.</b> A qualified id is {@code
 * <project slug>-<number>} and a project slug may hold dashes and digits, so {@code ticket/a-1-*}
 * (project {@code a}, ticket 1) would also cover {@code ticket/a-1-5}, the wrapper branch of project
 * {@code a-1}'s ticket 5. Neither a slug nor a number can hold {@code _}, so {@code
 * ticket/a-1_*} covers that agent's branches and nobody else's.
 *
 * <p>Only a ticket and an epic have a branch. A feature or a task is worked on by its epic's agent,
 * and a campaign orders work rather than being worked on (qits-411).
 *
 * <p>A <b>pure derivation</b>: no repository, no query. The caller renders the qualified id and
 * hands it in, because the project's slug is not on the row.
 */
public final class WorkBranches {

  /** Every ref an agent may push is a branch. */
  public static final String HEADS = "refs/heads/";

  /** Between the wrapper branch and the name an agent picks in a submodule. */
  public static final String AGENT_SEPARATOR = "_";

  private WorkBranches() {}

  /**
   * The wrapper branch of {@code entity}'s agent.
   *
   * @param entity a {@code TICKET} or {@code EPIC} row
   * @param qualifiedId the row's {@code <project-slug>-<number>}
   * @throws IllegalArgumentException for any other archetype, or a blank qualified id
   */
  public static String of(WorkEntity entity, String qualifiedId) {
    String prefix =
        switch (entity.archetype) {
          case TICKET -> "ticket/";
          case EPIC -> "epic/";
          case CAMPAIGN ->
              throw new IllegalArgumentException(
                  "campaign "
                      + entity.id
                      + " has no branch — a campaign orders work, it is not worked on");
          case FEATURE, TASK ->
              throw new IllegalArgumentException(
                  entity.archetype + " " + entity.id + " has no branch of its own — its epic's agent"
                      + " works on it");
        };
    if (qualifiedId == null || qualifiedId.isBlank()) {
      throw new IllegalArgumentException(
          entity.archetype + " " + entity.id + " has no qualified id to name its branch by");
    }
    return prefix + qualifiedId;
  }

  /**
   * The refs an agent on {@code wrapperBranch} may push: the branch itself and every {@code
   * <wrapperBranch>_<name>}. qits-workspaces states the same pair; it is spelled here so this side
   * can check a branch against it ({@link #isAgentBranch}).
   */
  public static List<String> agentRefs(String wrapperBranch) {
    return List.of(ref(wrapperBranch), ref(wrapperBranch + AGENT_SEPARATOR + "*"));
  }

  /**
   * Whether {@code branch} lies in the scope of the agent on {@code wrapperBranch}: the wrapper
   * branch itself, or {@code <wrapperBranch>_<at least one character>}.
   */
  public static boolean isAgentBranch(String wrapperBranch, String branch) {
    if (wrapperBranch == null || wrapperBranch.isBlank() || branch == null) {
      return false;
    }
    String prefix = wrapperBranch + AGENT_SEPARATOR;
    return branch.equals(wrapperBranch)
        || (branch.startsWith(prefix) && branch.length() > prefix.length());
  }

  /** The ref of a branch: {@code refs/heads/<branch>}. */
  public static String ref(String branch) {
    return HEADS + branch;
  }
}
