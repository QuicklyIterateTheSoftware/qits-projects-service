package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * An external MCP server <b>fully rendered</b> — the one shape that carries a credential's value,
 * and the only one.
 *
 * <p>It appears in exactly one place: {@link AgentDocumentSurfaceDto} inside the document a container
 * is created with. That is what "the container needs no second lookup" means — the container gets a
 * url and a header value and calls the server, rather than holding a reference it would have to
 * resolve against qits-configuration from inside a workspace.
 *
 * <p><b>{@link #headerValue} is the reason this record is separate from {@link
 * AgentMcpCatalogEntryDto}.</b> The catalog record goes into the editor's answers, into revision
 * snapshots and into the OpenAPI document; this one goes into a file mounted into a container and
 * into nothing else. Keeping them as one record with a sometimes-null field would have made every
 * one of those paths one mistake away from publishing a token. There is no {@code toString} override
 * to sanitise here for the same reason there is no logging of it: nothing in this service logs this
 * record, and the rule that nothing may is written down beside every caller.
 *
 * <p>A reference that could not be resolved never reaches this record — the document build fails
 * loudly and names the key. A server attached with a missing credential must not render as an
 * unauthenticated server that 401s on the agent's first tool call, because that failure surfaces as
 * a confused agent hours later rather than as an error anybody reads.
 *
 * @param key the key this server renders under
 * @param url the http(s) URL, verbatim from the catalog
 * @param headerName the header to send; empty when the server takes no credential
 * @param headerValue that header's value, resolved from qits-configuration; empty exactly when
 *     {@code headerName} is
 * @param allowedTools the tools pre-approved on this server
 */
public record AgentResolvedMcpServerDto(
    String key, String url, String headerName, String headerValue, List<String> allowedTools) {}
