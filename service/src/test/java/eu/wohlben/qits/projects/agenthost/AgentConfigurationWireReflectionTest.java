package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.projects.api.AgentCapabilityController;
import eu.wohlben.qits.projects.dto.AgentCapabilityCatalogueDto;
import eu.wohlben.qits.projects.dto.AgentCapabilityImageVersionDto;
import eu.wohlben.qits.projects.dto.AgentConfigurationDocumentDto;
import eu.wohlben.qits.projects.dto.AgentDocumentSurfaceDto;
import eu.wohlben.qits.projects.dto.AgentHarnessCapabilityDto;
import eu.wohlben.qits.projects.dto.AgentMcpAttachmentDto;
import eu.wohlben.qits.projects.dto.AgentMcpCatalogEntryDto;
import eu.wohlben.qits.projects.dto.AgentResolvedMcpServerDto;
import eu.wohlben.qits.projects.dto.AgentSurfaceConfigurationDto;
import eu.wohlben.qits.projects.entity.AgentHarness;
import eu.wohlben.qits.projects.entity.AgentPermissionMode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link AgentConfigurationWireReflection}'s <em>completeness</em>, which is all a JVM test
 * can reach here.
 *
 * <p>Say it plainly, the way {@code EventWireReflectionTest} does: on a JVM every class reflects
 * whether anyone registered it or not, so deleting the annotation tomorrow would fail nothing but
 * the assertion that reads it. Only the native artifact proves the registration does its job. What
 * is checkable is that the registered set still covers every record the document is built from — and
 * a missed entry is a deployed binary that cannot write a revision snapshot or a mounted document,
 * with the whole suite green.
 */
public class AgentConfigurationWireReflectionTest {

  @Test
  public void theRegisteredTargetsAreExactlyTheDocumentsOwnTypes() {
    RegisterForReflection registration =
        AgentConfigurationWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class; without it this file is a no-op");
    assertEquals(
        Set.of(
            AgentConfigurationDocumentDto.class,
            AgentDocumentSurfaceDto.class,
            AgentSurfaceConfigurationDto.class,
            AgentMcpAttachmentDto.class,
            AgentMcpCatalogEntryDto.class,
            AgentResolvedMcpServerDto.class,
            AgentCapabilityCatalogueDto.class,
            AgentHarnessCapabilityDto.class,
            AgentCapabilityImageVersionDto.class,
            AgentCapabilityController.CapabilityReportRequest.class,
            AgentCapabilityController.HarnessCapabilityReport.class,
            AgentHarness.class,
            AgentPermissionMode.class),
        Set.of(registration.targets()),
        "the document and its surfaces, the built-in and external MCP shapes, the capability"
            + " catalogue, the ingest records the relay deserializes outside a request, and the two"
            + " enums the configuration serializes — a further type on any of them means a line"
            + " here");
  }

  /**
   * Every record component's own type is either registered or a JDK type Jackson handles without
   * reflection on ours. This is the check that would catch a nested record being added to the
   * document and not to the list — the exact shape of the failure this family exists to prevent.
   */
  @Test
  public void nothingReachableFromTheDocumentIsUnregistered() {
    Set<Class<?>> registered =
        Set.of(
            AgentConfigurationWireReflection.class
                .getAnnotation(RegisterForReflection.class)
                .targets());
    for (Class<?> type :
        new Class<?>[] {
          AgentConfigurationDocumentDto.class,
          AgentDocumentSurfaceDto.class,
          AgentSurfaceConfigurationDto.class,
          AgentMcpAttachmentDto.class,
          AgentMcpCatalogEntryDto.class,
          AgentResolvedMcpServerDto.class,
          AgentCapabilityCatalogueDto.class,
          AgentHarnessCapabilityDto.class,
          AgentCapabilityImageVersionDto.class,
          AgentCapabilityController.CapabilityReportRequest.class,
          AgentCapabilityController.HarnessCapabilityReport.class
        }) {
      for (var component : type.getRecordComponents()) {
        Class<?> componentType = component.getType();
        boolean handled =
            componentType.getName().startsWith("java.")
                || componentType.isPrimitive()
                || registered.contains(componentType);
        assertEquals(
            true,
            handled,
            type.getSimpleName()
                + "."
                + component.getName()
                + " is a "
                + componentType.getName()
                + ", which is neither a JDK type nor registered");
      }
    }
  }
}
