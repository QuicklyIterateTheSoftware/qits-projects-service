package eu.wohlben.qits.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.contracts.GoldenFiles;
import eu.wohlben.qits.projectsdaemon.protocol.DeskBrief;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * <b>The brief has one owner</b> (qits-1734): the desk is sent to its brief by {@link
 * DeskBrief#pointer} — run {@code qits help guide}, with the guide-hash — and not by a file the
 * daemon used to write into the container. Two things must therefore never appear in this repository
 * again: the retired brief file's path, and the instruction sentence written out by hand (it lives in
 * the protocol jar alone, so find-usages on {@link DeskBrief} lists every prompt that relies on it).
 *
 * <p>Walks every module's {@code src/main} and {@code src/test}, the golden masters, {@code docs/}
 * and the top-level markdown, skipping {@code target} and {@code node_modules}. The instruction
 * check exempts the golden masters: they record what the service answered, and a message composed
 * from {@link DeskBrief} carries the sentence by design. Both needles are composed here rather than
 * written out, so this file does not trip its own scan.
 */
class BriefSourceScanTest {

  /** The retired brief file, built so the literal appears nowhere in the tree. */
  private static final String RETIRED_BRIEF_FILE = "/tmp/" + "qits-delivery" + "-brief.md";

  private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "target", "node_modules");

  @Test
  void noFileNamesTheRetiredBriefFileOrSpellsTheInstructionOut() throws IOException {
    Path root = GoldenFiles.repositoryRoot();
    List<Path> scanned = scannedFiles(root);
    assertTrue(scanned.size() > 100, "the scan found the sources: " + scanned.size());
    List<String> offenders = new ArrayList<>();
    for (Path file : scanned) {
      String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
      String where = root.relativize(file).toString();
      if (text.contains(RETIRED_BRIEF_FILE)) {
        offenders.add(where + ": names the retired brief file");
      }
      // A golden master records what the service answered, and a trigger message composed from
      // DeskBrief carries the sentence by design: only hand-written files must not spell it.
      if (!where.replace('\\', '/').startsWith("golden-masters/")
          && text.contains(DeskBrief.INSTRUCTION)) {
        offenders.add(where + ": spells DeskBrief.INSTRUCTION out instead of composing it");
      }
    }
    assertEquals(List.of(), offenders);
  }

  private static List<Path> scannedFiles(Path root) throws IOException {
    List<Path> files = new ArrayList<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            Path name = dir.getFileName();
            return name != null && !dir.equals(root) && SKIPPED_DIRECTORIES.contains(name.toString())
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile() && scanned(root.relativize(file))) {
              files.add(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return files;
  }

  /** Whether a path, relative to the repository root, is one this scan reads. */
  private static boolean scanned(Path relative) {
    String path = relative.toString().replace('\\', '/');
    if (relative.getNameCount() == 1) {
      return path.endsWith(".md");
    }
    return path.startsWith("golden-masters/")
        || path.startsWith("docs/")
        || path.contains("/src/main/")
        || path.contains("/src/test/");
  }
}
