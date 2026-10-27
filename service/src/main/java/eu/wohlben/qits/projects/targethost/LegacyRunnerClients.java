package eu.wohlben.qits.projects.targethost;

import eu.wohlben.qits.projects.idphost.IdpClients;
import eu.wohlben.qits.projects.idphost.IdpTokens;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The retirement's boot one-shot (epic qits-1481, qits-1551): gives back every idp client and token
 * of the retired front-desk runner's kinds, {@code desk-runner} and {@code
 * desk-runner-registration}. Without its client the old runner stops dialling — the toolkit's
 * {@code ControlSocket} gives up on a sustained {@code invalid_client} — and its registration tokens
 * can install nothing any more.
 *
 * <p><b>It only ever gives credentials back.</b> It sends no runner anything — no {@code
 * Retire{DELETED}}, no 1008 {@code RUNNER_DELETED} close, on which the runner toolkit purges every
 * desk container and volume it holds (qits-1592: the workspace cutover's runner did exactly that
 * on 2026-10-24). The runner socket, its registry and the protocol those would travel on are gone
 * from this service in the same release, so nothing can; {@code LegacyRunnerClientsTest} holds
 * both. The fixed toolkit stops without purging on a revoked credential.
 *
 * <p><b>Idempotent, and at every boot</b> (packaged runs only, off the startup path). It deletes
 * only what qits-idp lists for this service under those two kinds, so a second run finds nothing. A
 * listing that could not be read answers empty and deletes nothing; a failed delete is a WARN: the
 * next boot asks again. The kinds themselves go from qits-idp in a later release.
 */
@ApplicationScoped
public class LegacyRunnerClients {

  private static final Logger LOG = Logger.getLogger(LegacyRunnerClients.class);

  /** The retired front-desk runner's own client. */
  static final String RUNNER_KIND = "desk-runner";

  /** Its one-use registration token. */
  static final String REGISTRATION_KIND = "desk-runner-registration";

  static final Set<String> RETIRED_KINDS = Set.of(RUNNER_KIND, REGISTRATION_KIND);

  @Inject IdpClients idp;

  void onStart(@Observes StartupEvent start) {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    Thread.ofVirtual().name("qits-legacy-runner-clients").start(this::giveBack);
  }

  /** One pass; answers how many credentials it gave back. Never throws. */
  public int giveBack() {
    int given = 0;
    try {
      for (String kind : List.of(RUNNER_KIND, REGISTRATION_KIND)) {
        for (IdpClients.LiveClient client : idp.liveClients(kind).orElse(List.of())) {
          if (idp.deleteClient(client.clientId())) {
            LOG.infof(
                "Gave back retired runner client %s (%s, context %s)",
                client.clientId(), client.contextKind(), client.contextId());
            given++;
          }
        }
      }
    } catch (RuntimeException e) {
      LOG.warnf("Could not give back the retired runner clients; the next boot will: %s", e.toString());
    }
    try {
      for (IdpTokens.LiveToken token : idp.liveTokens().orElse(List.of())) {
        if (token.tokenId() == null || !RETIRED_KINDS.contains(token.contextKind())) {
          continue;
        }
        if (idp.deleteToken(token.tokenId())) {
          LOG.infof(
              "Deleted retired runner token %s (%s, context %s)",
              token.tokenId(), token.contextKind(), token.contextId());
          given++;
        }
      }
    } catch (RuntimeException e) {
      LOG.warnf("Could not delete the retired runner tokens; the next boot will: %s", e.toString());
    }
    return given;
  }
}
