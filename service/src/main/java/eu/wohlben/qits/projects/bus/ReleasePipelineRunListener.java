package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.ReleasePipelineRuns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * qits-ci said one of its run rows changed status, so the release pipeline's live view moves.
 *
 * <p><b>A SECOND consumption beside {@link BuildStatusListener}, and the split is the two events'
 * own.</b> That listener consumes {@code BuildSuccessful}/{@code BuildFailed}, which are statements
 * about a <em>commit</em> — qits-ci announces terminal runs only on those, and neither a cancelled
 * nor a superseded one, which is exactly what makes every row of the build-status ledger a genuine
 * verdict. This one consumes {@code BuildStatusChanged}, which is a statement about the <em>run's
 * own row</em> and is exhaustive: {@code QUEUED} on accept, {@code RUNNING} when a worker claims it,
 * and the terminal transition. A live pipeline view is about precisely the two states the verdict
 * events leave out, so the two consumptions want opposite halves of one run and neither event could
 * have been widened into the other.
 *
 * <p><b>{@link #CONSUMER_ID} is a NEW storage key and {@code BuildStatusListener}'s is untouched.</b>
 * A consumer id names every {@code consumed_event} row and the watermark, so the two consumptions
 * claim and settle independently — which is what they are for, since one of them writing a verdict
 * must never be held behind the other mirroring a queue position. Reusing that listener's id would
 * have been a second listener inheriting a watermark it was never offered the events behind.
 *
 * <h2>What is bound, and why</h2>
 *
 * <p><b>The payload is a local record and the signature is a string</b>, {@code
 * BuildStatusListener}'s arrangement for its stated reason: qits-ci publishes no consumed vocabulary
 * jar this service holds, and the wire is a handful of strings. Unknown fields are ignored by the
 * library's mapper, which is what lets qits-ci add one; the fields bound here are exactly those the
 * mirror stores or correlates on:
 *
 * <ul>
 *   <li>{@code runId} — the row's identity, and what makes a redelivered frame an upsert.
 *   <li>{@code repoId} — half of the publish arm's correlation, and a column in its own right.
 *   <li>{@code phase} — {@code RELEASE_REQUEST} or {@code RELEASE}. <b>The whole membership test:</b>
 *       a run with no phase is no part of a release and is not a phase run of anything.
 *   <li>{@code status} — qits-ci's own word, stored verbatim.
 *   <li>{@code branch} — the ref the run built, which is what the request correlation falls back to
 *       when the payload names no request; see {@code ReleasePipelineRuns}.
 *   <li>{@code releaseRequestId} — <b>bound leniently and absent today</b>. {@code
 *       BuildStatusChanged} does not carry it, so the field reads null and the correlation is
 *       derived from the phase and the branch. It is bound anyway because the publisher's own answer
 *       would be better than any inference here, and binding it now is what makes that a
 *       zero-change improvement rather than a release on both sides.
 * </ul>
 *
 * <p><b>{@code startedAt} and {@code finishedAt} are deliberately not bound</b>, exactly as {@code
 * finishedAt} is not on the verdict listener: this event carries neither, because its {@code
 * occurredAt} <em>is</em> the run row's timestamp for the state it just reached and {@code
 * CanonicalJson} keeps everything {@code QitsEvent} declares out of the payload. The frame's own
 * instant is therefore the ordering fact and the source of both columns, read off the frame.
 *
 * <p><b>{@code previousStatus} is not bound either.</b> It is how a mirror of {@code GET
 * /ci/api/runs/active} tells "left the listing" from "entered it"; this mirror is keyed on the run
 * and converges on the newest transition, so where the run came from adds nothing to what it is.
 *
 * <h2>Failure</h2>
 *
 * <p>The seam's rule, and {@code BuildStatusListener}'s discipline verbatim: a throw rolls the claim
 * back and the event is owed forever, so swallow what retrying cannot fix and throw what it can.
 *
 * <ul>
 *   <li>A payload that will not parse is poison — the same bytes forever — so it is a WARN and a
 *       return, because an event nothing can read must not hold the watermark.
 *   <li><b>A frame with a blank {@code phase} or naming no {@code (runId, repoId)} is SKIPPED, not an
 *       error, and not even a warning.</b> That is every ordinary run on the platform and every
 *       transition of every release published before the cutover; a WARN there would be several
 *       lines per build forever, which is how a log stops being read.
 *   <li>A database that could not answer is left to throw, because the next attempt is exactly what
 *       fixes it.
 * </ul>
 */
@ApplicationScoped
public class ReleasePipelineRunListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(ReleasePipelineRunListener.class);

  /** qits-ci's run-lifecycle event — {@code BuildStatusChanged}'s simple name, as the wire spells it. */
  static final String SIGNATURE = "BuildStatusChanged";

  /**
   * This consumer's storage key, in {@code consumed_event} and {@code consumer_watermark}.
   * <b>Never change it</b> — a new value is a brand-new consumer initializing at the head of the
   * log, silently skipping everything in between. It names the consumption, not the class, and it is
   * deliberately not {@code BuildStatusListener}'s: two consumptions, two watermarks.
   */
  static final String CONSUMER_ID = "projects-release-pipeline-run";

  /** The fields this mirror binds. See "What is bound, and why" in the class javadoc. */
  public record PipelineRunPayload(
      String runId,
      String repoId,
      String releaseRequestId,
      String phase,
      String status,
      String branch) {}

  @Inject ReleasePipelineRuns pipelineRuns;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  @Override
  public void onFrame(EventFrame frame) {
    PipelineRunPayload run = decode(frame);
    if (run == null) {
      // Warned in decode. Returning settles the event: the same bytes would fail identically on
      // every later offer, and an event nothing can read must not hold the watermark.
      return;
    }
    if (isBlank(run.phase()) || isBlank(run.runId()) || isBlank(run.repoId())) {
      // The ordinary case, silently: a run with no phase is no part of a release. See the javadoc.
      return;
    }
    if (isBlank(run.status())) {
      // A transition with no status it moved TO says nothing this mirror can store, and the column
      // is not null. Poison rather than traffic — qits-ci writes the word on every transition — so
      // it is worth one line, and it is still a return.
      LOG.warnf("%s %s names no status to mirror; it is skipped", frame.name(), frame.id());
      return;
    }
    pipelineRuns.record(
        new ReleasePipelineRuns.Transition(
            run.runId(),
            run.repoId(),
            run.releaseRequestId(),
            run.phase(),
            run.status(),
            run.branch(),
            frame.occurredAt(),
            causeOf(frame)));
  }

  /**
   * This frame as the row's cause. Lenient — an id that is not a UUID costs the trace edge and
   * nothing else, because causation must never be able to refuse a mirror its row.
   */
  private static UUID causeOf(EventFrame frame) {
    if (frame.id() == null) {
      return null;
    }
    try {
      return UUID.fromString(frame.id());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private PipelineRunPayload decode(EventFrame frame) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), PipelineRunPayload.class);
    } catch (RuntimeException e) {
      LOG.warnf("%s %s has an unreadable payload: %s", frame.name(), frame.id(), e.getMessage());
      return null;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
