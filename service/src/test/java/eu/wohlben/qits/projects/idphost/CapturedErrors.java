package eu.wohlben.qits.projects.idphost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jboss.logmanager.ExtLogRecord;

/**
 * The ERROR records one class logs while this is open. Surefire installs the JBoss LogManager as
 * the JUL manager, so a JUL handler on the class's logger sees what its JBoss Logging logger writes.
 */
final class CapturedErrors implements AutoCloseable {

  private final Logger logger;
  private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

  private final Handler handler =
      new Handler() {
        @Override
        public void publish(LogRecord record) {
          if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
            messages.add(
                record instanceof ExtLogRecord ext ? ext.getFormattedMessage() : record.getMessage());
          }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  CapturedErrors(Class<?> source) {
    logger = Logger.getLogger(source.getName());
    logger.addHandler(handler);
  }

  List<String> messages() {
    return List.copyOf(messages);
  }

  @Override
  public void close() {
    logger.removeHandler(handler);
  }
}
