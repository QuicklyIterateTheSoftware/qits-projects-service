package eu.wohlben.qits.projects.control;

/**
 * The agent in the workspace standing on a branch is told the work it is on as it now stands — its
 * title, its status and whether it is blocked — delivered after the fact and waited on by nobody
 * (qits-614 for the flag, qits-617 for the other two).
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A block is written on the entity's row and said on its thread, and both live here. The agent
 * working the entity lives in a container this service does not run, under a Claude session whose
 * name is the one thing a person scanning a list of sessions reads. A blocked ticket whose session
 * reads exactly like a working one is a ticket somebody opens to find stalled. With this channel the
 * far side names the session {@code <status square> <qualified id> <title>} — the square coloured by
 * the status and pale while a block stands — so the list says what each session is on, how far it
 * has come and which are waiting on a person before anybody opens one. The dispatch seeds the three
 * values ({@code WorkspaceAgentDispatch.Subject}); this port is every change after it: a block or an
 * unblock, <b>every</b> transition, and a retitle. It always sends all three as they stand on the
 * row, never a delta, so a signal that was lost is repaired by the next one about anything. It
 * carries values rather than prose because the far side renames a session, not a model's next turn:
 * nothing here speaks to the agent itself, and a rename that also interrupted the agent would be a
 * second act a person did not ask for.
 *
 * <h2>Nobody is waiting on it, and that is the contract</h2>
 *
 * <p><b>An implementation must never throw</b>, for {@link WorkspaceAgentTurns}' reason exactly: it
 * is told <em>after</em> a block, an unblock, a transition or a retitle that has already been recorded — the
 * row is written, the thread is stamped, the caller's request is already a success — so a workspace
 * that could not be told must never turn a block that happened into a 500. Absent implementation, no
 * address, no machine credential, an unreachable or refusing qits-workspaces, a qits-workspaces old
 * enough not to have the door at all (a 404 on the old door as well — see the adapter) and an answer that will not parse are <b>one
 * behaviour</b>: one WARN naming the branch and the reason, and a normal return.
 *
 * <p>It is cheaper to lose than a turn. A signal nobody delivered costs a session name its accuracy
 * until the next one: the values are on the row and on every board, and the next dispatch onto the
 * branch starts the agent with them as the row holds them. Nothing is stranded and nothing has to be
 * undone, so the method answers nothing at all — unlike {@link WorkspaceAgentTurns#deliver}, there
 * is no thread sentence for a caller to write about a session name, and an answer nobody reads is a
 * return value somebody eventually branches on.
 *
 * <h2>It must not be slow either</h2>
 *
 * <p>The callers are a person's block press, a transition and an edit, all synchronous. An implementation
 * bounds the whole exchange to a few seconds — the far side records three values and renames a session, so a
 * read's order of magnitude rather than a launch's — because a hop that hung would hold a request a
 * person is looking at for a label.
 *
 * <h2>Absent is a supported configuration</h2>
 *
 * <p>Injected as {@code Instance<T>} like every port here. With no implementation present nothing is
 * asked and every write behaves exactly as it did before this port existed: the row is written,
 * the thread is stamped, and the session keeps the name it had. That is a loss of a label, never of
 * a write.
 *
 * <h2>Why this is not a verb on {@link WorkspaceAgentTurns}</h2>
 *
 * <p>The failure contracts are the same, which is the test AGENTS.md sets for sharing a class, and
 * it still is not one: a turn is prose the agent acts on and answers an {@code Outcome} a thread
 * carries, while this is a label the far side's session bookkeeping reads and answers nothing. A
 * {@code deliver} overload taking a title would be a method whose name says "speak to the agent"
 * doing the one thing that deliberately does not. Two verbs, two doors on the far side, two ports.
 */
public interface WorkspaceAgentEntities {

  /**
   * Tell the workspace standing on {@code branch} what the work it is on now is: its title, its
   * status and its blocked flag, all three as the row holds them.
   *
   * <p><b>Never throws.</b> See the class javadoc. A branch with no workspace on it is the ordinary
   * answer for an entity nobody dispatched an agent onto, and is not worth a log line above DEBUG.
   *
   * @param repositoryId the <b>catalog</b> repository id qits-workspaces keys {@code repository_id}
   *     by — for a ticket or an epic, the project's wrapper, as for {@link
   *     WorkspaceAgentTurns#deliver}
   * @param branch the branch the workspace stands on — {@code ticket/<slug>} or {@code epic/<slug>}
   * @param title the entity's title as it stands on the row
   * @param status the entity's status as stored — the {@code EntityStatus} name, {@code REFINED},
   *     which the far side maps to the square's colour; never a rendered label
   * @param blocked what the flag now is on the entity's row
   */
  void changed(String repositoryId, String branch, String title, String status, boolean blocked);

  /**
   * {@link #changed(String, String, String, String, boolean)}, naming the work item too
   * (qits-112): qits-workspaces finds the workspace bound to {@code workId} first and falls back to
   * the branch. A port that knows no work id ignores it.
   *
   * @param workId the work item's entity id, or {@code null}
   */
  default void changed(
      String workId,
      String repositoryId,
      String branch,
      String title,
      String status,
      boolean blocked) {
    changed(repositoryId, branch, title, status, blocked);
  }

  /**
   * {@link #changed(String, String, String, String, String, boolean)}, carrying the effective
   * block's SOURCE too (qits-895): {@code "EXPLICIT"}, {@code "AGENT_WAITING"} or {@code "BOTH"}
   * ({@code entities.control.EntityBlockState}), and {@code null} while {@code blocked} is false. A
   * port that knows no source ignores it, which is why the default delegates down rather than up —
   * an implementation written before this overload existed keeps compiling and keeps sending the
   * four it already sent.
   *
   * @param blockSource the effective block's source, or {@code null} when not blocked
   */
  default void changed(
      String workId,
      String repositoryId,
      String branch,
      String title,
      String status,
      boolean blocked,
      String blockSource) {
    changed(workId, repositoryId, branch, title, status, blocked);
  }
}
