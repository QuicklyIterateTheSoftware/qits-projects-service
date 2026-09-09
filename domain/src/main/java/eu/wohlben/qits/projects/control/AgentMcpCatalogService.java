package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.AgentMcpCatalogEntryDto;
import eu.wohlben.qits.projects.dto.AgentResolvedMcpServerDto;
import eu.wohlben.qits.projects.entity.AgentMcpCatalogEntry;
import eu.wohlben.qits.projects.entity.AgentSurfaceExternalMcpAttachment;
import eu.wohlben.qits.projects.entity.AgentSurfaceMcpAttachment;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.InternalServerErrorException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.AgentMcpCatalogEntryRepository;
import eu.wohlben.qits.projects.persistence.AgentSurfaceExternalMcpAttachmentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The external MCP server catalog: defined once platform-wide, attached per surface, credentials by
 * reference.
 *
 * <p><b>Three validations on write, each one closing a failure that is otherwise silent.</b> The key
 * may not be one of the three reserved built-ins, or the entry would displace a platform server in
 * the rendered {@code mcpServers} object and the session would look normal while talking to somebody
 * else's server. The URL must be http(s), because that is the only transport both harnesses can
 * carry. And the referenced qits-configuration key must exist, so a mistyped reference is a 400 at
 * the form rather than a failed container provision later.
 *
 * <p><b>The third one has a third answer, and it matters.</b> {@link McpCredentials#exists} is empty
 * when qits-configuration could not be asked — the address unset, the service unreachable — which is
 * neither "the key is there" nor "the key is missing". A write is <em>accepted</em> in that case,
 * with a WARN, because refusing would make an unrelated outage look like a validation error and
 * would make the catalog unusable on a topology that has not configured the hop. What is <b>not</b>
 * lenient is {@link #resolve}: a reference that cannot be resolved when a document is built fails
 * that build loudly and names the key, which is the gate that actually protects a running agent.
 *
 * <p><b>Nothing here logs a header value, and nothing may.</b> The value is read once, in {@link
 * #resolve}, straight into {@code AgentResolvedMcpServerDto}, which travels only into the document a
 * container is mounted with. It is not stored, not put in a revision snapshot, not in an exception
 * message and not in a log line — a failure names the <em>key</em>, which is the part that is safe
 * and the part that is useful.
 */
@ApplicationScoped
public class AgentMcpCatalogService {

  private static final Logger LOG = Logger.getLogger(AgentMcpCatalogService.class);

  @Inject AgentMcpCatalogEntryRepository entries;

  @Inject AgentSurfaceExternalMcpAttachmentRepository attachments;

  /**
   * The credential hop, {@code Instance} like every other port out of this module.
   *
   * <p>Absent is a supported configuration and reads as the third answer this service already has to
   * handle: <b>"could not ask"</b>. That is what {@code domain}'s own suite runs as — the adapter
   * lives in {@code service/…/confighost/} and this module has no HTTP client at all — and it is
   * also what a topology with no qits-configuration wired is. It never means "no credential": a
   * catalog write is stored unverified with a WARN, and {@link #resolve} still fails the document
   * build loudly, which is the gate that protects a running agent.
   */
  @Inject Instance<McpCredentials> credentials;

  // -------------------------------------------------------------------------------------------
  // Reads
  // -------------------------------------------------------------------------------------------

  /** Every catalog entry, by key, each saying which surfaces attach it. */
  public List<AgentMcpCatalogEntryDto> listAll() {
    Map<String, List<String>> attachedBy = new LinkedHashMap<>();
    for (AgentSurfaceExternalMcpAttachment attachment : attachments.allOrdered()) {
      attachedBy
          .computeIfAbsent(attachment.catalogKey, key -> new ArrayList<>())
          .add(attachment.surfaceKey);
    }
    return entries.allOrdered().stream()
        .map(entry -> toDto(entry, attachedBy.getOrDefault(entry.catalogKey, List.of())))
        .toList();
  }

  /** One entry, or a 404 — unlike a surface, a catalog entry that does not exist is not a default. */
  public AgentMcpCatalogEntryDto get(String catalogKey) {
    AgentMcpCatalogEntry entry = entries.findById(requireKey(catalogKey));
    if (entry == null) {
      throw new NotFoundException("No MCP catalog entry: " + catalogKey);
    }
    return toDto(entry, surfacesAttaching(entry.catalogKey));
  }

  /** The entries a surface attaches, in attachment order, by reference — never with a value. */
  public List<AgentMcpCatalogEntryDto> attachedTo(String surfaceKey) {
    List<AgentMcpCatalogEntryDto> answer = new ArrayList<>();
    for (AgentSurfaceExternalMcpAttachment attachment : attachments.forSurface(surfaceKey)) {
      AgentMcpCatalogEntry entry = entries.findById(attachment.catalogKey);
      if (entry == null) {
        // A catalog entry cannot be deleted while a surface attaches it, so this is a row that
        // outlived its entry through a hand edit or a restore. Skip it rather than fail the read:
        // an editor that cannot open a surface is worse than one missing a server nobody can find.
        LOG.warnf(
            "Surface %s attaches MCP catalog entry %s, which no longer exists; ignoring it.",
            attachment.surfaceKey, attachment.catalogKey);
        continue;
      }
      answer.add(toDto(entry, List.of()));
    }
    return List.copyOf(answer);
  }

  /**
   * The entries a surface attaches, <b>fully rendered</b> — this is where a credential is read, and
   * the only place.
   *
   * <p>Called when a container's document is built and never on the editor's path. An unresolvable
   * reference throws, naming the key and the surface: the document build fails rather than handing a
   * container a server it cannot authenticate to, because that server would 401 on the agent's first
   * tool call and nothing in the failure would point back here.
   */
  public List<AgentResolvedMcpServerDto> resolve(String surfaceKey) {
    List<AgentResolvedMcpServerDto> answer = new ArrayList<>();
    for (AgentMcpCatalogEntryDto entry : attachedTo(surfaceKey)) {
      String headerValue = "";
      if (!entry.credentialKey().isBlank()) {
        Optional<String> value =
            credentials.isResolvable()
                ? credentials.get().value(entry.credentialKey())
                : Optional.empty();
        if (value.isEmpty() || value.get().isBlank()) {
          throw new InternalServerErrorException(
              "Could not resolve the credential for MCP server `"
                  + entry.key()
                  + "` on surface `"
                  + surfaceKey
                  + "`: qits-configuration key `"
                  + entry.credentialKey()
                  + "` under application `"
                  + AgentMcpCatalog.CREDENTIAL_APPLICATION
                  + "` is missing, empty or unreadable. The document is not built: a server"
                  + " attached with no credential would render as an unauthenticated server and"
                  + " 401 on the agent's first tool call.");
        }
        headerValue = value.get();
      }
      answer.add(
          new AgentResolvedMcpServerDto(
              entry.key(), entry.url(), entry.headerName(), headerValue, entry.allowedTools()));
    }
    return List.copyOf(answer);
  }

  // -------------------------------------------------------------------------------------------
  // Writes
  // -------------------------------------------------------------------------------------------

  /**
   * Create or replace one catalog entry.
   *
   * <p>A replacement rather than a patch, like every write in this feature: an entry is small, and a
   * partial edit of a url-plus-credential pair is not a state anybody asked for.
   */
  @Transactional
  public AgentMcpCatalogEntryDto save(
      String catalogKey,
      String displayName,
      String url,
      String headerName,
      String credentialKey,
      List<String> allowedTools,
      String changedBy) {
    String key = AgentMcpCatalog.requireCatalogKey(catalogKey);
    String header = AgentMcpCatalog.requireHeaderName(headerName);
    String reference = credentialKey == null ? "" : credentialKey.trim();
    AgentMcpCatalog.requireCredentialPair(header, reference);
    String checkedUrl = AgentMcpCatalog.requireUrl(url);
    String name = AgentMcpCatalog.requireDisplayName(displayName);
    if (!reference.isBlank()) {
      requireReferenceResolvable(key, reference);
    }

    Instant now = Instant.now();
    AgentMcpCatalogEntry entry = entries.findById(key);
    boolean fresh = entry == null;
    if (fresh) {
      entry = new AgentMcpCatalogEntry();
      entry.catalogKey = key;
      entry.createdAt = now;
    }
    entry.displayName = name;
    entry.url = checkedUrl;
    entry.headerName = header;
    entry.credentialKey = reference;
    entry.allowedTools =
        AgentSurfaceMcpAttachment.encodeTools(allowedTools == null ? List.of() : allowedTools);
    entry.updatedBy = changedBy;
    entry.updatedAt = now;
    if (fresh) {
      entries.persist(entry);
    }
    entries.flush();
    return toDto(entry, surfacesAttaching(key));
  }

  /**
   * Remove one entry — refused while any surface still attaches it, naming those surfaces.
   *
   * <p>The refusal is why {@code agent_surface_external_mcp_attachment.catalog_key} carries no
   * foreign key: an FK would turn this into a constraint violation surfacing as a 500, and the
   * message an operator can act on would be unreachable.
   */
  @Transactional
  public void delete(String catalogKey) {
    String key = requireKey(catalogKey);
    if (entries.findById(key) == null) {
      throw new NotFoundException("No MCP catalog entry: " + key);
    }
    List<String> attached = surfacesAttaching(key);
    if (!attached.isEmpty()) {
      throw new BadRequestException(
          "`"
              + key
              + "` is still attached by "
              + String.join(", ", attached)
              + ". Detach it from those surfaces first — deleting it here would leave them naming a"
              + " server that does not exist.");
    }
    entries.deleteById(key);
  }

  /**
   * The keys that exist, for the surface editor's validation of an attachment.
   *
   * <p>Kept here rather than read through {@link #listAll} at the call site so that the surface
   * service asks one question and gets one answer.
   */
  public List<String> knownKeys() {
    return entries.allOrdered().stream().map(entry -> entry.catalogKey).toList();
  }

  // -------------------------------------------------------------------------------------------
  // Plumbing
  // -------------------------------------------------------------------------------------------

  /**
   * Confirm the reference before the entry is stored, with the third answer handled honestly.
   *
   * <p>A confirmed-missing key is a 400 naming it and the application it should live under — the
   * whole reason this check is at the form. "Could not ask" is a WARN and the write proceeds:
   * refusing would dress an outage of another service up as a validation error, and would make the
   * catalog unusable wherever the hop is unconfigured. The gate that protects a running agent is
   * {@link #resolve}, which is strict.
   */
  private void requireReferenceResolvable(String catalogKey, String reference) {
    Optional<Boolean> exists =
        credentials.isResolvable() ? credentials.get().exists(reference) : Optional.empty();
    if (exists.isEmpty()) {
      LOG.warnf(
          "Could not confirm qits-configuration key %s (application %s) for MCP catalog entry %s;"
              + " storing the reference unverified. Building a container's document will fail"
              + " loudly if it is still unresolvable then.",
          reference, AgentMcpCatalog.CREDENTIAL_APPLICATION, catalogKey);
      return;
    }
    if (!exists.get()) {
      throw new BadRequestException(
          "qits-configuration holds no key `"
              + reference
              + "` under application `"
              + AgentMcpCatalog.CREDENTIAL_APPLICATION
              + "`. Create it there first — this catalog stores a reference and never the value, so"
              + " an entry pointing at nothing would fail every container provision that attaches"
              + " it.");
    }
  }

  private List<String> surfacesAttaching(String catalogKey) {
    return attachments.forCatalogKey(catalogKey).stream()
        .map(attachment -> attachment.surfaceKey)
        .distinct()
        .toList();
  }

  static AgentMcpCatalogEntryDto toDto(AgentMcpCatalogEntry entry, List<String> attachedBy) {
    return new AgentMcpCatalogEntryDto(
        entry.catalogKey,
        entry.displayName,
        entry.url,
        entry.headerName,
        entry.credentialKey,
        entry.allowedToolList(),
        attachedBy);
  }

  private static String requireKey(String catalogKey) {
    if (catalogKey == null || catalogKey.isBlank()) {
      throw new BadRequestException("A catalog key is required");
    }
    return catalogKey.trim();
  }
}
