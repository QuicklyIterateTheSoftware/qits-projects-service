package eu.wohlben.qits.epics.dto;

import java.time.Instant;

/**
 * One dossier page, body included. The list carries bodies too: a dossier is a handful of pages and
 * the tab renders one immediately, so a second round trip per page would buy nothing.
 */
public record DossierPageDto(
    String id,
    String epicId,
    String slug,
    String title,
    int position,
    String body,
    long version,
    Instant createdAt,
    Instant updatedAt) {}
