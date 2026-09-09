package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projects.dto.AgentCapabilityCatalogueDto;
import eu.wohlben.qits.projects.dto.AgentCapabilityImageVersionDto;
import eu.wohlben.qits.projects.dto.AgentConfigurationDocumentDto;
import eu.wohlben.qits.projects.dto.AgentDocumentSurfaceDto;
import eu.wohlben.qits.projects.dto.AgentHarnessCapabilityDto;
import eu.wohlben.qits.projects.dto.AgentMcpAttachmentDto;
import eu.wohlben.qits.projects.dto.AgentMcpCatalogEntryDto;
import eu.wohlben.qits.projects.dto.AgentResolvedMcpServerDto;
import eu.wohlben.qits.projects.api.AgentCapabilityController;
import eu.wohlben.qits.projects.dto.AgentSurfaceConfigurationDto;
import eu.wohlben.qits.projects.entity.AgentHarness;
import eu.wohlben.qits.projects.entity.AgentPermissionMode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What the native image owes the agent configuration document, and nothing else lives here.
 *
 * <p>The third member of the family {@code bus/EventWireReflection} opened and {@code
 * containershost/ContainersWireReflection} joined, and it is here for a version of the same reason.
 * These records leave this process by two paths, and only one of them is a REST return type the
 * build step can see:
 *
 * <ul>
 *   <li>{@code api/AgentConfigurationController} answers the document — Quarkus registers a
 *       resource's return types, so that path would survive without this class;
 *   <li>the document is <b>also serialized outside a request</b>, and increasingly so: {@code
 *       control/AgentSurfaceConfigurationService} writes a configuration through the injected {@code
 *       ObjectMapper} into every revision-trail snapshot; {@code AgentContainerFactory} serializes
 *       the whole document into the spec of its own agent container; and {@code
 *       AgentCapabilityRelay} reads a daemon's report back through the same mapper. None of the
 *       three is a resource method, and none of them is even inside a request.
 * </ul>
 *
 * <p><b>A JVM test cannot catch a missing entry</b> — on a JVM these types reflect whether anyone
 * registered them or not — so the guard is that this list and the records are kept the same list, by
 * {@code AgentConfigurationWireReflectionTest}. qits-ci measured what the absence costs on a
 * deployed binary: every write dying inside Jackson with "no serializer found", green suite and all.
 *
 * <p>The two enums are on the list beside the records because they are what {@code harness} and
 * {@code permissionMode} serialize as, and an enum's constants are found reflectively.
 *
 * <p>It lives in {@code agenthost/} rather than in {@code dto/} for the reason {@code
 * containershost/}'s does: a registration is something the <b>deployable</b> owes the builder about
 * itself, and {@code domain} is a plain library jar that does not get built into an image.
 */
@RegisterForReflection(
    targets = {
      AgentConfigurationDocumentDto.class,
      AgentDocumentSurfaceDto.class,
      AgentSurfaceConfigurationDto.class,
      AgentMcpAttachmentDto.class,
      // The external MCP catalog. AgentMcpCatalogEntryDto travels by both paths — a REST return type
      // and a revision snapshot through the injected ObjectMapper — and AgentResolvedMcpServerDto by
      // the second only: it appears nowhere but inside the document written to a file and mounted
      // into a container, which is the exact shape this class exists for.
      AgentMcpCatalogEntryDto.class,
      AgentResolvedMcpServerDto.class,
      // The capability catalogue. A REST return type today; on the list because it is the same
      // family and because a cached catalogue written to a file is the obvious next use of it.
      AgentCapabilityCatalogueDto.class,
      AgentHarnessCapabilityDto.class,
      AgentCapabilityImageVersionDto.class,
      // The ingest door's request records. They are a REST *parameter* type rather than a return
      // type, and on top of that AgentCapabilityRelay DESERIALIZES them through the injected
      // ObjectMapper outside any request — it reads a daemon's /agents/available body into exactly
      // this shape before handing it to the door. That second path is this class's whole subject,
      // and a record with no components found is a relay that silently records nothing.
      AgentCapabilityController.CapabilityReportRequest.class,
      AgentCapabilityController.HarnessCapabilityReport.class,
      AgentHarness.class,
      AgentPermissionMode.class
    })
public final class AgentConfigurationWireReflection {

  private AgentConfigurationWireReflection() {}
}
