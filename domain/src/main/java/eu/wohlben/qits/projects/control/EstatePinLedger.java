package eu.wohlben.qits.projects.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this service knows about one release request's <b>estate pins</b>, correlated to the fold it
 * knows it about. The mechanism that makes the wrapper gate hold, and the smallest piece of this
 * feature that has to be right.
 *
 * <h2>The record is POSITIVE, and everything else follows from that</h2>
 *
 * <p>The gate reads this ledger to answer one question: <em>as of this exact {@code mergedSha}, are
 * the wrapper's gitlinks at the versions its members have released?</em> A wrapper request goes READY
 * only when the answer is a {@link State#FRESH} note naming that same sha. Nothing else passes — not
 * a stale note, not a note about another fold, and above all not the <b>absence</b> of a note.
 *
 * <p>The alternative shape was a negative record — a flag saying "the refresh failed" that the gate
 * would refuse on — and it is fail-open in the precise way that would make this whole feature
 * decorative. Every path that never got as far as <em>writing</em> one releases a stale estate
 * silently: the port unconfigured so nothing ever ran, the service restarted between the refresh and
 * the gate, an exception thrown before the write, a request armed by a code path somebody added later
 * and did not think to wire. A positive record inverts every one of those into a hold. <b>Absence is
 * the hold</b>, and it is the only spelling under which forgetting to write is safe.
 *
 * <p>The cost is paid where it should be paid. A wrapper request that sits PENDING through a long
 * qits-maintenance outage, saying that the estate pins could not be refreshed, is the correct
 * behaviour and not a degradation: rejecting it would destroy a legitimate request over a fact about
 * this moment, and releasing it would ship exactly the stale estate the feature exists to remove.
 * Holding is the only failure direction somebody can see and undo.
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
 * <p>Given that, a table would buy durability across a restart and nothing else — and the value of
 * that durability is negative. A restart empties this map, every open wrapper request then reads as
 * "no note" and holds, and the very next sweep re-asks and writes one. The consequence of a restart
 * is a hold plus one re-ask, which is self-healing in the safe direction, against the cost of a
 * migration, a row nothing else reads, and an {@code @AfterEach} in every suite that touches release
 * requests. There is no DDL here on purpose.
 *
 * <h2>Bounded and thread-safe</h2>
 *
 * <p>A note is dropped when its request settles, so the map tracks open requests and the platform's
 * open wrapper requests are countable on one hand. {@link ConcurrentHashMap} because the writers are
 * folds — which run on the bus consumption, on the sweep's scheduler thread and on request threads —
 * and the reader is the gate's transaction on whichever of those got there first.
 */
@ApplicationScoped
public class EstatePinLedger {

  /**
   * What is known about a fold's pins. Three answers, and only the first one releases anything.
   *
   * <p>The two that hold are kept apart because they are different sentences to a person reading the
   * request: one says the platform is doing something about it, the other says it could not.
   */
  public enum State {
    /** The branch's gitlinks already name what its members released. Nothing was asked for. */
    FRESH,
    /** A bump was asked for and accepted. The commit lands later and re-arms the request. */
    PENDING_BUMP,
    /** The pins could not be established — the port is absent, or the ask could not be made. */
    UNKNOWN
  }

  /**
   * One request's note.
   *
   * @param foldSha the {@code mergedSha} this is a statement about. A note never applies to another
   *     fold, which is the whole of how a re-arm invalidates it.
   * @param detail a sentence, for the log and for the request's own {@code detail}
   */
  public record Note(String foldSha, State state, String detail) {}

  private final Map<String, Note> notes = new ConcurrentHashMap<>();

  /**
   * <b>The gate's question.</b> True only for a {@link State#FRESH} note whose fold is exactly the
   * one asked about — so no note, a note about a superseded fold, a pending bump and an unknown are
   * all one answer: not yet.
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
  public void record(String requestId, String foldSha, State state, String detail) {
    if (requestId == null || foldSha == null) {
      return;
    }
    notes.put(requestId, new Note(foldSha, state, detail));
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
