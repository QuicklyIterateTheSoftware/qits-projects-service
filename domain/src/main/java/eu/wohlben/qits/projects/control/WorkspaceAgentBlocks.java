package eu.wohlben.qits.projects.control;

/**
 * The agent in the workspace standing on a branch is told that the work it is on was blocked or
 * unblocked — one flag, delivered after the fact and waited on by nobody (qits-614).
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A block is written on the entity's row and said on its thread, and both live here. The agent
 * working the entity lives in a container this service does not run, under a Claude session whose
 * name is the one thing a person scanning a list of sessions reads. A blocked ticket whose session
 * reads exactly like a working one is a ticket somebody opens to find stalled; with this channel the
 * far side prefixes the session name with {@code ❗ } while the block stands and drops it when the
 * block is lifted, so the list says which sessions are waiting on a person before anybody opens one.
 * It carries a boolean rather than prose because the far side renames a session, not a model's next
 * turn: nothing here speaks to the agent itself, and a block that also interrupted the agent would
 * be a second act a person did not ask for.
 *
 * <h2>Nobody is waiting on it, and that is the contract</h2>
 *
 * <p><b>An implementation must never throw</b>, for {@link WorkspaceAgentTurns}' reason exactly: it
 * is told <em>after</em> a block, an unblock or a transition that has already been recorded — the
 * row is written, the thread is stamped, the caller's request is already a success — so a workspace
 * that could not be told must never turn a block that happened into a 500. Absent implementation, no
 * address, no machine credential, an unreachable or refusing qits-workspaces, a qits-workspaces old
 * enough not to have the door at all (a 404) and an answer that will not parse are <b>one
 * behaviour</b>: one WARN naming the branch and the reason, and a normal return.
 *
 * <p>It is cheaper to lose than a turn. A flag nobody delivered costs a session name its marker: the
 * block is on the row, on the thread and on every board, and the next dispatch onto the branch
 * starts the agent with the flag as the row holds it. Nothing is stranded and nothing has to be
 * undone, so the method answers nothing at all — unlike {@link WorkspaceAgentTurns#deliver}, there
 * is no thread sentence for a caller to write about a session name, and an answer nobody reads is a
 * return value somebody eventually branches on.
 *
 * <h2>It must not be slow either</h2>
 *
 * <p>The callers are a person's block press and a transition, both synchronous. An implementation
 * bounds the whole exchange to a few seconds — the far side flips a flag and renames a session, so a
 * read's order of magnitude rather than a launch's — because a hop that hung would hold a request a
 * person is looking at for a label.
 *
 * <h2>Absent is a supported configuration</h2>
 *
 * <p>Injected as {@code Instance<T>} like every port here. With no implementation present nothing is
 * asked and every block behaves exactly as it did before this port existed: the flag is written,
 * the thread is stamped, and the session keeps the name it had. That is a loss of a marker, never of
 * a block.
 *
 * <h2>Why this is not a verb on {@link WorkspaceAgentTurns}</h2>
 *
 * <p>The failure contracts are the same, which is the test AGENTS.md sets for sharing a class, and
 * it still is not one: a turn is prose the agent acts on and answers an {@code Outcome} a thread
 * carries, while this is a flag the far side's session bookkeeping reads and answers nothing. A
 * {@code deliver} overload taking a boolean would be a method whose name says "speak to the agent"
 * doing the one thing that deliberately does not. Two verbs, two doors on the far side, two ports.
 */
public interface WorkspaceAgentBlocks {

  /**
   * Tell the workspace standing on {@code branch} that the work it is on is now {@code blocked} or
   * not.
   *
   * <p><b>Never throws.</b> See the class javadoc. A branch with no workspace on it is the ordinary
   * answer for an entity nobody dispatched an agent onto, and is not worth a log line above DEBUG.
   *
   * @param repositoryId the <b>catalog</b> repository id qits-workspaces keys {@code repository_id}
   *     by — for a ticket or an epic, the project's wrapper, as for {@link
   *     WorkspaceAgentTurns#deliver}
   * @param branch the branch the workspace stands on — {@code ticket/<slug>} or {@code epic/<slug>}
   * @param blocked what the flag now is on the entity's row
   */
  void blocked(String repositoryId, String branch, boolean blocked);
}
