package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.PipelinePhaseReruns;
import eu.wohlben.qits.projects.error.DomainException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The suite's {@link PipelinePhaseReruns}: an ordinary bean, so it wins the injection over the
 * {@code @DefaultBean} HTTP adapter and no test posts to a qits-ci. {@code
 * RecordingQaRunCancellations}' shape one class over.
 *
 * <p>It records the ask — and the ask is the assertion, because the phase word crossing this seam is
 * qits-ci's storage vocabulary ({@code RELEASE_REQUEST} / {@code RELEASE}) and not the reader's
 * ({@code QA} / {@code PUBLISH}), so what a door test proves is that the one translation happened.
 *
 * <p>It can also be told to throw, which is how the refusal path is driven from the door's side: the
 * message qits-ci composed must reach the caller unchanged, and a rewritten one is the defect. The
 * wire half of that — reading {@code {"message": …}} off a 409 and rethrowing the far side's own
 * status — is {@code HttpPipelinePhaseRerunsTest}'s.
 */
@ApplicationScoped
public class RecordingPipelinePhaseReruns implements PipelinePhaseReruns {

  /** One ask, as it crossed the seam. */
  public record Asked(String repoId, String releaseRequestId, String ciPhase) {}

  private final List<Asked> asked = new CopyOnWriteArrayList<>();

  private final AtomicReference<DomainException> failure = new AtomicReference<>();

  public List<Asked> asked() {
    return List.copyOf(asked);
  }

  /** The next rerun throws this — qits-ci's own status and sentence, as the adapter propagates it. */
  public void failWith(DomainException exception) {
    failure.set(exception);
  }

  public void reset() {
    asked.clear();
    failure.set(null);
  }

  @Override
  public String rerun(String repoId, String releaseRequestId, String ciPhase) {
    asked.add(new Asked(repoId, releaseRequestId, ciPhase));
    DomainException failing = failure.get();
    if (failing != null) {
      throw failing;
    }
    return "rerun-" + ciPhase;
  }
}
