package eu.wohlben.qits.projects.idphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.refinementhost.RefinementTokens;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What a refinement room's token is commissioned as (qits-1482): kind {@code refinement}, the row
 * id as its context, claiming its project and its entity, allowed to push nothing — and the listing
 * the reconcile reads holds that kind alone. Plain JUnit over a fake {@link IdpTokens} that records
 * the commission instead of sending it, as {@code IdpFrontDeskTokensTest} does.
 */
class IdpRefinementTokensTest {

  private static final String PROJECT = "11111111-2222-3333-4444-555555555555";
  private static final String ENTITY = "66666666-7777-8888-9999-000000000000";

  /** One commission, as {@link IdpRefinementTokens} asked for it. */
  record Commission(String kind, String contextId, Map<String, String> claims, List<String> gitRefs) {}

  /** A fake qits-idp: records every commission and delete, answers a fixed token and listing. */
  static final class FakeIdpTokens extends IdpTokens {

    final List<Commission> commissions = new ArrayList<>();

    final List<String> deleted = new ArrayList<>();

    FakeIdpTokens() {
      super(QitsHttpClients.of(OpenTelemetry.noop(), null));
    }

    @Override
    public Issued commission(
        String contextKind, String contextId, Map<String, String> claims, List<String> gitRefs) {
      commissions.add(new Commission(contextKind, contextId, claims, gitRefs));
      return new Issued("t-" + commissions.size(), "qits_tok_" + commissions.size(), "tok-sub");
    }

    @Override
    public boolean delete(String tokenId) {
      deleted.add(tokenId);
      return true;
    }

    @Override
    public Optional<List<LiveToken>> list() {
      Instant at = Instant.parse("2026-10-22T10:00:00Z");
      return Optional.of(
          List.of(
              new LiveToken("t-r", "refinement", "42", at),
              new LiveToken("t-d", "agent-container", PROJECT, at)));
    }
  }

  private final FakeIdpTokens idp = new FakeIdpTokens();

  private IdpRefinementTokens tokens() {
    IdpRefinementTokens tokens = new IdpRefinementTokens();
    tokens.tokens = idp;
    return tokens;
  }

  @AfterEach
  void close() {
    idp.close();
  }

  @Test
  void aRefinementTokenIsOneCommissionOfKindRefinementClaimingProjectAndEntity() {
    RefinementTokens.Minted minted = tokens().mint(42L, PROJECT, ENTITY);

    assertEquals(
        List.of(
            new Commission(
                "refinement", "42", Map.of("project", PROJECT, "entity", ENTITY), List.of())),
        idp.commissions);
    assertEquals("t-1", minted.tokenId());
    assertEquals("qits_tok_1", minted.token());

    assertTrue(tokens().revoke("t-1"));
    assertEquals(List.of("t-1"), idp.deleted);
  }

  @Test
  void theListingHoldsRefinementTokensOnly() {
    assertEquals(
        Optional.of(
            List.of(
                new RefinementTokens.Live("t-r", "42", Instant.parse("2026-10-22T10:00:00Z")))),
        tokens().list());
  }
}
