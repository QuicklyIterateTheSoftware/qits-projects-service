package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.WorkEntity;

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
 * <wrapper branch>-<its choice>}; qits-workspaces derives the agent's push scope from the wrapper
 * branch ({@code refs/heads/<branch>} and {@code refs/heads/<branch>-*}), so nothing here computes
 * refs any more.
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

  /** The ref of a branch: {@code refs/heads/<branch>}. */
  public static String ref(String branch) {
    return HEADS + branch;
  }
}
