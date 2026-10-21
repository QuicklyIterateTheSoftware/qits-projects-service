package eu.wohlben.qits.projects.idphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.deskhost.FrontDeskTokens;
import io.opentelemetry.api.OpenTelemetry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What a desk's two tokens are commissioned as (qits-767, epic qits-1440): the desk token is kind
 * {@code agent-container} and the delivery token kind {@code desk-delivery}, both for the project,
 * claiming it, and allowed to push nothing. Plain JUnit over a fake {@link IdpTokens} that records
 * the commission instead of sending it.
 */
class IdpFrontDeskTokensTest {

  private static final String PROJECT = "11111111-2222-3333-4444-555555555555";

  /** One commission, as {@link IdpFrontDeskTokens} asked for it. */
  record Commission(String kind, String contextId, Map<String, String> claims, List<String> gitRefs) {}

  /** A fake qits-idp: records every commission and delete, answers a fixed token. */
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
  }

  private final FakeIdpTokens idp = new FakeIdpTokens();

  private IdpFrontDeskTokens tokens() {
    IdpFrontDeskTokens tokens = new IdpFrontDeskTokens();
    tokens.tokens = idp;
    return tokens;
  }

  @AfterEach
  void close() {
    idp.close();
  }

  @Test
  void theDeliveryTokenIsOneCommissionOfKindDeskDelivery() {
    FrontDeskTokens.Minted minted = tokens().mintDelivery(PROJECT);

    assertEquals(
        List.of(new Commission("desk-delivery", PROJECT, Map.of("project", PROJECT), List.of())),
        idp.commissions);
    assertEquals(FrontDeskTokens.CONTEXT_KIND_DELIVERY, idp.commissions.get(0).kind());
    assertEquals("t-1", minted.tokenId());
    assertEquals("qits_tok_1", minted.token());
    assertEquals("tok-sub", minted.subject());

    assertTrue(tokens().revokeDelivery("t-1"));
    assertEquals(List.of("t-1"), idp.deleted);
  }

  @Test
  void theDeskTokenIsStillKindAgentContainer() {
    tokens().mint(PROJECT);

    assertEquals(
        List.of(new Commission("agent-container", PROJECT, Map.of("project", PROJECT), List.of())),
        idp.commissions);
  }
}
