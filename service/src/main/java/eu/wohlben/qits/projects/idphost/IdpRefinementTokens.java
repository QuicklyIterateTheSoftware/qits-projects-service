package eu.wohlben.qits.projects.idphost;

import eu.wohlben.qits.projects.refinementhost.RefinementTokens;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link RefinementTokens} of a deployment with an idp (qits-1482): qits-idp's commissioned-token
 * API through {@link IdpTokens} — the endpoint {@link IdpFrontDeskTokens} uses — authenticated with
 * this service's own client. {@code @DefaultBean}, so a suite's stand-in wins the injection.
 */
@ApplicationScoped
@DefaultBean
public class IdpRefinementTokens implements RefinementTokens {

  @Inject IdpTokens tokens;

  @Override
  public Minted mint(long refinementId, String projectId, String entityId) {
    IdpTokens.Issued issued =
        tokens.commission(
            CONTEXT_KIND,
            Long.toString(refinementId),
            Map.of(IdpCommissionWire.PROJECT_CLAIM, projectId, ENTITY_CLAIM, entityId),
            IdpCommissionWire.NO_GIT_REFS);
    return new Minted(issued.tokenId(), issued.token(), issued.subject());
  }

  @Override
  public boolean revoke(String tokenId) {
    return tokens.delete(tokenId);
  }

  @Override
  public Optional<List<Live>> list() {
    return tokens
        .list()
        .map(
            live ->
                live.stream()
                    .filter(t -> CONTEXT_KIND.equals(t.contextKind()))
                    .map(t -> new Live(t.tokenId(), t.contextId(), t.createdAt()))
                    .toList());
  }
}
