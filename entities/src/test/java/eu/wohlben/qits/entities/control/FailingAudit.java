package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An audit log that fails when it records one armed entity (qits-1685). The audit row is written
 * inside the move's transaction, after the rows before it changed, so a failure here shows whether
 * the whole move rolls back. Enabled by one test profile only.
 */
@Alternative
@ApplicationScoped
public class FailingAudit extends AuditService {

  /** The message the armed failure throws. */
  public static final String FAILED = "the audit row was refused";

  private final AtomicReference<String> armed = new AtomicReference<>();

  /** The next audit row for {@code entityId} fails. */
  public void failFor(String entityId) {
    armed.set(entityId);
  }

  /** Clears the arm. */
  public void healthy() {
    armed.set(null);
  }

  @Override
  public void record(
      AuditEntityType entityType,
      String entityId,
      String epicId,
      AuditOperation operation,
      String changedBy,
      Object snapshot) {
    if (entityId.equals(armed.get())) {
      armed.set(null);
      throw new IllegalStateException(FAILED);
    }
    super.record(entityType, entityId, epicId, operation, changedBy, snapshot);
  }
}
