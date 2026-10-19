package eu.wohlben.qits.http;

import io.opentelemetry.api.OpenTelemetry;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>{@link QitsHttpClients} that remembers every client it wraps, so a test can close them</b>
 * (ticket qits-1149). The consumer rows build a real client per row, and each real client builds
 * its own JDK {@link HttpClient}: a selector thread plus workers that live until the client is
 * garbage collected. Two hundred rows in one surefire fork piled them up past the gate's thread
 * limit ({@code OutOfMemoryError: unable to create native thread}). {@link #close} shuts down every
 * client this instance wrapped.
 *
 * <p>In {@code QitsHttpClients}' package for its package-private constructor; it wraps exactly as
 * {@link QitsHttpClients#of} with a no-op OpenTelemetry and no caller header does.
 */
public final class ClosableQitsHttpClients extends QitsHttpClients implements AutoCloseable {

  private final List<HttpClient> wrapped = new ArrayList<>();

  @Override
  public synchronized HttpClient wrap(HttpClient client) {
    wrapped.add(client);
    return QitsHttpClients.wrap(client, OpenTelemetry.noop(), null);
  }

  /** Shuts down every client wrapped so far, and forgets them. */
  @Override
  public synchronized void close() {
    for (HttpClient client : wrapped) {
      client.shutdownNow();
    }
    wrapped.clear();
  }
}
