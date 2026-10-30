package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.AgentMcpCredentialDto;
import eu.wohlben.qits.projects.entity.AgentMcpCredential;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.AgentMcpCredentialRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The external MCP catalog's credential store: this service's own {@code agent_mcp_credential}
 * table, and the one {@link McpCredentials} there is (qits-1394).
 *
 * <p><b>Write-only over the API.</b> {@link #set} and {@link #delete} back the catalog's credential
 * doors; {@link #list} answers keys and who set them, never a value. The value leaves this class
 * through {@link #value} alone, which {@code AgentMcpCatalogService.resolve} calls when a container's
 * document is built. Nothing here logs, and no exception message carries a value.
 *
 * <p><b>The key grammar is the catalog's</b> ({@link AgentMcpCatalog#requireCredentialKey}): a key
 * that could not be referenced by an entry cannot be set, so a credential can never sit here under a
 * name nothing can point at.
 */
@ApplicationScoped
public class AgentMcpCredentialStore implements McpCredentials {

  /** Generous for a bearer or an API key, and a bound on what one request can write. */
  static final int MAX_VALUE = 16 * 1024;

  @Inject AgentMcpCredentialRepository credentials;

  /** Every credential, by key — without its value. */
  public List<AgentMcpCredentialDto> list() {
    return credentials.allOrdered().stream().map(AgentMcpCredentialStore::toDto).toList();
  }

  /**
   * Create or replace the value behind {@code key}. 400 on a key outside the grammar or a blank
   * value; the value is stored verbatim.
   */
  @Transactional
  public AgentMcpCredentialDto set(String key, String value, String changedBy) {
    String checked = AgentMcpCatalog.requireCredentialKey(key);
    if (value == null || value.isBlank()) {
      throw new BadRequestException(
          "A credential value is required. To remove `" + checked + "`, delete it instead.");
    }
    if (value.length() > MAX_VALUE) {
      throw new BadRequestException(
          "The credential value is longer than " + MAX_VALUE + " characters");
    }
    AgentMcpCredential row = credentials.findById(checked);
    boolean fresh = row == null;
    if (fresh) {
      row = new AgentMcpCredential();
      row.credentialKey = checked;
    }
    row.value = value;
    row.updatedAt = Instant.now();
    row.updatedBy = changedBy;
    if (fresh) {
      credentials.persist(row);
    }
    credentials.flush();
    return toDto(row);
  }

  /** Remove the value behind {@code key}, or a 404 when none is set. */
  @Transactional
  public void delete(String key) {
    String checked = AgentMcpCatalog.requireCredentialKey(key);
    if (!credentials.deleteById(checked)) {
      throw new NotFoundException("No MCP credential: " + checked);
    }
  }

  @Override
  public Optional<String> value(String key) {
    if (key == null || key.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(credentials.findById(key.trim()))
        .map(row -> row.value)
        .filter(value -> !value.isBlank());
  }

  @Override
  public boolean exists(String key) {
    return value(key).isPresent();
  }

  private static AgentMcpCredentialDto toDto(AgentMcpCredential row) {
    return new AgentMcpCredentialDto(row.credentialKey, row.updatedAt, row.updatedBy);
  }
}
