package eu.wohlben.qits.projects.maintenancehost;

import java.util.Optional;

/**
 * The machine credential this service presents to qits-maintenance, as a seam.
 *
 * <p>An interface rather than the {@code OidcClient} inline, for {@code workspacehost}'s reason one
 * package over: it is the one part of {@link HttpDownstreamComponents} that cannot be pointed at a
 * local stub server, so a wire test would otherwise have to boot an idp to assert a request shape. It
 * is <b>not</b> a port out of the domain — nothing in {@code domain} knows this hop exists, and a
 * bearer is infrastructure rather than a contract with another context.
 *
 * <p><b>Empty means the hop does not call.</b> The bearer is the only credential a hop to
 * qits-maintenance sends (owner ruling 2026-10-18, epic qits-543: no forwarded {@code X-Qits-*} pair
 * beside it or instead of it), so with none each hop fails closed, logs {@link #MISSING} and answers
 * its own failure contract, as {@code wiring/HttpGitHostRepositories} does.
 */
public interface MaintenanceBearer {

  /** What a hop logs when it has no bearer to present and therefore does not call. */
  String MISSING =
      "no machine bearer for qits-maintenance: the named OIDC client quarkus.oidc-client.qits is"
          + " disabled or minted no token, and this call is never sent without one";

  /** {@code Bearer <token>}, or empty when this hop has no credential to present. */
  Optional<String> authorization();
}
