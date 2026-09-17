package eu.wohlben.qits.projects.deploymenthost;

import eu.wohlben.qits.projects.control.DeploymentRedeploys;
import eu.wohlben.qits.projects.error.DomainException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The suite's {@link DeploymentRedeploys}: an ordinary bean, so it wins the injection over the
 * {@code @DefaultBean} HTTP adapter and no test posts to a qits-deployments.
 *
 * <p>Recording rather than answering, because this port returns nothing: what a test asserts is
 * <b>what was asked for</b> — the pair the far side is keyed on, and the naming taken off the
 * request's own row. It can also be told to fail, so the door's own propagation is testable without
 * a wire.
 */
@ApplicationScoped
public class RecordingDeploymentRedeploys implements DeploymentRedeploys {

  private final List<Redeploy> asks = new CopyOnWriteArrayList<>();

  private final AtomicReference<DomainException> failure = new AtomicReference<>();

  public List<Redeploy> asks() {
    return List.copyOf(asks);
  }

  /** The next ask throws this, the way the real hop throws 502 or 503. */
  public void failWith(DomainException exception) {
    failure.set(exception);
  }

  public void reset() {
    asks.clear();
    failure.set(null);
  }

  @Override
  public void deployAgain(Redeploy ask) {
    asks.add(ask);
    DomainException failing = failure.get();
    if (failing != null) {
      throw failing;
    }
  }
}
