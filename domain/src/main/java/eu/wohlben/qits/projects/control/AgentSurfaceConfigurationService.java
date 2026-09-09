package eu.wohlben.qits.projects.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.dto.AgentConfigurationDocumentDto;
import eu.wohlben.qits.projects.dto.AgentDocumentSurfaceDto;
import eu.wohlben.qits.projects.dto.AgentMcpAttachmentDto;
import eu.wohlben.qits.projects.dto.AgentMcpCatalogEntryDto;
import eu.wohlben.qits.projects.dto.AgentSurfaceConfigurationDto;
import eu.wohlben.qits.projects.entity.AgentHarness;
import eu.wohlben.qits.projects.entity.AgentPermissionMode;
import eu.wohlben.qits.projects.entity.AgentSurfaceConfiguration;
import eu.wohlben.qits.projects.entity.AgentSurfaceConfigurationRevision;
import eu.wohlben.qits.projects.entity.AgentSurfaceExternalMcpAttachment;
import eu.wohlben.qits.projects.entity.AgentSurfaceMcpAttachment;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.InternalServerErrorException;
import eu.wohlben.qits.projects.persistence.AgentSurfaceConfigurationRepository;
import eu.wohlben.qits.projects.persistence.AgentSurfaceConfigurationRevisionRepository;
import eu.wohlben.qits.projects.persistence.AgentSurfaceExternalMcpAttachmentRepository;
import eu.wohlben.qits.projects.persistence.AgentSurfaceMcpAttachmentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reading and editing what each session surface is configured to launch as.
 *
 * <p><b>An absent row is never a 404.</b> Every read falls through to {@link AgentSurfaceDefaults},
 * so a surface nobody has edited answers what it ships as and a surface nobody has heard of answers
 * the neutral default. That is what lets a daemon ship ahead of this store, and what lets a ninth
 * surface be added one repository at a time.
 *
 * <p>Writes are whole-record replacements rather than patches, and each one appends an {@link
 * AgentSurfaceConfigurationRevision} <em>inside the same transaction</em>: a trail that can disagree
 * with the row it describes is worse than no trail. The MCP attachment set is replaced wholesale for
 * the same reason — a half-applied set of servers is not a state anybody asked for — and the replace
 * is an explicit delete, flush, insert, so it cannot race the unique {@code (surface_key,
 * server_key)} constraint.
 *
 * <p><b>The pre-approval tool lists are not taken from an editor.</b> They are shipped constants,
 * and {@link #validated} strips whatever a request carried. What a write then stores is the list the
 * row already held for that server, or the shipped one for a server the row did not hold — which is
 * what preserves the workspace daemon's longer {@code repository} list through an edit that only
 * changed a prompt.
 */
@ApplicationScoped
public class AgentSurfaceConfigurationService {

  @Inject AgentSurfaceConfigurationRepository configurations;

  @Inject AgentSurfaceMcpAttachmentRepository attachments;

  @Inject AgentSurfaceExternalMcpAttachmentRepository externalAttachments;

  @Inject AgentSurfaceConfigurationRevisionRepository revisions;

  @Inject AgentMcpCatalogService catalog;

  @Inject ObjectMapper json;

  // -------------------------------------------------------------------------------------------
  // Reads
  // -------------------------------------------------------------------------------------------

  /**
   * Every surface the platform knows, resolved: the stored row where there is one, the shipped
   * default where there is not, plus any stored surface outside the shipped vocabulary.
   *
   * <p>The shipped vocabulary leads, in its own order, so the editor's list does not reshuffle as
   * rows are written; anything stored beyond it follows, sorted, so a surface written by a newer
   * peer is visible rather than invisible.
   */
  public List<AgentSurfaceConfigurationDto> listAll() {
    Map<String, AgentSurfaceConfiguration> stored = new LinkedHashMap<>();
    for (AgentSurfaceConfiguration row : configurations.allOrdered()) {
      stored.put(row.surfaceKey, row);
    }
    Map<String, List<AgentSurfaceMcpAttachment>> bySurface = new LinkedHashMap<>();
    for (AgentSurfaceMcpAttachment attachment : attachments.allOrdered()) {
      bySurface.computeIfAbsent(attachment.surfaceKey, key -> new ArrayList<>()).add(attachment);
    }
    Set<String> keys = new LinkedHashSet<>(AgentSurfaceDefaults.SURFACES);
    keys.addAll(stored.keySet());
    List<AgentSurfaceConfigurationDto> answer = new ArrayList<>(keys.size());
    for (String key : keys) {
      AgentSurfaceConfiguration row = stored.get(key);
      answer.add(
          row != null
              ? toDto(row, bySurface.getOrDefault(key, List.of()), catalog.attachedTo(key))
              : AgentSurfaceDefaults.shippedDefault(key));
    }
    return List.copyOf(answer);
  }

  /** One surface, resolved. Never absent — an unknown key answers its shipped default. */
  public AgentSurfaceConfigurationDto get(String surfaceKey) {
    String key = requireSurfaceKey(surfaceKey);
    return configurations
        .findByIdOptional(key)
        .map(row -> toDto(row, attachments.forSurface(key), catalog.attachedTo(key)))
        .orElseGet(() -> AgentSurfaceDefaults.shippedDefault(key));
  }

  /**
   * The whole document a container is created with — every surface, resolved, in listing order.
   *
   * <p>Every surface rather than only the ones a given container serves: which surfaces a container
   * can serve is the caller's knowledge, the document is a handful of kilobytes, and a container
   * that turns out to serve a surface the fetcher did not predict is better off holding a
   * configuration for it than falling back to the library's constants.
   *
   * <p><b>This is where credentials are read, and the only place.</b> Each surface's attached catalog
   * entries are rendered whole — url and header value — so a container needs no second lookup and
   * never holds a qits-configuration reference it would have to resolve from inside a workspace. A
   * reference that cannot be resolved throws out of here and the whole document fails, naming the key
   * and the surface: a partially-resolved document handed to a container would be a server that 401s
   * on the agent's first tool call, which surfaces as a confused agent hours later rather than as an
   * error anybody reads.
   *
   * <p>The failure is deliberately whole-document rather than per-surface. A container is created
   * with one document for every surface it may serve; dropping one surface's servers quietly would
   * make it launch a session that looks configured and is not.
   */
  public AgentConfigurationDocumentDto document() {
    return document(Instant.now().toString());
  }

  /**
   * The same document, stamped with <b>when the store last changed</b> rather than with now — what
   * qits-projects injects into its own agent container's spec.
   *
   * <p>The difference is one field and it is load-bearing. These bytes travel in the container spec
   * qits-containers hashes, and {@code AgentContainerFactory.forRestart} sends {@code
   * Recreate.ifChanged}: a spec that differs from the stored one is a container <em>replacement</em>.
   * A wall clock in the document would differ on every call, so every wake would replace the
   * container instead of starting it in place — precisely the defect that repo carried while
   * qits-containers had no start verb, reintroduced through a timestamp. {@code
   * AgentContainerFactoryTest.aRestartPermitsAReplacementAndIsOtherwiseTheSameRequest} is what
   * notices.
   *
   * <p>Stamping the last edit is also the more honest answer for an injected document: the container
   * holds a configuration that is as old as the last change to it, not as old as the moment somebody
   * pressed a button. Everything else that can move the document — a catalog entry's url, a resolved
   * credential — moves the bytes themselves, so this field carries no obligation to notice it.
   *
   * <p>A store nobody has written yet stamps the empty string rather than an invented instant.
   */
  public AgentConfigurationDocumentDto documentForContainerSpec() {
    return document(revisions.latestChangeAt().map(Instant::toString).orElse(""));
  }

  private AgentConfigurationDocumentDto document(String generatedAt) {
    List<AgentDocumentSurfaceDto> surfaces = new ArrayList<>();
    for (AgentSurfaceConfigurationDto configuration : listAll()) {
      surfaces.add(
          new AgentDocumentSurfaceDto(configuration, catalog.resolve(configuration.surface())));
    }
    return new AgentConfigurationDocumentDto(
        AgentConfigurationDocumentDto.CURRENT_VERSION, generatedAt, List.copyOf(surfaces));
  }

  /** One surface's revision trail, newest first. */
  public List<AgentSurfaceConfigurationRevision> history(String surfaceKey) {
    return revisions.forSurface(requireSurfaceKey(surfaceKey));
  }

  // -------------------------------------------------------------------------------------------
  // Writes
  // -------------------------------------------------------------------------------------------

  /**
   * Replace one surface's configuration, appending a revision.
   *
   * @param changedBy the acting principal, or null when the write is unattributed (the boot seed)
   */
  @Transactional
  public AgentSurfaceConfigurationDto save(
      String surfaceKey, AgentSurfaceConfigurationDto wanted, String changedBy) {
    String key = requireSurfaceKey(surfaceKey);
    Instant now = Instant.now();

    Map<String, String> keptTools = new LinkedHashMap<>();
    for (AgentSurfaceMcpAttachment existing : attachments.forSurface(key)) {
      keptTools.put(existing.serverKey, existing.allowedTools);
    }

    AgentSurfaceConfiguration row = configurations.findById(key);
    boolean fresh = row == null;
    if (fresh) {
      row = new AgentSurfaceConfiguration();
      row.surfaceKey = key;
      row.createdAt = now;
    }
    row.harness = wanted.harness();
    row.model = blankIfNull(wanted.model());
    row.effort = blankIfNull(wanted.effort());
    row.remoteControl = wanted.remoteControl();
    row.permissionMode = wanted.permissionMode();
    row.activityTracking = wanted.activityTracking();
    row.systemPrompt = blankIfNull(wanted.systemPrompt());
    row.initialPrompt = blankIfNull(wanted.initialPrompt());
    row.updatedBy = changedBy;
    row.updatedAt = now;
    if (fresh) {
      configurations.persist(row);
    }

    // Delete, flush, insert — see the class javadoc: the unique (surface_key, server_key)
    // constraint is what makes the order matter, and Hibernate's own is not dependable here.
    attachments.clearSurface(key);
    attachments.flush();
    List<AgentSurfaceMcpAttachment> written = new ArrayList<>();
    int position = 0;
    for (AgentMcpAttachmentDto attachment : wanted.mcpServers()) {
      AgentSurfaceMcpAttachment entity = new AgentSurfaceMcpAttachment();
      entity.id = UUID.randomUUID().toString();
      entity.surfaceKey = key;
      entity.serverKey = attachment.server();
      entity.position = position++;
      entity.narrowProject = attachment.narrowProject();
      entity.narrowRepository = attachment.narrowRepository();
      entity.narrowWorkspace = attachment.narrowWorkspace();
      entity.readOnly = attachment.readOnly();
      List<String> supplied = attachment.allowedTools();
      entity.allowedTools =
          supplied != null && !supplied.isEmpty()
              ? AgentSurfaceMcpAttachment.encodeTools(supplied)
              : keptTools.getOrDefault(
                  attachment.server(),
                  AgentSurfaceMcpAttachment.encodeTools(
                      AgentSurfaceDefaults.shippedToolsFor(attachment.server())));
      attachments.persist(entity);
      written.add(entity);
    }

    // The external set is replaced the same way and for the same reason: its own unique
    // (surface_key, catalog_key) constraint, and a half-applied set of servers is not a state
    // anybody asked for. They are written after the built-ins because that is their render order.
    externalAttachments.clearSurface(key);
    externalAttachments.flush();
    int externalPosition = 0;
    for (AgentMcpCatalogEntryDto entry : wanted.externalMcpServers()) {
      AgentSurfaceExternalMcpAttachment entity = new AgentSurfaceExternalMcpAttachment();
      entity.id = UUID.randomUUID().toString();
      entity.surfaceKey = key;
      entity.catalogKey = entry.key();
      entity.position = externalPosition++;
      externalAttachments.persist(entity);
    }
    configurations.flush();

    AgentSurfaceConfigurationDto saved = toDto(row, written, catalog.attachedTo(key));
    AgentSurfaceConfigurationRevision revision = new AgentSurfaceConfigurationRevision();
    revision.id = UUID.randomUUID().toString();
    revision.surfaceKey = key;
    revision.changedBy = changedBy;
    revision.changedAt = now;
    revision.snapshot = snapshot(saved);
    revisions.persist(revision);
    return saved;
  }

  /**
   * Write the shipped default for {@code surfaceKey} if the store holds no row for it. Answers
   * whether a row was written.
   *
   * <p>Insert-if-absent rather than upsert, and that is the whole contract the boot seed rests on:
   * an operator's edit must survive every subsequent boot, so a seed that overwrote would be a
   * nightly undo of everything anybody changed.
   */
  @Transactional
  public boolean seedIfAbsent(String surfaceKey) {
    String key = requireSurfaceKey(surfaceKey);
    if (configurations.findByIdOptional(key).isPresent()) {
      return false;
    }
    save(key, AgentSurfaceDefaults.shippedDefault(key), null);
    return true;
  }

  // -------------------------------------------------------------------------------------------
  // Validation
  // -------------------------------------------------------------------------------------------

  /**
   * Reads an editor's write body into a configuration, refusing anything the render path could not
   * carry: an unknown harness, an unknown permission mode, an MCP attachment naming a server that
   * does not exist, and the same server attached twice.
   *
   * <p>The duplicate check is not fussiness. Both harnesses render the attached servers into one
   * {@code key → config} object, so a second entry under the same key silently replaces the first
   * and the session ends up talking through whichever narrowing happened to be last.
   *
   * <p><b>External attachments are validated on the same terms, one table over.</b> A named catalog
   * entry must exist — an attachment naming nothing would fail every container provision that
   * reaches it — and no key may be attached twice. The two vocabularies cannot collide with each
   * other because {@code AgentMcpCatalog} refuses the three reserved names at the catalog's own
   * write door, which is where that rule belongs: one refusal, at the point somebody types the name,
   * rather than a check repeated at every attachment.
   */
  public AgentSurfaceConfigurationDto validated(
      String surfaceKey,
      String harness,
      String model,
      String effort,
      boolean remoteControl,
      String permissionMode,
      boolean activityTracking,
      String systemPrompt,
      String initialPrompt,
      List<AgentMcpAttachmentDto> mcpServers,
      List<String> externalMcpServers) {
    AgentHarness parsedHarness =
        parse(AgentHarness.class, harness)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "Unknown harness: " + harness + " (known: CLAUDE, KIMI)"));
    AgentPermissionMode parsedMode =
        parse(AgentPermissionMode.class, permissionMode)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "Unknown permission mode: "
                            + permissionMode
                            + " (known: SKIP_PERMISSIONS, PROMPT)"));
    List<AgentMcpAttachmentDto> servers = mcpServers == null ? List.of() : mcpServers;
    Set<String> seen = new LinkedHashSet<>();
    for (AgentMcpAttachmentDto attachment : servers) {
      String server = attachment.server();
      if (!AgentSurfaceDefaults.BUILT_IN_SERVERS.contains(server)) {
        throw new BadRequestException(
            "Unknown MCP server: "
                + server
                + " (known: "
                + String.join(", ", AgentSurfaceDefaults.BUILT_IN_SERVERS)
                + ")");
      }
      if (!seen.add(server)) {
        throw new BadRequestException("MCP server attached twice: " + server);
      }
    }
    List<String> external = externalMcpServers == null ? List.of() : externalMcpServers;
    List<String> known = catalog.knownKeys();
    List<AgentMcpCatalogEntryDto> attachedEntries = new ArrayList<>();
    for (String candidate : external) {
      String entryKey = candidate == null ? "" : candidate.trim();
      if (!known.contains(entryKey)) {
        throw new BadRequestException(
            "No MCP catalog entry `"
                + entryKey
                + "`"
                + (known.isEmpty()
                    ? ". The catalog is empty — define the server first."
                    : " (defined: " + String.join(", ", known) + ")"));
      }
      if (!seen.add(entryKey)) {
        throw new BadRequestException("MCP server attached twice: " + entryKey);
      }
      // Read back rather than trusting the request body: the request names a key and nothing else,
      // and everything about the server — url, header, tools — belongs to the catalog entry.
      attachedEntries.add(catalog.get(entryKey));
    }
    return new AgentSurfaceConfigurationDto(
        requireSurfaceKey(surfaceKey),
        parsedHarness,
        blankIfNull(model),
        blankIfNull(effort),
        remoteControl,
        parsedMode,
        activityTracking,
        blankIfNull(systemPrompt),
        blankIfNull(initialPrompt),
        // The editor never sets pre-approval lists; save() supplies them.
        servers.stream()
            .map(
                a ->
                    new AgentMcpAttachmentDto(
                        a.server(),
                        a.narrowProject(),
                        a.narrowRepository(),
                        a.narrowWorkspace(),
                        a.readOnly(),
                        List.of()))
            .toList(),
        attachedEntries,
        false);
  }

  // -------------------------------------------------------------------------------------------
  // Plumbing
  // -------------------------------------------------------------------------------------------

  static AgentSurfaceConfigurationDto toDto(
      AgentSurfaceConfiguration row,
      List<AgentSurfaceMcpAttachment> attachments,
      List<AgentMcpCatalogEntryDto> externalServers) {
    return new AgentSurfaceConfigurationDto(
        row.surfaceKey,
        row.harness,
        row.model,
        row.effort,
        row.remoteControl,
        row.permissionMode,
        row.activityTracking,
        row.systemPrompt,
        row.initialPrompt,
        attachments.stream()
            .sorted((a, b) -> Integer.compare(a.position, b.position))
            .map(
                a ->
                    new AgentMcpAttachmentDto(
                        a.serverKey,
                        a.narrowProject,
                        a.narrowRepository,
                        a.narrowWorkspace,
                        a.readOnly,
                        a.allowedToolList()))
            .toList(),
        externalServers,
        false);
  }

  private String snapshot(AgentSurfaceConfigurationDto configuration) {
    try {
      return json.writeValueAsString(configuration);
    } catch (JsonProcessingException e) {
      // A snapshot that cannot be written must fail the write it describes rather than leave a row
      // with no trail: the trail is the only record of what a prompt used to say.
      throw new InternalServerErrorException(
          "Could not record a revision for " + configuration.surface() + ": " + e.getMessage());
    }
  }

  private static <E extends Enum<E>> Optional<E> parse(Class<E> type, String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Enum.valueOf(type, value.trim()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static String blankIfNull(String value) {
    return value == null ? "" : value;
  }

  private static String requireSurfaceKey(String surfaceKey) {
    if (surfaceKey == null || surfaceKey.isBlank()) {
      throw new BadRequestException("surface is required");
    }
    return surfaceKey.trim();
  }
}
