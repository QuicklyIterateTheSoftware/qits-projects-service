package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.error.BadRequestException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The create door's question to the forge, asked before anything is written: does the backup twin
 * a new blank component would be born with actually exist (qits-367)?
 *
 * <p>A twin that does not exist used to be accepted, and the cost landed far from the request that
 * caused it: every backup of the repository failed in a log line nobody reads, and the forge never
 * held a copy. Refusing up front puts the answer in front of the person who can act on it, at the
 * moment they can — create the forge repository, fix the typo, or sign in.
 *
 * <p>The question is {@code git ls-remote}, with the same credentials the backup push itself uses
 * ({@code RepositoryService.backupToTwin}, {@code TagCollector.twinTags}), so "the check passed"
 * and "the backup can reach it" are one fact. An <b>empty</b> forge repository — created and never
 * pushed to — prints nothing and exits 0, and that is the commonest twin of all, so success is the
 * exit code alone.
 *
 * <p>Skipped entirely when backups are switched off ({@code qits.projects.backup.enabled}): a twin
 * nothing will ever push to is not worth refusing a create over.
 */
@ApplicationScoped
public class BackupTwinCheck {

  @Inject GitExecutor git;

  @Inject GitRemoteAuth remoteAuth;

  /** The backup kill switch {@code BackupPushService} honours; off, there is nothing to check. */
  @ConfigProperty(name = "qits.projects.backup.enabled", defaultValue = "true")
  boolean backupEnabled;

  /**
   * How long the forge gets to answer. A person is waiting on the request, and a forge that never
   * answers must become a refusal rather than a hung request thread.
   */
  @ConfigProperty(name = "qits.projects.backup.twin-check-timeout", defaultValue = "30s")
  Duration timeout;

  /**
   * Refuses unless {@code twin} answers an {@code ls-remote}. A null twin — a greenfield wrapper, or
   * one hosted on the platform itself — has nothing to check and passes.
   *
   * @throws BadRequestException naming the twin, both causes a missing one has, and git's own answer
   */
  public void requireExists(String twin, String name) {
    if (twin == null || !backupEnabled) {
      return;
    }
    String failure = probe(twin);
    if (failure != null) {
      throw new BadRequestException(
          "The backup twin "
              + twin
              + " does not answer. It was derived from the wrapper's forge url plus ../"
              + name
              + ".git, and a blank repository is created only once its twin exists. Either the"
              + " forge repository does not exist — create it first, or check the name for a typo —"
              + " or the platform holds no forge credentials for it — sign in to its remote. git: "
              + failure);
    }
  }

  /** Null when the twin answered; otherwise the line that says why not. */
  private String probe(String twin) {
    // ls-remote needs no repository; a scratch directory keeps it from discovering one around the
    // service's working directory.
    Path scratch;
    try {
      scratch = Files.createTempDirectory("qits-twin-check");
    } catch (IOException e) {
      return "could not create a scratch directory: " + e.getMessage();
    }
    try {
      GitExecutor.ExecResult result =
          git.execAllowNonZero(
              scratch.toFile(),
              timeout,
              remoteAuth.gitWithCredentials("ls-remote", "--end-of-options", twin));
      return result.exitCode() == 0 ? null : firstErrorLine(result);
    } catch (TimeoutException e) {
      return e.getMessage();
    } catch (Exception e) {
      return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    } finally {
      deleteQuietly(scratch);
    }
  }

  /** git's first {@code fatal:}/{@code error:} line, else its first line, else the exit code. */
  static String firstErrorLine(GitExecutor.ExecResult result) {
    String[] lines = result.output() == null ? new String[0] : result.output().split("\n");
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.startsWith("fatal:") || trimmed.startsWith("error:")) {
        return trimmed;
      }
    }
    for (String line : lines) {
      if (!line.isBlank()) {
        return line.trim();
      }
    }
    return "exit " + result.exitCode();
  }

  private static void deleteQuietly(Path dir) {
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException ignored) {
      // a leftover empty temp directory is not worth failing a create over
    }
  }
}
