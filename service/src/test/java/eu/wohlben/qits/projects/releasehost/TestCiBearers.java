package eu.wohlben.qits.projects.releasehost;

import java.util.Optional;

/**
 * {@link IdpCiBearer}s for tests that build a qits-ci hop by hand: one that always
 * presents {@link #TOKEN}, and the shipped default with the named client off, which presents none
 * (qits-1332: the hop then fails closed rather than sending a forwarded pair).
 */
public final class TestCiBearers {

  /** The {@code Authorization} value {@link #machine()} presents. */
  public static final String TOKEN = "Bearer machine-token";

  private TestCiBearers() {}

  /** A bearer that always presents {@link #TOKEN}. */
  public static IdpCiBearer machine() {
    return new IdpCiBearer() {
      @Override
      public Optional<String> authorization() {
        return Optional.of(TOKEN);
      }
    };
  }

  /** The named client off: {@code quarkus.oidc-client.qits.client-enabled} false. */
  public static IdpCiBearer none() {
    return new IdpCiBearer();
  }
}
