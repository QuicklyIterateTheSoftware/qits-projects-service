package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.gitmirror.GitTracing;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A recording {@link GitTracing} for this module's unit tests: every span kept in start order, its
 * attributes, whether it failed, and the span that was open on the thread when it started — the
 * parent OpenTelemetry would have given it, which is what lets a test here prove nesting with no
 * tracer on the classpath. {@code gitmirror} keeps its own copy for the same reason this one
 * exists: neither module's test classes reach the other's.
 */
final class StackTracing implements GitTracing {

  static final class Recorded implements Span {
    final String name;
    final Recorded parent;
    final Map<String, Object> attributes = new LinkedHashMap<>();
    final List<Object> errors = new ArrayList<>();
    private final Deque<Recorded> stack;
    boolean closed;

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

    @Override
    public void close() {
      closed = true;
      stack.remove(this);
    }

    @Override
    public String toString() {
      return name + attributes + (errors.isEmpty() ? "" : " ERROR" + errors);
    }
  }

  private final Deque<Recorded> stack = new ArrayDeque<>();
  final List<Recorded> spans = new ArrayList<>();

  @Override
  public Span start(String name, Map<String, String> attributes) {
    Recorded span = new Recorded(name, stack.peek(), stack);
    span.attributes.putAll(attributes);
    stack.push(span);
    spans.add(span);
    return span;
  }

  Recorded only(String name) {
    List<Recorded> found = spans.stream().filter(s -> s.name.equals(name)).toList();
    if (found.size() != 1) {
      throw new AssertionError("expected one span named " + name + ", got " + spans);
    }
    return found.get(0);
  }
}
