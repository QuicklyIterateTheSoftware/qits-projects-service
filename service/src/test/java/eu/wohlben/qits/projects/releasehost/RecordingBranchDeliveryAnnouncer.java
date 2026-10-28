package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.BranchDeliveryAnnouncer;
import eu.wohlben.qits.projects.control.BranchDeliveryStep;
import eu.wohlben.qits.projects.entity.BranchDelivery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The suite's {@link BranchDeliveryAnnouncer} (qits-1719): records each step. An ordinary bean over
 * the {@code @DefaultBean} announcer, so no test reaches the bus.
 *
 * <p>Like {@link RecordingReleaseRequestStateAnnouncer}, it reads the ledger row back in a
 * transaction of its own before recording — proof that the step is announced only once the row
 * that says so has committed.
 */
@ApplicationScoped
public class RecordingBranchDeliveryAnnouncer implements BranchDeliveryAnnouncer {

  /** One step, and the step a fresh transaction read off the ledger row when it was announced. */
  public record Recorded(BranchDeliveryStep step, String committedStep) {}

  private final List<Recorded> recorded = Collections.synchronizedList(new ArrayList<>());

  public List<Recorded> recorded() {
    return List.copyOf(recorded);
  }

  /** The steps of one branch, in the order they were announced. */
  public List<BranchDeliveryStep> stepsOf(String branch) {
    return recorded().stream()
        .map(Recorded::step)
        .filter(step -> step.branch().equals(branch))
        .toList();
  }

  /** "STEP@qualifiedId" for each step of one branch — the shortest thing a test can assert on. */
  public List<String> movesOf(String branch) {
    return stepsOf(branch).stream().map(step -> step.step() + "@" + step.qualifiedId()).toList();
  }

  public void reset() {
    recorded.clear();
  }

  @Override
  public void onStep(BranchDeliveryStep step) {
    String committed =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    BranchDelivery.<BranchDelivery>find(
                            "projectId = ?1 and repoId = ?2 and branch = ?3",
                            step.projectId(),
                            step.repoId(),
                            step.branch())
                        .firstResultOptional()
                        .map(row -> row.step)
                        .orElse(null));
    recorded.add(new Recorded(step, committed));
  }
}
