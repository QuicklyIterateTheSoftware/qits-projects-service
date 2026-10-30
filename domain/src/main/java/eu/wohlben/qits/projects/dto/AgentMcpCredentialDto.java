package eu.wohlben.qits.projects.dto;

import java.time.Instant;

/**
 * One external MCP credential <b>as the API answers it: by key, never by value</b>.
 *
 * <p>There is no value field here and there must never be one. The value is written through {@code
 * PUT /agent-mcp-catalog/credentials/{key}} and read back only into the document a container is
 * created with ({@code AgentResolvedMcpServerDto}).
 *
 * @param key the credential key, {@code env.<VAR>}, as a catalog entry's {@code credentialKey} names
 *     it
 * @param updatedAt when the value was last set
 * @param updatedBy the principal that set it
 */
public record AgentMcpCredentialDto(String key, Instant updatedAt, String updatedBy) {}
