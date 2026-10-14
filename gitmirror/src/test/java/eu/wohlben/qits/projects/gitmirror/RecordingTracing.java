package eu.wohlben.qits.projects.gitmirror;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The test-side {@link GitTracing}: every span kept, in start order, with what was said about it.
 *
 * <p>It honours the port's one structural promise the way the OpenTelemetry implementation does — a
 * span is current on its thread from {@link #start} to {@link GitTracing.Span#close} — by keeping a
 * per-thread stack, so {@link Recorded#parent} is exactly what OpenTelemetry would have parented
 * the span to. That is what lets this module prove nesting with no tracer on its classpath.
 */
final class RecordingTracing implements GitTracing {

  /** One span as it was recorded. Values are {@code String} or {@code Long}. */
  static final class Recorded implements Span {
    final String name;
    final Recorded parent;
    final Map<String, Object> attributes = Collections.synchronizedMap(new LinkedHashMap<>());
    final List<Object> errors = Collections.synchronizedList(new ArrayList<>());
    final String thread = Thread.currentThread().getName();
    private final Deque<Recorded> stack;
    volatile boolean closed;

    private Recorded(String name, Recorded parent, Deque<Recorded> stack) {
      this.name = name;
      this.parent = parent;
      this.stack = stack;
    }

    @Override
    public void attribute(String key, String value) {
      attributes.put(key, value);
    }

    @Override
    public void attribute(String key, long value) {
      attributes.put(key, value);
    }

    @Override
    public void error(Throwable failure) {
      errors.add(failure);
    }

    @Override
    public void error(String description) {
      errors.add(description);
    }

    boolean failed() {
      return !errors.isEmpty();
    }

    @Override
    public void close() {
      closed = true;
      stack.remove(this);
    }

    @Override
    public String toString() {
      return name + attributes + (failed() ? " ERROR" + errors : "");
    }
  }

  private final ThreadLocal<Deque<Recorded>> current = ThreadLocal.withInitial(ArrayDeque::new);
  final List<Recorded> spans = Collections.synchronizedList(new ArrayList<>());

  @Override
  public Span start(String name, Map<String, String> attributes) {
    Deque<Recorded> stack = current.get();
    Recorded span = new Recorded(name, stack.peek(), stack);
    span.attributes.putAll(attributes);
    stack.push(span);
    spans.add(span);
    return span;
  }

  List<Recorded> named(String name) {
    synchronized (spans) {
      return spans.stream().filter(s -> s.name.equals(name)).toList();
    }
  }

  Recorded only(String name) {
    List<Recorded> found = named(name);
    if (found.size() != 1) {
      throw new AssertionError("expected one span named " + name + ", got " + spans);
    }
    return found.get(0);
  }
}
