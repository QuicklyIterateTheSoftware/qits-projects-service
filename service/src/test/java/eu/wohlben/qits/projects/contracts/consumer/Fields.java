package eu.wohlben.qits.projects.contracts.consumer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Sets the injected fields of a client built with {@code new}, the way each client's own unit test
 * does from inside its package. The consumer rows live in one package and reach clients in many, so
 * they set the same fields by name.
 */
final class Fields {

  private Fields() {}

  /** {@code target} with each (name, value) pair set; fails naming a field that does not exist. */
  static <T> T with(T target, Object... namesAndValues) {
    if (namesAndValues.length % 2 != 0) {
      throw new IllegalArgumentException("names and values come in pairs");
    }
    for (int i = 0; i < namesAndValues.length; i += 2) {
      set(target, (String) namesAndValues[i], namesAndValues[i + 1]);
    }
    return target;
  }

  static void set(Object target, String name, Object value) {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
        return;
      } catch (NoSuchFieldException next) {
        // try the superclass
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
    throw new IllegalArgumentException(target.getClass().getName() + " has no field " + name);
  }

  /** Calls a method that is not public, for a client whose entry point is package-private. */
  static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, types);
    method.setAccessible(true);
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw e;
    }
  }
}
