package eu.wohlben.qits.projects.control;

import java.util.Optional;

/**
 * Reading an external MCP server's credential, as a port.
 *
 * <p>Implemented once, by {@link AgentMcpCredentialStore} over this service's own {@code
 * agent_mcp_credential} table (qits-1394). It used to be an HTTP hop to qits-configuration under the
 * reserved application {@code qits-agent-mcp}; that service is being removed, and the port stayed so
 * the two callers keep asking the two questions they ask: the catalog's write door asks whether a
 * key <em>exists</em>, so a mistyped reference is a 400 the operator reads at the form; the document
 * build asks for the <em>value</em>, once, when a container is created.
 *
 * <p><b>Absent is never "no credential".</b> The document build turns an empty {@link #value} into a
 * failure naming the key. A server attached with a missing credential must not render as an
 * unauthenticated server: that failure surfaces as an agent whose first tool call 401s, three hours
 * later, with nothing pointing at the configuration.
 *
 * <p><b>Nothing that passes through here may be logged.</b> An implementation logs the key and the
 * failure, never the value; the same rule holds at every caller, and it is why the value never
 * enters an exception message either.
 */
public interface McpCredentials {

  /**
   * The value behind a credential key, or empty when none is set.
   *
   * @param key an {@code env.<VAR>} key; see {@link AgentMcpCatalog} for the grammar
   */
  Optional<String> value(String key);

  /** Whether a non-blank value is set for the key — the catalog's write-time check. */
  boolean exists(String key);
}
