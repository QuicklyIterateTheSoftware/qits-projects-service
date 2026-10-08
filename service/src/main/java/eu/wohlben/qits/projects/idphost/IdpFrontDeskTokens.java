package eu.wohlben.qits.projects.idphost;

import eu.wohlben.qits.projects.deskhost.FrontDeskTokens;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link FrontDeskTokens} of a deployment with an idp (qits-767): qits-idp's token API through
 * {@link IdpTokens}, authenticated with this service's own client. {@code @DefaultBean}, so a
 * suite's stand-in wins the injection.
 */
@ApplicationScoped
@DefaultBean
public class IdpFrontDeskTokens implements FrontDeskTokens {

  @Inject IdpTokens tokens;

  @Override
  public Minted mint(String projectId) {
    IdpTokens.Issued issued =
        tokens.commission(CONTEXT_KIND, projectId, Map.of(IdpCommissionWire.PROJECT_CLAIM, projectId),
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
                    .map(t -> new Live(t.tokenId(), t.contextKind(), t.contextId(), t.createdAt()))
                    .toList());
  }
}
