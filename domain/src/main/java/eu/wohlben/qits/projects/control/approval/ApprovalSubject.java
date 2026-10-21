package eu.wohlben.qits.projects.control.approval;

import eu.wohlben.qits.projects.control.FoldChanges;
import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * <b>What an {@link ApprovalRequirementSource} is asked about</b>: one fold of one repository's
 * request, the repository's gate set as {@code main} configures it, and the fold's content —
 * <b>read lazily, once</b>. A source that never asks for the changes costs no git; two sources asking
 * for them cost one read, and a failure is remembered so the second asker meets the same failure
 * rather than a second mirror read.
 *
 * <p>Not thread-safe and not meant to be: one subject is built per question and asked on one thread.
 *
 * <p>Every lazy read throws {@link FoldUnreadable} rather than answering a value an outage could be
 * mistaken for — "changes nothing" and "adds something to main" are the two answers that must never
 * come out of a failure, because each waves a fold past the person it is held for.
 */
public final class ApprovalSubject {

  private static final Logger LOG = Logger.getLogger(ApprovalSubject.class);

  /** The id of the requirement an unreadable fold becomes (the epic's fail-closed rule). */
  public static final String UNREADABLE_CHANGES_ID = ConfigDirectorySource.ID;

  private final String repoId;
  private final String projectId;
  private final String mergedSha;
  private final ReleaseRequest.State state;
  private final ReleaseGates.GateSet gateSet;
  private final boolean foldReadable;
  private final FoldChanges reader;

  private List<CommitFileChangeDto> changes;
  private FoldUnreadable changesFailure;
  private Boolean addsNothing;
  private FoldUnreadable addsNothingFailure;
  private final Map<String, String> blobs = new HashMap<>();

  /**
   * @param mergedSha the fold, or null for a request with none yet
   * @param foldReadable whether the fold's content may be read at all — a fold, of a repository that
   *     still has a row, of a request still before its tag; false makes every fold-reading source
   *     answer nothing
   * @param reader the fold reader; may be null where {@code foldReadable} is false
   */
  public ApprovalSubject(
      String repoId,
      String projectId,
      String mergedSha,
      ReleaseRequest.State state,
      ReleaseGates.GateSet gateSet,
      boolean foldReadable,
      FoldChanges reader) {
    this.repoId = repoId;
    this.projectId = projectId;
    this.mergedSha = mergedSha;
    this.state = state;
    this.gateSet = gateSet == null ? ReleaseGates.GateSet.of(java.util.Set.of()) : gateSet;
    this.foldReadable =
        foldReadable && reader != null && mergedSha != null && !mergedSha.isBlank();
    this.reader = reader;
  }

  public String repoId() {
    return repoId;
  }

  public String projectId() {
    return projectId;
  }

  /** The fold, or null where the request has none yet. */
  public String mergedSha() {
    return mergedSha;
  }

  public ReleaseRequest.State state() {
    return state;
  }

  /** The repository's gate set as {@code main} configures it. */
  public ReleaseGates.GateSet gateSet() {
    return gateSet;
  }

  /** Whether the fold's content may be asked for; a source reading it answers nothing otherwise. */
  public boolean foldReadable() {
    return foldReadable;
  }

  /**
   * Every entry the fold changed, against the base the request's {@code …/changes} view diffs it
   * against ({@link FoldChanges#changes}), over the whole tree. Read on first ask.
   *
   * @throws FoldUnreadable when it cannot be read — or when the fold is not readable at all, which a
   *     caller asks {@link #foldReadable()} about first
   */
  public List<CommitFileChangeDto> changes() {
    requireReadable();
    if (changesFailure != null) {
      throw changesFailure;
    }
    if (changes == null) {
      try {
        changes = List.copyOf(reader.changes(repoId, mergedSha, null));
      } catch (RuntimeException e) {
        LOG.warnf(
            "Could not read the changes of %s of %s; requiring a person's approval: %s",
            mergedSha, repoId, e.getMessage());
        changesFailure =
            new FoldUnreadable(
                UNREADABLE_CHANGES_ID,
                "the changes to " + ConfigDirectorySource.DIRECTORY + " could not be read",
                e.getMessage(),
                true,
                e);
        throw changesFailure;
      }
    }
    return changes;
  }

  /**
   * Whether the fold adds nothing to the repository's {@code main} ({@link
   * FoldChanges#addsNothingToMain}). Read on first ask.
   *
   * @throws FoldUnreadable when it cannot be read
   */
  public boolean addsNothingToMain() {
    requireReadable();
    if (addsNothingFailure != null) {
      throw addsNothingFailure;
    }
    if (addsNothing == null) {
      try {
        addsNothing = reader.addsNothingToMain(repoId, mergedSha);
      } catch (RuntimeException e) {
        LOG.warnf(
            "Could not read whether %s of %s changes anything against main; requiring approval: %s",
            mergedSha, repoId, e.getMessage());
        addsNothingFailure =
            new FoldUnreadable(
                NoChangesSource.ID,
                "whether the fold changes anything against main could not be read",
                e.getMessage(),
                false,
                e);
        throw addsNothingFailure;
      }
    }
    return addsNothing;
  }

  /**
   * The content of the blob {@code blobSha} in the repository, or null for a path that does not exist
   * on that side of the diff ({@code null} or the all-zero sha).
   *
   * @throws FoldUnreadable when it cannot be read
   */
  public String blob(String blobSha) {
    requireReadable();
    if (blobSha == null || blobSha.isBlank() || blobSha.chars().allMatch(c -> c == '0')) {
      return null;
    }
    if (blobs.containsKey(blobSha)) {
      return blobs.get(blobSha);
    }
    try {
      String content = reader.blob(repoId, blobSha);
      blobs.put(blobSha, content);
      return content;
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not read blob %s of %s; requiring a person's approval: %s",
          blobSha, repoId, e.getMessage());
      throw new FoldUnreadable(
          UNREADABLE_CHANGES_ID,
          "the changes to " + ConfigDirectorySource.DIRECTORY + " could not be read",
          e.getMessage(),
          true,
          e);
    }
  }

  private void requireReadable() {
    if (!foldReadable) {
      throw new IllegalStateException("the fold of this subject is not readable");
    }
  }
}
