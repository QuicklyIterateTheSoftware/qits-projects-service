package eu.wohlben.qits.projects.dto;

/**
 * A single file touched by a commit (relative to the diff base). {@code oldPath} is non-null only
 * for renames/copies (the path the file moved from); {@code changeType} is one of {@code
 * ADDED|MODIFIED|DELETED|RENAMED|COPIED|TYPE_CHANGED}.
 *
 * <p><b>The modes and object ids are carried because a path and a status do not say what a tree
 * entry <em>is</em>.</b> The one case that matters is mode {@code 160000}: a gitlink is not a file,
 * its "content" is a commit of another repository, and a change set that reports only {@code
 * MODIFIED components/qits-ci/qits-ci-service} has thrown away the only two facts about it worth
 * having. With the pair of shas in hand the same row can be labelled with the repository it resolves
 * to and expanded into that repository's own commits.
 *
 * @param oldMode the base side's tree mode ({@code 100644}, {@code 160000}, …), null when the entry
 *     did not exist there
 * @param newMode the fold side's tree mode, null when the entry does not exist there
 * @param oldSha the base side's object id, null when the entry did not exist there. Git prints an
 *     all-zero id for an absent side; that is normalised away rather than handed on, because {@code
 *     0000000…} is a sentinel and a caller that treats it as an id asks git for a commit that has
 *     never existed.
 * @param newSha the fold side's object id, null on the same terms
 * @param submodule what the gitlink resolves to, when this entry is one and the caller asked for
 *     that reading. Null for every ordinary file, and null on a gitlink nobody has labelled.
 */
public record CommitFileChangeDto(
    String path,
    String oldPath,
    String changeType,
    String oldMode,
    String newMode,
    String oldSha,
    String newSha,
    SubmoduleRefDto submodule) {

  /** This entry with its gitlink labelled; every other field is left exactly as git reported it. */
  public CommitFileChangeDto withSubmodule(SubmoduleRefDto ref) {
    return new CommitFileChangeDto(
        path, oldPath, changeType, oldMode, newMode, oldSha, newSha, ref);
  }

  /** Whether either side of this entry is a {@code 160000} gitlink. */
  public boolean touchesGitlink() {
    return "160000".equals(oldMode) || "160000".equals(newMode);
  }
}
