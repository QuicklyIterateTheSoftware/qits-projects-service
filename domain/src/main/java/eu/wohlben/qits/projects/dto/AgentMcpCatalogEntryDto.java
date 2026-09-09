package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * One external MCP server as the catalog holds it — <b>reference only, never the credential's
 * value</b>.
 *
 * <p>This is what the catalog route lists and what a surface's configuration names when it attaches
 * a server. It carries the qits-configuration key the header's value lives behind and never the
 * value itself: this record is serialized into revision snapshots, into the editor's answers and
 * into the OpenAPI document, and a header value has no business in any of the three. The value is
 * read once, when a container's document is built, into {@link AgentResolvedMcpServerDto} and
 * nowhere else.
 *
 * <p>{@code headerName} and {@code credentialKey} are empty together or set together. A header with
 * no key and a key with no header are both refused on write — either is a server that will 401 on
 * the agent's first tool call with nothing in the configuration to say why.
 *
 * @param key the key this server renders under; never one of the reserved built-in three
 * @param displayName what an operator reads; never rendered into a command
 * @param url an http(s) URL — the only transport both harnesses can carry
 * @param headerName the header the credential is presented in; empty when the server takes none
 * @param credentialKey the qits-configuration key holding that header's value, under the reserved
 *     {@code qits-agent-mcp} application; empty when the server takes no credential
 * @param allowedTools the tools pre-approved on this server — operator-editable, unlike the
 *     built-ins' shipped lists, because the platform ships no constant for a server it never heard of
 * @param attachedBy the surfaces attaching this entry, so a delete can be refused with a reason; an
 *     empty list on a write body, filled in on a read
 */
public record AgentMcpCatalogEntryDto(
    String key,
    String displayName,
    String url,
    String headerName,
    String credentialKey,
    List<String> allowedTools,
    List<String> attachedBy) {}
