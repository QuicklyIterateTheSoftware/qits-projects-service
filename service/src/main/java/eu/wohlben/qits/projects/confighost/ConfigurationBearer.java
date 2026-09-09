package eu.wohlben.qits.projects.confighost;

import java.util.Optional;

/**
 * The machine credential this service presents to qits-configuration, as a seam.
 *
 * <p>An interface rather than the {@code OidcClient} inline, for the reason {@code
 * maintenancehost/MaintenanceBearer} gives one package over: it is the one part of {@link
 * HttpMcpCredentials} that cannot be pointed at a local stub server, so a wire test would otherwise
 * have to boot an idp to assert a request shape.
 *
 * <p><b>Empty here is stricter than at the neighbouring hops, and deliberately so.</b> The other
 * outbound reads in this service fall back to the forwarded {@code X-Qits-*} header pair when the
 * named client is off, because their far side is a read that decides nothing. This one reads a
 * credential, and both of qits-configuration's entry routes take {@code qits:admin} or {@code
 * qits:system} — a forwarded pair from a machine-driven document build carries neither honestly. So
 * an empty bearer means the read is not attempted, and the document build fails naming the key
 * rather than handing a container a server with no credential.
 */
public interface ConfigurationBearer {

  /** {@code Bearer <token>}, or empty when this hop has no credential to present. */
  Optional<String> authorization();
}
