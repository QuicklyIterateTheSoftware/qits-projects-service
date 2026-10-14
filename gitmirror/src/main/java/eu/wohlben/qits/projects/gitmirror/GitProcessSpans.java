package eu.wohlben.qits.projects.gitmirror;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One span per git process (qits-1202): what it is called, what it may say about its argv, and when
 * it counts as failed — the one place those are decided, for both process spawners this repository
 * has, {@link GitCli} here and the domain's {@code GitExecutor}, which reuses it rather than
 * keeping a second copy that could drift.
 *
 * <p><b>The name is {@code git <subcommand>}</b>, the first argument after the binary that is
 * neither an option nor an option's separate value. The low-cardinality half of the call is what a
 * trace view groups by; everything else goes in {@link #ARGS}. A command that is not git at all —
 * the domain's test fixtures hand its executor {@code sh -c …} — is named by its binary's basename,
 * so it still traces and can never be mistaken for a git span.
 *
 * <p><b>{@link #ARGS} is redacted, and the redaction is two rules, both deliberately blunt:</b>
 *
 * <ul>
 *   <li><b>Every {@code -c <value>} pair is dropped whole</b>, wherever it sits — and so is {@code
 *       clone}'s {@code --config <value>} / {@code --config=<value>}. That is where the secrets
 *       travel: {@code RepoMirror.platformArgv} injects {@code -c http.extraHeader= Authorization:
 *       Bearer …} and the domain's {@code GitRemoteAuth} a credential helper, and a config value is
 *       free text no pattern can promise to recognise. Dropping by position rather than by content
 *       is what makes the rule safe against the next {@code -c} somebody adds. The cost is that the
 *       handful of subcommands with a flag spelled {@code -c} and no value ({@code git log -c})
 *       lose the argument after it in the trace — a trace that says too little, never one that says
 *       too much.
 *   <li><b>Any URL's userinfo is stripped</b> — {@code https://user:tok@host/x} becomes {@code
 *       https://host/x} — because a backup remote is a forge URL and the convention of putting a
 *       token there is older than this service.
 * </ul>
 *
 * <p><b>Exit codes 1 to 127 are answers, 128 and above are failures.</b> {@code merge-tree} exits 1
 * on a conflict, {@code rev-parse --verify --quiet} exits 1 on an absent ref and {@code merge-base
 * --is-ancestor} exits 1 on "no" — each is the question being answered, so the span records the
 * code and its status stays unset. 128 is git's own "fatal:" and above it is a signal, and those
 * are the span's status ERROR.
 */
public final class GitProcessSpans {

  /** The argv after the subcommand, redacted as this class's javadoc says. */
  public static final String ARGS = "git.args";

  public static final String EXIT_CODE = "process.exit.code";

  /** The merged stdout/stderr this process produced, in UTF-8 bytes. */
  public static final String OUTPUT_BYTES = "git.output.bytes";

  public static final String TIMEOUT_MS = "git.timeout.ms";

  /**
   * The working directory, for the domain's {@code GitExecutor}, whose calls name no repository.
   */
  public static final String CWD = "git.cwd";

  /** Set by whoever knows which repository a span is about; never inferred from a path. */
  public static final String REPO_ID = "qits.repo.id";

  /** The first exit code git uses for a failure rather than an answer: its own {@code fatal:}. */
  static final int FIRST_FAILURE_EXIT = 128;

  /**
   * The global options that take their value as the NEXT argument, which therefore is not the
   * subcommand. {@code -c} is the one that matters (and the one redacted); the rest are listed so a
   * caller using them cannot turn a path into a span name.
   */
  private static final Set<String> GLOBAL_OPTIONS_WITH_VALUE =
      Set.of("-c", "-C", "--git-dir", "--work-tree", "--namespace", "--config-env");

  private GitProcessSpans() {}

  /**
   * Opens the span for one process, on the calling thread — so it parents to whatever is current
   * there — with the name, the redacted argv, the timeout when there is one, and the caller's own
   * attributes ({@link #REPO_ID}, a working directory).
   *
   * @param tracing null is {@link GitTracing#NONE}
   * @param timeout null for an unbounded local call, which records no {@link #TIMEOUT_MS}
   */
  public static GitTracing.Span start(
      GitTracing tracing, String[] argv, Map<String, String> attributes, Duration timeout) {
    GitTracing tracer = tracing == null ? GitTracing.NONE : tracing;
    Map<String, String> all = new LinkedHashMap<>();
    if (attributes != null) {
      attributes.forEach(
          (key, value) -> {
            if (key != null && value != null) {
              all.put(key, value);
            }
          });
    }
    all.put(ARGS, redactedArgs(argv));
    GitTracing.Span span = tracer.start(spanName(argv), Map.copyOf(all));
    if (timeout != null) {
      span.attribute(TIMEOUT_MS, timeout.toMillis());
    }
    return span;
  }

  /**
   * Records how the process ended: its exit code and output size always, and status ERROR for an
   * exit of {@value #FIRST_FAILURE_EXIT} or above — see this class's javadoc for why the codes
   * below it are not failures.
   */
  public static void exited(GitTracing.Span span, int exitCode, String output) {
    span.attribute(EXIT_CODE, exitCode);
    span.attribute(OUTPUT_BYTES, utf8Length(output));
    if (exitCode >= FIRST_FAILURE_EXIT) {
      span.error("git exited " + exitCode);
    }
  }

  /**
   * The argv as it may be printed anywhere — a span, an exception message: the span name followed
   * by {@link #redactedArgs}. What {@link GitCli}'s timeout says instead of the raw argv, which
   * carries the platform bearer on every call to the git host.
   */
  public static String describe(String... argv) {
    String args = redactedArgs(argv);
    return args.isEmpty() ? spanName(argv) : spanName(argv) + " " + args;
  }

  /**
   * {@code git <subcommand>}, {@code git} when no subcommand can be found, or the basename of a
   * binary that is not git.
   */
  public static String spanName(String... argv) {
    if (argv == null || argv.length == 0) {
      return "git";
    }
    String binary = new File(argv[0]).getName();
    if (!"git".equals(binary)) {
      return binary;
    }
    int sub = subcommandIndex(argv);
    return sub < 0 ? "git" : "git " + argv[sub];
  }

  /**
   * The arguments after the subcommand — after the binary, for a command that is not git — joined
   * by spaces, with every {@code -c <value>} pair dropped and every URL's userinfo stripped.
   */
  public static String redactedArgs(String... argv) {
    if (argv == null || argv.length == 0) {
      return "";
    }
    int from;
    if ("git".equals(new File(argv[0]).getName())) {
      int sub = subcommandIndex(argv);
      from = sub < 0 ? argv.length : sub + 1;
    } else {
      from = 1;
    }
    List<String> kept = new ArrayList<>();
    for (int i = from; i < argv.length; i++) {
      String arg = argv[i];
      if ("-c".equals(arg) || "--config".equals(arg)) {
        i++; // the value goes with it, whatever it says
        continue;
      }
      if (arg.startsWith("--config=")) {
        continue; // clone's long spelling of the same pair, as one argument
      }
      kept.add(stripUserinfo(arg));
    }
    return String.join(" ", kept);
  }

  /**
   * {@code scheme://user:secret@host/path} with the {@code user:secret@} removed; anything else
   * unchanged. Every URL in the argument is treated, not only one at its start, so a {@code
   * --url=https://…} or a refspec-shaped value cannot carry one through.
   */
  public static String stripUserinfo(String arg) {
    if (arg == null || !arg.contains("://")) {
      return arg;
    }
    StringBuilder out = new StringBuilder(arg.length());
    int cursor = 0;
    int marker;
    while ((marker = arg.indexOf("://", cursor)) >= 0) {
      int authority = marker + 3;
      int end = authority;
      while (end < arg.length() && "/?# ".indexOf(arg.charAt(end)) < 0) {
        end++;
      }
      int at = arg.lastIndexOf('@', end - 1);
      out.append(arg, cursor, authority);
      cursor = at >= authority ? at + 1 : authority;
    }
    out.append(arg, cursor, arg.length());
    return out.toString();
  }

  /** The index of the subcommand in a git argv, or -1 when there is none. */
  static int subcommandIndex(String[] argv) {
    for (int i = 1; i < argv.length; i++) {
      String arg = argv[i];
      if (GLOBAL_OPTIONS_WITH_VALUE.contains(arg)) {
        i++;
      } else if (!arg.startsWith("-")) {
        return i;
      }
    }
    return -1;
  }

  /** Counted rather than encoded, so a large output is not copied a second time to be measured. */
  static long utf8Length(String s) {
    if (s == null) {
      return 0;
    }
    long bytes = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x80) {
        bytes += 1;
      } else if (c < 0x800) {
        bytes += 2;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < s.length()
          && Character.isLowSurrogate(s.charAt(i + 1))) {
        bytes += 4;
        i++;
      } else {
        bytes += 3;
      }
    }
    return bytes;
  }
}
