package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * A drift guard over the one address this service HANDS OUT rather than dials.
 *
 * <p>{@code qits.projects.own-host} is composed into {@code QITS_PROJECTS_DAEMON_URL} and {@code
 * QITS_REPOSITORY_MCP_URL} at container creation, and a container's environment is frozen at
 * creation. So every agent and every refinement container carries whatever this value was at the
 * moment it was created, for its whole life, and the daemon inside it dials that verbatim.
 *
 * <p><b>Why a test and not a code review.</b> This value read {@code qits-projects} — a bare wire
 * alias — for as long as the platform plane existed, and kept reading it after the plane was deleted
 * and every service moved to {@code <env>-<app>}. Nothing reported it, because the DEPLOYED value
 * was right: the bootstrap writes {@code QITS_PROJECTS_OWN_HOST=<env>-qits-projects} into
 * qits-configuration and the entry beats the default. What was wrong was the default underneath, and
 * a default is only reached when the entry is missing — a fresh tier, a boot that runs before the
 * extras land, a process started by hand. Which is to say: it was wrong exactly where nobody was
 * looking.
 *
 * <p><b>And the failure would not have surfaced either.</b> The victim is a container, and the
 * workspace daemon's provisioner is built never to exit on failure, so a dead authority is a
 * reconnect loop in a log nobody reads rather than a crash anybody sees. That is the same shape as
 * the idp advertising a {@code jwks_uri} on a host that had gone — invisible to every warm consumer,
 * fatal to every cold one — and the same shape as {@code qits events} looping in every agent
 * container against a bare alias its CLI still held.
 *
 * <p>It asserts the value RESOLVED rather than the expression, because what has to be right is the
 * string a container is handed; asserting {@code ${QITS_ENVIRONMENT:dev}-qits-projects} would pass
 * on a file whose expression no longer expands.
 */
@QuarkusTest
class OwnHostConfigTest {

  private static final String KEY = "qits.projects.own-host";

  @Inject Config config;

  @Test
  void theAddressHandedToEveryContainerCarriesItsTier() {
    // No QITS_ENVIRONMENT under test, so the shipped default resolves its own `dev` arm. The point
    // is the SHAPE: a tier, a dash, and the application.
    assertEquals("dev-qits-projects", config.getValue(KEY, String.class));
  }

  @Test
  void theBareWireAliasIsNotAnAnswerHere() {
    // Named as its own case because this is the regression, not a rounding error. `qits-projects`
    // resolves nowhere on this platform: the plane that answered bare aliases is deleted. A future
    // edit that drops the tier would still produce a plausible-looking hostname, which is why the
    // absence is asserted rather than left implied by the equality above.
    String value = config.getValue(KEY, String.class);
    assertEquals(
        "dev-qits-projects",
        value,
        "the address handed to a container must carry its tier — see the class javadoc");
    assertFalse(
        value.equals("qits-projects"),
        "`qits-projects` is the bare wire alias of the deleted platform plane and resolves nowhere");
  }
}
