package eu.wohlben.qits.projects.security;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Turns the shipped OIDC tenant <b>on</b> for one test class, with {@link MockIdp} standing in for
 * qits-platform-idp — the only {@code @QuarkusTest} posture in this repository where a machine
 * bearer is validated at all, and the reason {@link BearerJwksTest} can be a surefire test rather
 * than a third packaged IT.
 *
 * <p>It moves exactly three keys, and the shipped key path is untouched: discovery stays off,
 * {@code jwks-path} stays {@code jwks} (joined onto the address below, which is why the mock stubs
 * {@code /idp/jwks}), the audience stays the shipped {@code qits-platform}, and {@code
 * jwks.resolve-early} stays {@code false} — the thing under test.
 *
 * <ul>
 *   <li>{@code qits.auth.machine.required} is THE GATE: the shipped tenant is {@code
 *       quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}}, so this one key is the
 *       difference between a service that reads bearers and one that ignores them.
 *   <li>{@code quarkus.oidc.auth-server-url} is the one seam moved — where the idp is.
 *   <li>{@code qits.auth.forward.dev-user} is blanked, as {@link NoDevUserProfile} does it, so a
 *       request with no credential is really anonymous instead of the {@code %test} admin
 *       qits-auth-core hands every plain {@code given()}.
 * </ul>
 *
 * <p>The mock is started through {@link MockIdp#ensureStarted()} and reached again from the test
 * class through {@link MockIdp#attach()}, because a lifecycle manager and a test method need not
 * share a classloader — the same reason {@code TokenValidationBootstrapIT} does it that way. It is
 * <b>started before the application</b>, which is what makes "the boot fetched nothing" an
 * assertion about a reachable idp rather than about an absent one: the recordings are cleared here,
 * so everything counted afterwards was asked for by the process under test.
 *
 * <p>A {@code QuarkusTestResourceLifecycleManager} rather than a {@code QuarkusTestProfile} because
 * the mock has to exist before the overrides can name its port; {@code @WithTestResource} keeps it
 * restricted to the annotated class, so no other suite here gains a tenant.
 */
public class MockIdpTenant implements QuarkusTestResourceLifecycleManager {

  @Override
  public Map<String, String> start() {
    MockIdp idp = MockIdp.ensureStarted();
    idp.reset();
    return Map.of(
        "qits.auth.machine.required", "true",
        "quarkus.oidc.auth-server-url", idp.baseUrl(),
        "qits.auth.forward.dev-user", "");
  }

  @Override
  public void stop() {
    // ensureStarted() owns the server for the life of the JVM; a handle here owns nothing.
  }
}
