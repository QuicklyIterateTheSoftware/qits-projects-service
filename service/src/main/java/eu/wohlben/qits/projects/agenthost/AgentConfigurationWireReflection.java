package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projects.dto.AgentConfigurationDocumentDto;
import eu.wohlben.qits.projects.dto.AgentMcpAttachmentDto;
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
 *       ObjectMapper} into every revision-trail snapshot, and the feature after this one writes the
 *       whole document to a file this service mounts into its own agent container. Neither is a
 *       resource method, and the second is not even a request.
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
      AgentSurfaceConfigurationDto.class,
      AgentMcpAttachmentDto.class,
      AgentHarness.class,
      AgentPermissionMode.class
    })
public final class AgentConfigurationWireReflection {

  private AgentConfigurationWireReflection() {}
}
