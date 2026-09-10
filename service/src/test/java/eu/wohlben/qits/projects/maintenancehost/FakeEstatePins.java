package eu.wohlben.qits.projects.maintenancehost;

import eu.wohlben.qits.projects.control.EstatePins;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The suite's {@link EstatePins}: an ordinary bean, which beats the {@code @DefaultBean} {@link
 * HttpEstatePins} at the port's injection point, so no test asks qits-maintenance to write a pin.
 * {@link FakeDownstreamComponents}' shape one class over, including the rule that costs the most to
 * rediscover — <b>everything is read through methods and nothing is a public field</b>, because the
 * injected reference is a CDI client proxy and a proxy does not proxy field access.
 *
 * <p><b>It accepts by default</b>, answering a fresh bump id, because that is the posture in which
 * the flow behaves as the feature intends: a wrapper whose pins are stale asks, is told the bump is
 * accepted, and waits for the commit to re-arm it. {@link #answerNothing()} is the other one and it
 * is the load-bearing one — "could not ask", which is what an absent, unreachable or refusing
 * qits-maintenance looks like from the domain, and which must leave a request holding rather than
 * releasing a stale estate.
 */
@ApplicationScoped
public class FakeEstatePins implements EstatePins {

  /** One ask, exactly as the port declares it — so a test can assert what was asked and with what. */
  public record Asked(String repositoryName, String branch, List<GitlinkChange> changes) {}

  private final List<Asked> asked = Collections.synchronizedList(new ArrayList<>());

  private volatile boolean accept = true;

  public List<Asked> asked() {
    return List.copyOf(asked);
  }

  /** The branches that were asked about, in the order they were asked — the "once per source" claim. */
  public List<String> branchesAsked() {
    return asked().stream().map(Asked::branch).toList();
  }

  /** Answer "could not ask" from here on: no address, no route, a refusal, a 409, a timeout. */
  public void answerNothing() {
    accept = false;
  }

  public void reset() {
    asked.clear();
    accept = true;
  }

  @Override
  public Optional<String> bump(String repositoryName, String branch, List<GitlinkChange> changes) {
    asked.add(new Asked(repositoryName, branch, List.copyOf(changes)));
    return accept ? Optional.of("bump-" + UUID.randomUUID()) : Optional.empty();
  }
}
