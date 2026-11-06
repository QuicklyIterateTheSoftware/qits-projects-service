package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.entity.Archetype;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * <b>The answer of {@code GET /projects/{project}/work/briefs}</b> (qits-1897): the work items a
 * caller named by qualified id, each as the little a reference to it shows — a chip, a link label,
 * a status square — and nothing else. No description, no impetus, no excerpt: a caller that needs
 * the body reads the item.
 *
 * <p>A record of its own rather than one in {@code ProjectWorkController}, because {@link
 * WorkEntityDoors} builds it and the doors name no controller.
 *
 * @param briefs one entry per distinct id that names an item of the project, in the order the ids
 *     were first named; an id naming nothing there is absent
 */
@Schema(
    name = "WorkBriefs",
    description =
        "The work items a caller named, each in brief, in the order the ids were first named. An id"
            + " that names nothing in the project — unknown, another project's, or malformed — is"
            + " left out.")
public record WorkBriefs(List<WorkBrief> briefs) {

  /**
   * One work item, in brief.
   *
   * @param id the entity's UUID
   * @param qualifiedId {@code <project-slug>-<number>}
   * @param archetype EPIC, FEATURE, TASK, TICKET, CAMPAIGN, …
   * @param status the status word as stored
   * @param title the label
   * @param updatedAt when the row was last written
   */
  @Schema(name = "WorkBrief", description = "One work item, in brief.")
  public record WorkBrief(
      @Schema(required = true, description = "The entity's UUID.") String id,
      @Schema(required = true, description = "<project-slug>-<number>, e.g. qits-12.")
          String qualifiedId,
      @Schema(required = true, description = "The item's archetype.") Archetype archetype,
      @Schema(required = true, description = "The status word as stored.") String status,
      @Schema(required = true, description = "The label.") String title,
      @Schema(required = true, description = "When the item was last written.")
          Instant updatedAt) {}
}
