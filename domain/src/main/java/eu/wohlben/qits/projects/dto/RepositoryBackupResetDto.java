package eu.wohlben.qits.projects.dto;

import eu.wohlben.qits.projects.entity.BackupResetKind;
import eu.wohlben.qits.projects.entity.RepositoryBackupReset;
import java.time.Instant;

/**
 * One reset of a repository's backup twin (qits-1885) — the answer of {@code POST
 * /repositories/{repoId}/backup-reset} and an entry of {@code GET …/backup-resets}.
 *
 * @param repositoryId the repository whose twin was reset
 * @param kind {@code FAST_FORWARD}: a plain push, the twin was behind or had no main; {@code
 *     UNRELATED_RESET}: the twin's main shared no commit with ours and was force-updated under a
 *     lease on {@code twinShaBefore}
 * @param twinShaBefore the twin's main before the push; null when it had none
 * @param pushedSha our main, which the twin's main is now
 * @param decidedBy the verified person, or the project's delivery desk's token subject
 * @param decidedAt when
 * @param reason why, as they said it
 */
public record RepositoryBackupResetDto(
    String repositoryId,
    BackupResetKind kind,
    String twinShaBefore,
    String pushedSha,
    String decidedBy,
    Instant decidedAt,
    String reason) {

  public static RepositoryBackupResetDto of(RepositoryBackupReset row) {
    return new RepositoryBackupResetDto(
        row.repositoryId,
        row.kind,
        row.twinShaBefore,
        row.pushedSha,
        row.decidedBy,
        row.decidedAt,
        row.reason);
  }
}
