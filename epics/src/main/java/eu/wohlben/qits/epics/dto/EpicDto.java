package eu.wohlben.qits.epics.dto;

import java.time.Instant;
import java.util.List;

/**
 * @param number the per-project numeric id — the bare {@code long} as stored. It travels beside
 *     {@link #qualifiedId} rather than instead of it because the SPA needs the datum as well as the
 *     rendering: a sort, a filter and a "copy the id" button all want the number, and only a commit
 *     subject wants the qualified string.
 * @param qualifiedId {@code <project-slug>-<number>} — {@code qits-1337}, the form that is written
 *     by hand. <b>Null until somebody resolves the project slug</b>, which this module cannot do:
 *     the slug lives in {@code domain}'s {@code project} table, in a different physical database,
 *     and {@code epics} depends on {@code domain} nowhere. {@code projects/api/QualifiedEntityIds}
 *     is the one place it is filled, at the DTO boundary in the {@code service} module, exactly as
 *     {@code DispatchedWorkspaces} fills {@link #workspaces}.
 * @param workspaces the live workspaces working on this epic — {@link TicketDto#workspaces}' field,
 *     rule for rule, and the read behind "Start implementation" knowing it has already been pressed.
 *     An epic leaves no other trace of a dispatch: its door writes nothing on the row, by design.
 */
public record EpicDto(
    String id,
    String projectId,
    long number,
    String qualifiedId,
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
        number,
        qualifiedId,
        title,
        slug,
        status,
        supersededByEpicId,
        description,
        createdAt,
        updatedAt,
        found == null ? List.of() : found);
  }

  /** The same epic, told what it is called in a commit subject. */
  public EpicDto withQualifiedId(String rendered) {
    return new EpicDto(
        id,
        projectId,
        number,
        rendered,
        title,
        slug,
        status,
        supersededByEpicId,
        description,
        createdAt,
        updatedAt,
        workspaces);
  }
}
