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
 * <p>Empty is a supported answer and does <b>not</b> mean "do not ask". The door at the far side is a
 * pure read that decides nothing, so the caller falls back to the forwarded {@code X-Qits-*} pair —
 * the posture the two qits-ci hops take, and what keeps a no-idp topology answering.
 */
public interface MaintenanceBearer {

  /** {@code Bearer <token>}, or empty when this hop has no credential to present. */
  Optional<String> authorization();
}
