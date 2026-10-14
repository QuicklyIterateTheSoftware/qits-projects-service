package eu.wohlben.qits.projects.telemetry;

import eu.wohlben.qits.projects.gitmirror.GitTracing;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

/**
 * {@code gitmirror}'s {@link GitTracing} port over OpenTelemetry (qits-1202) — the one class in
 * this repository that knows a git process span is an OpenTelemetry span.
 *
 * <p>It is found by the domain through three optional {@code Instance<GitTracing>} ports — {@code
 * GitMirrorRegistry} (every mirror's processes and its {@code git.mirror.refresh}), {@code
 * GitExecutor} (every other git process) and {@code ReleaseGates} ({@code release-gates.resolve}) —
 * so a domain suite without this module traces nothing and needs nothing.
 *
 * <p><b>Current while open, and that is the point of the class.</b> {@link #start} opens the span
 * as a child of {@link Context#current()} and makes it current on the calling thread; {@link
 * GitTracing.Span#close} closes that scope <em>first</em> and ends the span after. That is what
 * nests a {@code git fetch} under the {@code git.mirror.refresh} that ran it, and the git host's
 * HTTP client spans (which parent to {@code Context.current()} like any instrumented client) under
 * {@code release-gates.resolve}, without a context passed through a single signature. The order of
 * the two closes matters: ending first and closing the scope after would leave an ended span
 * current for the instant between, and a scope closed on a thread other than the opener's corrupts
 * that thread's context — which is why the port says to close on the opening thread.
 *
 * <p><b>The {@code Tracer} is Quarkus' own bean</b>, the same SDK the HTTP and JDBC spans go
 * through, so these spans export wherever those do. Under {@code %test} the SDK is disabled and the
 * tracer is a no-op: the spans cost nothing in the suite, and {@code OtelGitTracingTest} proves the
 * behaviour against an in-memory SDK of its own instead.
 *
 * <p>No reflection, no proxies, no {@code ServiceLoader} — nothing the native image has to be told.
 */
@ApplicationScoped
public class OtelGitTracing implements GitTracing {

  private final Tracer tracer;

  @Inject
  public OtelGitTracing(Tracer tracer) {
    this.tracer = tracer;
  }

  @Override
  public GitTracing.Span start(String name, Map<String, String> attributes) {
    SpanBuilder builder =
        tracer.spanBuilder(name).setParent(Context.current()).setSpanKind(SpanKind.INTERNAL);
    if (attributes != null) {
      attributes.forEach(
          (key, value) -> {
            if (key != null && value != null) {
              builder.setAttribute(key, value);
            }
          });
    }
    io.opentelemetry.api.trace.Span span = builder.startSpan();
    return new Open(span, span.makeCurrent());
  }

  /**
   * One span and the scope that made it current. (OpenTelemetry's {@code Span} is spelled out in
   * full here because the port's own {@code Span}, inherited as a member type, shadows the import.)
   */
  private static final class Open implements GitTracing.Span {

    private final io.opentelemetry.api.trace.Span span;
    private final Scope scope;
    private boolean closed;

    private Open(io.opentelemetry.api.trace.Span span, Scope scope) {
      this.span = span;
      this.scope = scope;
    }

    @Override
    public void attribute(String key, String value) {
      if (key != null && value != null) {
        span.setAttribute(key, value);
      }
    }

    @Override
    public void attribute(String key, long value) {
      if (key != null) {
        span.setAttribute(key, value);
      }
    }

    @Override
    public void error(Throwable failure) {
      if (failure == null) {
        error("failed");
        return;
      }
      span.recordException(failure);
      span.setStatus(
          StatusCode.ERROR,
          failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage());
    }

    @Override
    public void error(String description) {
      span.setStatus(StatusCode.ERROR, description == null ? "" : description);
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      try {
        scope.close();
      } finally {
        span.end();
      }
    }
  }
}
