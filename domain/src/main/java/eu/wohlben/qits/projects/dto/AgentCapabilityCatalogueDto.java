package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * The catalogue behind the editor's model and effort dropdowns: one entry per harness, newest report
 * per harness, shipped fallback where nothing has reported.
 *
 * <p><b>A pure read of a cache, and that is the requirement rather than an optimisation.</b> The
 * editor is a platform-wide route with no container in front of it; it may not shell out to a
 * harness binary and may not block waiting for a container to come up. So every value here was
 * written earlier, by a container reporting in at its own start, and this read touches one table.
 *
 * <p>The list holds every harness the platform knows, in {@code AgentHarness}'s own order, so the
 * editor's harness dropdown and this catalogue cannot disagree about what exists.
 *
 * @param harnesses every harness, newest report each
 */
public record AgentCapabilityCatalogueDto(List<AgentHarnessCapabilityDto> harnesses) {}
