package eu.wohlben.qits.projects.control;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;

/**
 * An {@link Instance} that resolves to one fixed object, for a plain unit test that builds a bean
 * with {@code new} and wants one of its optional ports present without booting CDI. Only the
 * resolution half is real; selecting by qualifier and destroying answer as a lone bean would.
 */
final class FixedInstance<T> implements Instance<T> {

  private final T value;

  FixedInstance(T value) {
    this.value = value;
  }

  @Override
  public T get() {
    return value;
  }

  @Override
  public Instance<T> select(Annotation... qualifiers) {
    return this;
  }

  @Override
  public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
    throw new UnsupportedOperationException("not needed by any test");
  }

  @Override
  public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
    throw new UnsupportedOperationException("not needed by any test");
  }

  @Override
  public boolean isUnsatisfied() {
    return false;
  }

  @Override
  public boolean isAmbiguous() {
    return false;
  }

  @Override
  public void destroy(T instance) {}

  @Override
  public Handle<T> getHandle() {
    throw new UnsupportedOperationException("not needed by any test");
  }

  @Override
  public Iterable<? extends Handle<T>> handles() {
    throw new UnsupportedOperationException("not needed by any test");
  }

  @Override
  public Iterator<T> iterator() {
    return List.of(value).iterator();
  }
}
