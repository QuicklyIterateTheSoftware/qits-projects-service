package eu.wohlben.qits.projects.gitmirror;

import java.util.Map;

/**
 * The port a git process, a mirror refresh and a gate resolution are traced through (qits-1202) —
 * declared here, implemented in the deployable, and absent everywhere else.
 *
 * <p><b>A port and not a dependency, for the reason this module's pom gives at length.</b> {@code
 * gitmirror} depends on nothing, so that its suite runs offline against throwaway bares with no
 * container and no augmentation, and so that it could move into a daemon of its own without a
 * rewrite. OpenTelemetry would be the first framework on its classpath, and with it a second reason
 * for the module's tests to know what a tracer is. So the module names only the shape it needs —
 * open a span, say things about it, mark it failed, close it — and the service's {@code
 * telemetry.OtelGitTracing} is the one class that turns that into OpenTelemetry. {@link #NONE} is
 * what every caller with nothing to offer gets, this module's own suite and {@code new GitCli()}
 * among them, so that "untraced" needs no ceremony anywhere.
 *
 * <p><b>Nesting is the implementation's job, and it is a contract rather than a nicety.</b> {@link
 * #start} must open the span as a child of whatever is current on the calling thread <em>and make
 * it current itself</em> until {@link Span#close}, so a span started while another is open parents
 * to it with nobody passing a context around: the {@code git fetch} under {@code
 * git.mirror.refresh}, the git host's HTTP spans under {@code release-gates.resolve}. That is also
 * why every caller starts the span on the thread that asked, and never on the drain thread {@link
 * GitCli} reads the process's output on.
 *
 * <p><b>Attributes are strings and longs and nothing else</b>, and a value is whatever the caller
 * says it is — this port redacts nothing. What must never reach a span (a bearer in a {@code -c
 * http.extraHeader}, a credential helper, a token in a URL's userinfo) is removed before it gets
 * here, in one place: {@link GitProcessSpans}.
 */
public interface GitTracing {

  /** Traces nothing: every span it opens is {@link Span#NONE}. */
  GitTracing NONE = (name, attributes) -> Span.NONE;

  /**
   * Opens a span named {@code name}, as a child of the calling thread's current one, and makes it
   * current until {@link Span#close}.
   *
   * @param attributes set at start; may be empty, never null
   */
  Span start(String name, Map<String, String> attributes);

  /**
   * One open span. Close it on the thread that opened it, exactly once — try-with-resources is the
   * shape every caller in this repository uses — because closing is what restores the context that
   * was current before it.
   */
  interface Span extends AutoCloseable {

    /** The span that records nothing. */
    Span NONE =
        new Span() {
          @Override
          public void attribute(String key, String value) {}

          @Override
          public void attribute(String key, long value) {}

          @Override
          public void error(Throwable failure) {}

          @Override
          public void error(String description) {}

          @Override
          public void close() {}
        };

    void attribute(String key, String value);

    void attribute(String key, long value);

    /**
     * Marks the span failed and records {@code failure} on it. Only for a throwable whose message
     * is known to carry no secret — a git invocation's argv is not one: see {@link #error(String)}.
     */
    void error(Throwable failure);

    /** Marks the span failed with a description and no exception — the redacted path. */
    void error(String description);

    /** Ends the span and restores the context that was current when it started. Never throws. */
    @Override
    void close();
  }
}
