package eu.wohlben.qits.epics.dto;

import java.time.Instant;
import java.util.List;

/**
 * @param workspaces the live workspaces working on this epic — {@link TicketDto#workspaces}' field,
 *     rule for rule, and the read behind "Start implementation" knowing it has already been pressed.
 *     An epic leaves no other trace of a dispatch: its door writes nothing on the row, by design.
 */
public record EpicDto(
    String id,
    String projectId,
    String title,
    String slug,
    String status,
    String supersededByEpicId,
    String description,
    Instant createdAt,
    Instant updatedAt,
    List<WorkspaceReferenceDto> workspaces) {

  /** The same epic, told which workspaces are on it. */
  public EpicDto withWorkspaces(List<WorkspaceReferenceDto> found) {
    return new EpicDto(
        id,
        projectId,
        title,
        slug,
        status,
        supersededByEpicId,
        description,
        createdAt,
        updatedAt,
        found == null ? List.of() : found);
  }
}
