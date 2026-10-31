package eu.wohlben.qits.entities.api;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * <b>The answer of the drop door</b>, {@code PUT /work/{qualifiedId}/priority} (qits-753): every
 * sibling the drop renumbered — the moved item and each one above it — in their new order, highest
 * first, each by qualified id with the priority it now holds. The siblings below the drop are not
 * in it, because nothing below it was written.
 *
 * <p>A record of its own rather than one in {@code WorkController}, because {@link WorkEntityDoors}
 * builds it and the doors name no controller.
 *
 * @param items the renumbered siblings, in order
 */
@Schema(
    name = "WorkPriorities",
    description =
        "The siblings a drop renumbered — the moved item and every one above it — in their new"
            + " order, highest first.")
public record WorkPriorities(List<WorkPriority> items) {

  /**
   * One renumbered sibling.
   *
   * @param qualifiedId {@code <project-slug>-<number>}
   * @param priority the priority it now holds
   */
  @Schema(name = "WorkPriority", description = "One sibling and the priority it now holds.")
  public record WorkPriority(String qualifiedId, int priority) {}
}
