package eu.wohlben.qits.projects.control;

import java.util.Optional;

/**
 * Reading an external MCP server's credential out of qits-configuration — the one hop this feature
 * makes to another context, as a port.
 *
 * <p>A port rather than an HTTP client inline because the two callers want different things from
 * the same read and both have to be testable without standing up qits-configuration: the catalog's
 * write door asks whether a key <em>exists</em>, so a mistyped reference is a 400 the operator reads
 * at the form; the document build asks for the <em>value</em>, once, when a container is created.
 *
 * <p><b>Absent is never "no credential".</b> Every answer here is an {@link Optional} that is empty
 * when the value could not be read — unset address, unreachable service, refused, absent key — and
 * the document build turns an empty into a failure naming the key. A server attached with a missing
 * credential must not render as an unauthenticated server: that failure surfaces as an agent whose
 * first tool call 401s, three hours later, with nothing pointing at the configuration.
 *
 * <p><b>Nothing that passes through here may be logged.</b> The implementations log the key, the
 * status code and the failure, and never the value; the same rule holds at every caller, and it is
 * why the value never enters an exception message either.
 */
public interface McpCredentials {

  /**
   * The value behind a qits-configuration key under the reserved {@code qits-agent-mcp} application,
   * or empty when it could not be read.
   *
   * @param key an {@code env.<VAR>} key; see {@link AgentMcpCatalog} for why that is the grammar
   */
  Optional<String> value(String key);

  /**
   * Whether a key can be read at all — the catalog's write-time check.
   *
   * <p>Empty is "could not ask", which is a third answer and not a no: a catalog write may not be
   * refused because qits-configuration was briefly unreachable, and it may not be accepted on the
   * strength of a reference nobody confirmed. The caller decides; see {@code AgentMcpCatalogService}.
   */
  Optional<Boolean> exists(String key);
}
