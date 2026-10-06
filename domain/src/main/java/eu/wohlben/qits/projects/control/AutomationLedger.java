package eu.wohlben.qits.projects.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this service knows about one release request's <b>automations</b>, correlated to the fold it
 * knows it about. The mechanism that makes the automation gate hold — the estate gate, generalised
 * from "the wrapper's pins" to "every automation that applies" (epic qits-978) — and the smallest
 * piece of that feature that has to be right.
 *
 * <h2>The record is POSITIVE, and everything else follows from that</h2>
 *
 * <p>The gate reads this ledger to answer one question: <em>as of this exact {@code mergedSha}, is
 * every automation that applies fresh?</em> A request passes only on a {@link State#FRESH} note
 * naming that same sha (or a person's waiver of that fold, which lives in a table, not here).
 * Nothing else passes — not a stale note, not a note about another fold, and above all not the
 * <b>absence</b> of a note.
 *
 * <p>The alternative shape was a negative record — a flag saying "the refresh failed" that the gate
 * would refuse on — and it is fail-open in the precise way that would make this whole feature
 * decorative. Every path that never got as far as <em>writing</em> one releases stale output
 * silently: the port unreachable so nothing ever answered, the service restarted between the refresh
 * and the gate, an exception thrown before the write, a request armed by a code path somebody added
 * later and did not think to wire. A positive record inverts every one of those into a hold.
 * <b>Absence is the hold</b>, and it is the only spelling under which forgetting to write is safe.
 *
 * <p>The cost is paid where it should be paid. A request that sits PENDING through a long
 * qits-maintenance outage, saying that its automations could not be established, is the correct
 * behaviour and not a degradation: rejecting it would destroy a legitimate request over a fact about
 * this moment, and releasing it would ship exactly the stale output the feature exists to remove.
 * Holding is the only failure direction somebody can see and undo — and a person's waiver is the
 * escape when the outage is qits-maintenance's own and its fix is the request being held.
 *
 * <h2>Why it is memory and not a table</h2>
 *
 * <p><b>The note is correlated to {@code mergedSha}, so a re-arm invalidates it for free.</b> That is
 * the same argument {@code ReleaseRequest.ApprovalState} makes for deriving rather than storing — an
 * approval names the fold it judged, so nothing has to remember to clear one — and {@code
 * ReleaseRequest}'s own javadoc states the rule this obeys: no gate's answer is a column on that row,
 * because a column is a second answer that some path will forget to rewrite. A note about a
 * superseded fold is not stale data to be cleaned up; it simply stops matching.
 *
 * <p>Given that, a table would buy durability across a restart and nothing else — and qits-maintenance
 * already holds the durable record of every outcome. A restart empties this map, every open request
 * then reads as "no note" and holds, and the very next sweep asks again and writes one; the far
 * side's trigger is idempotent per (request, fold), so that re-ask answers the stored outcome rather
 * than running anything twice. The consequence of a restart is a hold plus one re-ask, which is
 * self-healing in the safe direction. There is no DDL here on purpose.
 *
 * <h2>Bounded and thread-safe</h2>
 *
 * <p>A note is dropped when its request settles, so the map tracks open requests. {@link
 * ConcurrentHashMap} because the writers are folds — which run on the bus consumption, on the sweep's
 * scheduler thread and on request threads — and the reader is the gate's transaction on whichever of
 * those got there first.
 */
@ApplicationScoped
public class AutomationLedger {

  /**
   * What is known about a fold's automations, read together. Four answers, and only the first one
   * releases anything.
   *
   * <p>The three that hold are kept apart because they are different sentences to a person reading
   * the request, and because the sweep treats them differently: PENDING and FAILED are re-read, so a
   * run that finishes (or a re-run that is pressed) is learned; UNKNOWN is asked again outright.
   */
  public enum State {
    /** Every automation that applies is fresh at this fold — including "none applies". */
    FRESH,
    /** At least one is requested, running or committed: the platform is doing something about it. */
    PENDING,
    /** At least one run was red, a join was refused, or a kind did not converge. A person's turn. */
    FAILED,
    /** It could not be established — no answer, an answer that would not say, or no port at all. */
    UNKNOWN
  }

  /**
   * One kind's outcome as this service keeps it — the far side's answer, minus its row id, which
   * nothing here reads.
   *
   * @param runIds the qits-ci runs behind it, oldest first
   */
  public record Automation(
      String kind,
      String label,
      String state,
      String detail,
      List<String> runIds,
      String branch,
      String resultSha,
      java.time.Instant updatedAt) {

    public Automation {
      runIds = runIds == null ? List.of() : List.copyOf(runIds);
    }

    /** The newest run, or null where there is none yet. */
    public String newestRunId() {
      return runIds.isEmpty() ? null : runIds.get(runIds.size() - 1);
    }
  }

  /**
   * One request's note.
   *
   * @param foldSha the {@code mergedSha} this is a statement about. A note never applies to another
   *     fold, which is the whole of how a re-arm invalidates it.
   * @param automations one entry per kind that applies, as last answered; empty where none applies
   *     or where nothing could be asked
   * @param detail a sentence, for the log and for the request's own {@code detail} — the reason on an
   *     UNKNOWN note, null otherwise
   * @param previousFoldSha the fold this one replaced, as the fold seam knew it — carried so that a
   *     re-ask after an UNKNOWN still tells the far side what changed. Not part of the gate's answer.
   */
  public record Note(
      String foldSha,
      State state,
      List<Automation> automations,
      String detail,
      String previousFoldSha) {

    public Note {
      automations = automations == null ? List.of() : List.copyOf(automations);
    }
  }

  private final Map<String, Note> notes = new ConcurrentHashMap<>();

  /**
   * <b>The gate's question.</b> True only for a {@link State#FRESH} note whose fold is exactly the
   * one asked about — so no note, a note about a superseded fold, a pending, a failed and an unknown
   * one are all one answer: not yet.
   */
  public boolean fresh(String requestId, String foldSha) {
    if (requestId == null || foldSha == null) {
      return false;
    }
    Note note = notes.get(requestId);
    return note != null && note.state() == State.FRESH && foldSha.equals(note.foldSha());
  }

  /** What is on record for this request, whatever fold it is about. Empty is a supported answer. */
  public Optional<Note> noteFor(String requestId) {
    return requestId == null ? Optional.empty() : Optional.ofNullable(notes.get(requestId));
  }

  /** Replace this request's note. The last writer wins; there is only ever one fold in flight. */
  public void record(String requestId, Note note) {
    if (requestId == null || note == null || note.foldSha() == null) {
      return;
    }
    notes.put(requestId, note);
  }

  /**
   * Drop a request's note — called when it settles, so the map stays the size of the open work. A
   * request that comes back (a re-armed REJECTED one, a retried FAILED one) simply has no note and
   * is re-asked, which is the same safe direction a restart takes.
   */
  public void forget(String requestId) {
    if (requestId != null) {
      notes.remove(requestId);
    }
  }
}
