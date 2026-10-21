package eu.wohlben.qits.projects.maintenancehost;

import eu.wohlben.qits.projects.control.AutomationKinds;
import eu.wohlben.qits.projects.control.approval.AutomationContentSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

/**
 * The suite's {@link AutomationKinds}: an ordinary bean, which beats the {@code @DefaultBean} {@link
 * HttpAutomationKinds}, so no test dials qits-maintenance. <b>It answers "not configured" until a
 * test scripts it</b>, which is the posture every suite not about automation content runs in: no
 * {@code automation:*} requirement opens. Scripting or resetting it forgets what {@link
 * AutomationContentSource} cached, so a test never meets the previous one's catalogue.
 */
@ApplicationScoped
public class FakeAutomationKinds implements AutomationKinds {

  @Inject AutomationContentSource source;

  private volatile boolean configured;

  private volatile Optional<List<Kind>> answer = Optional.empty();

  /** Configured, answering {@code kinds}. */
  public void answer(List<Kind> kinds) {
    configured = true;
    answer = Optional.of(List.copyOf(kinds));
    source.forget();
  }

  /** Configured, and unreachable. */
  public void unreachable() {
    configured = true;
    answer = Optional.empty();
    source.forget();
  }

  public void reset() {
    configured = false;
    answer = Optional.empty();
    source.forget();
  }

  @Override
  public boolean configured() {
    return configured;
  }

  @Override
  public Optional<List<Kind>> kinds() {
    return answer;
  }
}
