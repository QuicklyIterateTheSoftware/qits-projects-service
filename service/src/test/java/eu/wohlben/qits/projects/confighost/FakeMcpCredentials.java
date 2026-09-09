package eu.wohlben.qits.projects.confighost;

import eu.wohlben.qits.projects.control.McpCredentials;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The suite's {@link McpCredentials}: an in-memory qits-configuration.
 *
 * <p>It wins over {@code confighost/HttpMcpCredentials} for free — that bean is {@code @DefaultBean},
 * so any other bean of the type simply takes the injection, exactly as {@code
 * agenthost/FakeContainerRuntime} does. No idp, no peer service, no network, which is what keeps
 * {@code ./mvnw verify} green from a clone of this repository alone.
 *
 * <p><b>It models all three answers, because the third one is the interesting one.</b> A key that is
 * present answers its value; a key that is absent answers "asked, not there" ({@code
 * Optional.of(false)} from {@code exists}); and {@link #unreachable} makes every read answer "could
 * not ask" ({@code Optional.empty()} from both). The catalog's write door treats the last two
 * differently on purpose — a confirmed-missing reference is a 400, an unconfirmable one is a warning
 * — and a fake that could not tell them apart would let that distinction rot.
 */
@ApplicationScoped
public class FakeMcpCredentials implements McpCredentials {

  private final Map<String, String> values = new LinkedHashMap<>();

  private boolean unreachable;

  /** Put a key in the store, as if somebody had set it in qits-configuration. */
  public void set(String key, String value) {
    values.put(key, value);
  }

  /** Make every read answer "could not ask", the way an unset address or an outage does. */
  public void unreachable(boolean value) {
    this.unreachable = value;
  }

  /** Back to an empty, reachable store. */
  public void reset() {
    values.clear();
    unreachable = false;
  }

  @Override
  public Optional<String> value(String key) {
    if (unreachable) {
      return Optional.empty();
    }
    return Optional.ofNullable(values.get(key));
  }

  @Override
  public Optional<Boolean> exists(String key) {
    if (unreachable) {
      return Optional.empty();
    }
    return Optional.of(values.containsKey(key) && !values.get(key).isBlank());
  }
}
