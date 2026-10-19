package eu.wohlben.qits.projects.contracts.consumer;

import eu.wohlben.qits.http.ClosableQitsHttpClients;
import eu.wohlben.qits.http.QitsHttpClients;
import java.net.http.HttpClient;

/**
 * <b>The HTTP clients a consumer row's real client is built with</b>, closed after every row
 * (ticket qits-1149). A JDK {@link HttpClient} owns a selector thread and workers until it is
 * garbage collected; one per row across every row of a surefire fork exhausted the gate's threads.
 * So a row builds its client on {@link #clients()}, which remembers what it wraps, and {@link
 * #closeAll()} — called by whatever runs a row — shuts them down. A row that sends its own request
 * uses the one shared {@link #HTTP}.
 */
final class RowClients {

  /** For a row that sends a request itself rather than through a client of this service. */
  static final HttpClient HTTP = HttpClient.newHttpClient();

  private static final ClosableQitsHttpClients CLIENTS = new ClosableQitsHttpClients();

  private RowClients() {}

  /** What a row hands its client's constructor in place of {@code QitsHttpClients.of(...)}. */
  static QitsHttpClients clients() {
    return CLIENTS;
  }

  /** Shut down every client a row built since the last call. */
  static void closeAll() {
    CLIENTS.close();
  }
}
