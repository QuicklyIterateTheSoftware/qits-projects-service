package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.FoldChanges;
import eu.wohlben.qits.projects.control.MirrorFoldChanges;
import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The suite's {@link FoldChanges}: an ordinary bean over the {@code @DefaultBean} mirror read, so it
 * wins the injection simply by existing — the package's convention for every port a release reaches
 * through.
 *
 * <p><b>Its default answers "the fold changed nothing"</b>, and that is the only default the suite
 * can live with: nearly every release-request test folds through {@link RecordingBackingBranchMerger},
 * whose shas no git repository holds, and the real read would call every one of them unreadable —
 * which the approval policy reads as "ask a person". A test that means a fold to change something
 * scripts it per repository; a test that means the real git read to answer marks the repository
 * {@link #useMirror mirrored}, and this then delegates to {@link MirrorFoldChanges} itself, so the
 * seam behind the fake is exercised rather than assumed.
 */
@ApplicationScoped
public class RecordingFoldChanges implements FoldChanges {

  @Inject MirrorFoldChanges mirror;

  private final Map<String, List<CommitFileChangeDto>> scripted = new ConcurrentHashMap<>();

  private final Map<String, String> unreadable = new ConcurrentHashMap<>();

  private final Set<String> mirrored = ConcurrentHashMap.newKeySet();

  private final Map<String, List<String>> betweenScripted = new ConcurrentHashMap<>();

  private final Set<String> empty = ConcurrentHashMap.newKeySet();

  private final Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();

  /** How many times {@code repoId}'s folds were read since the last reset. */
  public int reads(String repoId) {
    AtomicInteger count = reads.get(repoId);
    return count == null ? 0 : count.get();
  }

  /** Every fold of {@code repoId} changes {@code files}, from now until the next script or reset. */
  public void changes(String repoId, List<CommitFileChangeDto> files) {
    unreadable.remove(repoId);
    empty.remove(repoId);
    scripted.put(repoId, List.copyOf(files));
  }

  /**
   * Every fold of {@code repoId} adds nothing to {@code main} — a request whose branches carry
   * nothing, fast-forwarded onto main's own head — from now until the next script or reset.
   */
  public void empty(String repoId) {
    unreadable.remove(repoId);
    empty.add(repoId);
  }

  /** Every read of {@code repoId}'s folds fails with {@code message}. */
  public void unreadable(String repoId, String message) {
    scripted.remove(repoId);
    empty.remove(repoId);
    unreadable.put(repoId, message);
  }

  /** Answer {@code repoId}'s folds out of its real mirror. */
  public void useMirror(String repoId) {
    mirrored.add(repoId);
  }

  /**
   * Make {@link #pathsBetween} look the repository row up in the {@code projects} datasource first,
   * the way the shipped mirror read does ({@code CommitService.requireMirror}) — so a caller that
   * runs it inside a transaction holding another datasource (the bus claim) fails as it does live.
   */
  public void readsTheRepositoryRow(String repoId) {
    rowReaders.add(repoId);
  }

  private final Set<String> rowReaders = ConcurrentHashMap.newKeySet();

  public void reset() {
    rowReaders.clear();
    scripted.clear();
    betweenScripted.clear();
    unreadable.clear();
    empty.clear();
    mirrored.clear();
    reads.clear();
  }

  @Override
  public List<CommitFileChangeDto> changes(String repoId, String mergedSha, String pathspec) {
    reads.computeIfAbsent(repoId, key -> new AtomicInteger()).incrementAndGet();
    if (mirrored.contains(repoId)) {
      return mirror.changes(repoId, mergedSha, pathspec);
    }
    String failure = unreadable.get(repoId);
    if (failure != null) {
      throw new IllegalStateException(failure);
    }
    return scripted.getOrDefault(repoId, List.of());
  }

  /**
   * Every two-fold read of {@code repoId} answers {@code paths}, from now until the next script or
   * reset. Unscripted, it answers that nothing changed — this suite's folds are shas no repository
   * holds, so the real read would fail every time.
   */
  public void between(String repoId, List<String> paths) {
    betweenScripted.put(repoId, List.copyOf(paths));
  }

  @Override
  public List<String> pathsBetween(String repoId, String foldSha, String previousFoldSha) {
    if (rowReaders.contains(repoId)
        && eu.wohlben.qits.projects.entity.Repository.findByIdOptional(repoId).isEmpty()) {
      throw new IllegalStateException("no repository " + repoId);
    }
    if (mirrored.contains(repoId)) {
      return mirror.pathsBetween(repoId, foldSha, previousFoldSha);
    }
    String failure = unreadable.get(repoId);
    if (failure != null) {
      throw new IllegalStateException(failure);
    }
    return betweenScripted.getOrDefault(repoId, List.of());
  }

  /**
   * Unscripted, every fold adds something to main: the suite's folds are shas no repository holds,
   * and the real read would fail closed into "ask a person" for every request.
   */
  @Override
  public boolean addsNothingToMain(String repoId, String mergedSha) {
    reads.computeIfAbsent(repoId, key -> new AtomicInteger()).incrementAndGet();
    if (mirrored.contains(repoId)) {
      return mirror.addsNothingToMain(repoId, mergedSha);
    }
    String failure = unreadable.get(repoId);
    if (failure != null) {
      throw new IllegalStateException(failure);
    }
    return empty.contains(repoId);
  }

  /** A changed blob, as the mirror read reports one. */
  public static CommitFileChangeDto file(String changeType, String path, String oldPath) {
    return new CommitFileChangeDto(
        path,
        oldPath,
        changeType,
        "ADDED".equals(changeType) ? null : "100644",
        "DELETED".equals(changeType) ? null : "100644",
        null,
        null,
        null);
  }

  /** A moved gitlink. */
  public static CommitFileChangeDto gitlink(String path) {
    return new CommitFileChangeDto(path, null, "MODIFIED", "160000", "160000", null, null, null);
  }
}
