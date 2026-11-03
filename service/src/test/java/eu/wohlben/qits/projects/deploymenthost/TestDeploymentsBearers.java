package eu.wohlben.qits.projects.deploymenthost;

import java.util.Optional;

/**
 * {@link IdpDeploymentsBearer}s for tests that build a qits-deployments hop by hand: one that always
 * presents {@link #TOKEN}, and the shipped default with the named client off, which presents none
 * (qits-1332: the hop then fails closed rather than sending a forwarded pair).
 */
public final class TestDeploymentsBearers {

  /** The {@code Authorization} value {@link #machine()} presents. */
  public static final String TOKEN = "Bearer machine-token";

  private TestDeploymentsBearers() {}

  /** A bearer that always presents {@link #TOKEN}. */
  public static IdpDeploymentsBearer machine() {
    return new IdpDeploymentsBearer() {
      @Override
      public Optional<String> authorization() {
        return Optional.of(TOKEN);
      }
    };
  }

  /** The named client off: {@code quarkus.oidc-client.qits.client-enabled} false. */
  public static IdpDeploymentsBearer none() {
    return new IdpDeploymentsBearer();
  }
}
