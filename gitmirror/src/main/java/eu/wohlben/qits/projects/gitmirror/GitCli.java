package eu.wohlben.qits.projects.gitmirror;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Spawning a git process, and nothing else.
 *
 * <p><b>The timeout is for network calls only.</b> A {@code null} timeout runs {@code p.waitFor()}
 * unbounded, which is survivable for a local repository operation — git either finishes or the disk
 * is gone — and is not survivable for a fetch, a push or an {@code ls-remote}, where a wedged host
 * would pin a request thread forever. Every wire call this module makes passes one; every local one
 * does not.
 *
 * <p>The bound covers the <b>whole</b> invocation and not only the exit status. A transport that
 * accepts the connection and then says nothing blocks in {@code readLine()}, never in {@code
 * waitFor()}, so the output is drained on its own thread and the deadline is enforced against the
 * process — {@code destroyForcibly} closes the pipe, which is what unblocks the drain.
 *
 * <p><b>Every invocation is one span</b> (qits-1202), opened through the {@link GitTracing} this
 * instance was built with and shaped by {@link GitProcessSpans}: named {@code git <subcommand>},
 * argv redacted, exit code and output size recorded, status ERROR on a throw, a timeout or an exit
 * of 128 and above. It is opened on the calling thread before the process starts — never on the
 * drain thread — so it parents to whatever that thread was doing.
 */
public final class GitCli {

  /** The exit code and combined stdout/stderr of a finished git invocation. */
  public record Result(int exitCode, String output) {}

  private final GitTracing tracing;

  /** An untraced spawner — {@link GitTracing#NONE} — for every caller with no tracer to offer. */
  public GitCli() {
    this(GitTracing.NONE);
  }

  /**
   * @param tracing where each invocation's span goes; null is {@link GitTracing#NONE}
   */
  public GitCli(GitTracing tracing) {
    this.tracing = tracing == null ? GitTracing.NONE : tracing;
  }

  /**
   * The tracer this spawner was built with — never null. {@link RepoMirror} opens its {@code
   * git.mirror.refresh} span through it, which is how that span reaches the same tracer as the
   * processes nested under it without a second constructor parameter threaded through {@link
   * GitMirrors}.
   */
  public GitTracing tracing() {
    return tracing;
  }

  /** How long to wait for the drain thread to finish after the process has settled. */
  private static final Duration DRAIN_JOIN = Duration.ofSeconds(5);

  /**
   * qits-1066: git 2.47+ runs {@code git maintenance run --auto --detach} after almost every
   * command (fetch, commit, merge, …), and the detached grandchild is reparented to this
   * process's PID 1 — a native Quarkus binary that never reaps — which is how this service piled
   * up thousands of zombie {@code git} processes. These two flags keep any auto-gc or
   * auto-maintenance such an invocation triggers running in the FOREGROUND, as a child this very
   * call waits for, instead of detaching. {@code gc.auto}/{@code maintenance.auto} themselves are
   * deliberately left alone — a bare mirror still needs its housekeeping to run, only not as an
   * orphan.
   */
  static final List<String> NO_DETACHED_MAINTENANCE =
      List.of("-c", "gc.autoDetach=false", "-c", "maintenance.autoDetach=false");

  /**
   * Splices {@link #NO_DETACHED_MAINTENANCE} in right after the binary (index 0) and before
   * whatever the caller asked for — a subcommand, or any {@code -c} flags the caller already
   * added ({@link RepoMirror}'s causation and bearer headers) — so the guard always lands ahead
   * of the subcommand, as it must. Package-private so a test can assert the built argv directly.
   */
  static String[] withMaintenanceGuard(String[] argv) {
    // Only a git invocation: a caller may hand this a non-git command (a test fixture's
    // `sh -c ...`), whose own `-c` would otherwise swallow the guard as its script.
    if (argv.length == 0 || !"git".equals(new File(argv[0]).getName())) {
      return argv;
    }
    String[] result = new String[argv.length + NO_DETACHED_MAINTENANCE.size()];
    result[0] = argv[0];
    for (int i = 0; i < NO_DETACHED_MAINTENANCE.size(); i++) {
      result[1 + i] = NO_DETACHED_MAINTENANCE.get(i);
    }
    System.arraycopy(argv, 1, result, 1 + NO_DETACHED_MAINTENANCE.size(), argv.length - 1);
    return result;
  }

  /**
   * @param cwd the working directory, or null to inherit this process's
   * @param env an overlay applied last, so a supplied {@code GIT_AUTHOR_*}/{@code GIT_COMMITTER_*}
   *     replaces any ambient identity env inherited from the host (env outranks {@code -c} config)
   * @param onLine an optional per-line tap, invoked as each merged stdout/stderr line arrives
   * @param timeout a wall-clock bound, or null for the unbounded local-operation behaviour
   * @throws TimeoutException when the deadline passes; the process is killed first, so nothing is
   *     left running behind the failure
   */
  public Result run(
      File cwd, Map<String, String> env, Consumer<String> onLine, Duration timeout, String... argv)
      throws Exception {
    return run(Map.of(), cwd, env, onLine, timeout, argv);
  }

  /**
   * {@link #run(File, Map, Consumer, Duration, String...)}, with attributes for this invocation's
   * span that only the caller knows — {@link GitProcessSpans#REPO_ID} is the one {@link RepoMirror}
   * passes on every call.
   *
   * @param spanAttributes added to the span as given; they are NOT redacted, so nothing taken from
   *     the argv belongs here
   */
  public Result run(
      Map<String, String> spanAttributes,
      File cwd,
      Map<String, String> env,
      Consumer<String> onLine,
      Duration timeout,
      String... argv)
      throws Exception {
    try (GitTracing.Span span = GitProcessSpans.start(tracing, argv, spanAttributes, timeout)) {
      Result result;
      try {
        result = execute(cwd, env, onLine, timeout, argv);
      } catch (TimeoutException e) {
        // The message is already redacted (see execute), but the description says only what the
        // span needs and stays safe should that message ever change.
        span.error("git timed out after " + timeout);
        throw e;
      } catch (Exception e) {
        span.error(e);
        throw e;
      }
      GitProcessSpans.exited(span, result.exitCode(), result.output());
      return result;
    }
  }

  private Result execute(
      File cwd, Map<String, String> env, Consumer<String> onLine, Duration timeout, String... argv)
      throws Exception {
    ProcessBuilder pb = new ProcessBuilder(withMaintenanceGuard(argv));
    if (cwd != null) {
      pb.directory(cwd);
    }
    // Never let a transport prompt: there is no TTY here and the unbounded overload has no timeout,
    // so a git that decides to ask for credentials would block forever. With the flag a missing
    // credential is an immediate, classifiable exit 128 ("could not read Username ...") instead.
    pb.environment().put("GIT_TERMINAL_PROMPT", "0");
    // Force English output regardless of the host locale: callers classify failures (e.g.
    // non-fast-forward rejection, a branch that already exists) by matching substrings in git's
    // message, which would silently miss a localized translation.
    pb.environment().put("LC_ALL", "C");
    pb.environment().put("LANG", "C");
    if (env != null) {
      env.forEach(pb.environment()::put);
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    if (timeout == null) {
      String output = drain(p, onLine);
      return new Result(p.waitFor(), output);
    }
    // Bounded: the drain has to run somewhere other than the thread holding the deadline, or a
    // silent-but-open transport parks in readLine() and the deadline is never consulted.
    StringBuilder collected = new StringBuilder();
    Thread drain =
        new Thread(
            () -> {
              try {
                collected.append(drain(p, onLine));
              } catch (Exception ignored) {
                // the pipe closing under a destroyForcibly is the expected end of this thread
              }
            },
            "git-exec-drain");
    drain.setDaemon(true);
    drain.start();
    boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      p.destroyForcibly();
      drain.join(DRAIN_JOIN.toMillis());
      // Redacted, not String.join(argv): every call to the git host carries the platform bearer as
      // a -c http.extraHeader, and this message travels into exceptions, logs and spans.
      throw new TimeoutException(
          "git timed out after " + timeout + ": " + GitProcessSpans.describe(argv));
    }
    drain.join(DRAIN_JOIN.toMillis());
    return new Result(p.exitValue(), collected.toString());
  }

  private static String drain(Process p, Consumer<String> onLine) throws Exception {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
      if (onLine == null) {
        return reader.lines().collect(Collectors.joining("\n"));
      }
      // Read line by line so the tap sees each line as it arrives (readLine also splits on a bare
      // `\r`, so git's in-place progress updates stream through too, and it yields the final
      // unterminated line so nothing is dropped). Still accumulate the full joined output.
      StringBuilder collected = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        if (collected.length() > 0) {
          collected.append('\n');
        }
        collected.append(line);
        try {
          onLine.accept(line);
        } catch (RuntimeException ignored) {
          // the tap is observational only — a failing sink must not abort the git command
        }
      }
      return collected.toString();
    }
  }
}
