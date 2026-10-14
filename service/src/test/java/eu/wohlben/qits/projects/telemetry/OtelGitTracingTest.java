package eu.wohlben.qits.projects.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.projects.gitmirror.GitCli;
import eu.wohlben.qits.projects.gitmirror.GitProcessSpans;
import eu.wohlben.qits.projects.gitmirror.GitTracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link OtelGitTracing}'s one structural promise (qits-1202): a span it opens is a child of what
 * is current, and is itself current until closed — so a git process span lands under the request or
 * refresh that ran it, and spans opened inside it land under it.
 *
 * <p><b>A plain unit test, not a {@code @QuarkusTest}, on purpose.</b> {@code %test} disables the
 * Quarkus SDK, so the CDI {@link Tracer} records nothing; this builds an {@link SdkTracerProvider}
 * over an {@link InMemorySpanExporter} instead and hands its tracer to the constructor, which is
 * the whole of what CDI does for the bean.
 */
class OtelGitTracingTest {

  @TempDir Path tmp;

  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private final SdkTracerProvider provider =
      SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
  private final Tracer tracer = provider.get("qits-projects-test");
  private final OtelGitTracing tracing = new OtelGitTracing(tracer);

  @AfterEach
  void shutDown() {
    provider.close();
  }

  private SpanData finished(String name) {
    List<SpanData> found =
        exporter.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).toList();
    assertEquals(1, found.size(), "one " + name + " in " + exporter.getFinishedSpanItems());
    return found.get(0);
  }

  @Test
  void aGitSpanStartedInsideAParentIsItsChild() throws Exception {
    Span request = tracer.spanBuilder("GET /projects").startSpan();
    try (Scope ignored = request.makeCurrent()) {
      new GitCli(tracing)
          .run(
              Map.of(GitProcessSpans.REPO_ID, "repo-1"),
              tmp.toFile(),
              null,
              null,
              null,
              "git",
              "init",
              "--bare",
              "--quiet");
    } finally {
      request.end();
    }

    SpanData git = finished("git init");
    assertEquals(request.getSpanContext().getSpanId(), git.getParentSpanId());
    assertEquals(request.getSpanContext().getTraceId(), git.getTraceId());
    assertEquals(SpanKind.INTERNAL, git.getKind());
    assertEquals(
        "repo-1", git.getAttributes().get(AttributeKey.stringKey(GitProcessSpans.REPO_ID)));
    assertEquals(0L, git.getAttributes().get(AttributeKey.longKey(GitProcessSpans.EXIT_CODE)));
    assertEquals(StatusCode.UNSET, git.getStatus().getStatusCode());
  }

  @Test
  void nestedStartsParentToTheOpenSpanAndCloseRestoresTheOuterOne() {
    try (GitTracing.Span outer = tracing.start("git.mirror.refresh", Map.of())) {
      try (GitTracing.Span inner = tracing.start("git fetch", Map.of())) {
        inner.attribute(GitProcessSpans.EXIT_CODE, 0);
      }
      // the inner close restored the outer span as current, so a sibling parents to it too
      try (GitTracing.Span sibling = tracing.start("git rev-parse", Map.of())) {
        sibling.attribute(GitProcessSpans.EXIT_CODE, 0);
      }
    }

    SpanData outer = finished("git.mirror.refresh");
    assertFalse(outer.getParentSpanContext().isValid(), "the refresh is a root here");
    assertEquals(outer.getSpanId(), finished("git fetch").getParentSpanId());
    assertEquals(outer.getSpanId(), finished("git rev-parse").getParentSpanId());
    assertFalse(Span.current().getSpanContext().isValid(), "nothing is left current behind");
  }

  @Test
  void errorMarksTheSpanFailedWithAndWithoutAnException() {
    try (GitTracing.Span span = tracing.start("git fetch", Map.of())) {
      span.error(new IllegalStateException("the host went away"));
    }
    try (GitTracing.Span span = tracing.start("git push", Map.of())) {
      span.error("git exited 128");
    }
    try (GitTracing.Span span = tracing.start("git ls-remote", Map.of())) {
      span.attribute("git.args", "origin");
    }

    SpanData thrown = finished("git fetch");
    assertEquals(StatusCode.ERROR, thrown.getStatus().getStatusCode());
    assertEquals("the host went away", thrown.getStatus().getDescription());
    assertEquals("exception", thrown.getEvents().get(0).getName());

    SpanData exited = finished("git push");
    assertEquals(StatusCode.ERROR, exited.getStatus().getStatusCode());
    assertEquals("git exited 128", exited.getStatus().getDescription());
    assertEquals(0, exited.getEvents().size(), "no exception to record");

    SpanData fine = finished("git ls-remote");
    assertEquals(StatusCode.UNSET, fine.getStatus().getStatusCode());
    assertNull(fine.getAttributes().get(AttributeKey.longKey(GitProcessSpans.EXIT_CODE)));
  }

  @Test
  void aSecondCloseIsHarmless() {
    GitTracing.Span span = tracing.start("git gc", Map.of());
    span.close();
    span.close();
    assertEquals(1, exporter.getFinishedSpanItems().size());
  }
}
