package eu.wohlben.qits.projects.maintenancehost;

import eu.wohlben.qits.projects.control.DownstreamPins;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The suite's {@link DownstreamPins}: an ordinary bean, which beats the {@code @DefaultBean} {@link
 * HttpDownstreamPins}, so no test dials qits-maintenance. <b>It answers "could not ask" until a test
 * scripts it</b>, which is the posture every suite not about contract changes runs in: no contract
 * requirement opens.
 */
@ApplicationScoped
public class FakeDownstreamPins implements DownstreamPins {

  private final Map<String, List<Downstream>> downstream = new ConcurrentHashMap<>();

  private final Map<String, List<Pin>> pins = new ConcurrentHashMap<>();

  /** {@code repoId}'s closure answers {@code entries}. */
  public void downstream(String repoId, List<Downstream> entries) {
    downstream.put(repoId, List.copyOf(entries));
  }

  /** {@code repository} pins {@code held}. */
  public void pins(String repository, List<Pin> held) {
    pins.put(repository, List.copyOf(held));
  }

  public void reset() {
    downstream.clear();
    pins.clear();
  }

  @Override
  public Optional<List<Downstream>> downstreamOf(String repoId) {
    return Optional.ofNullable(downstream.get(repoId));
  }

  @Override
  public Optional<List<Pin>> pinsOf(String repository) {
    return Optional.ofNullable(pins.get(repository));
  }
}
