package eu.wohlben.qits.entities.control;

/**
 * How a reader names whoever changed an assignee (qits-2518): asked by {@link AssigneeWrites} when
 * a change is first owed — inside the write and its request, so the answer may read the caller's
 * identity and the database — and carried on {@link AssigneeChange#changedByName()}, because the
 * after-commit that announces it has neither to read.
 *
 * <p>A port for {@link AssigneeAnnouncer}'s reason: the caller's identity, its agent and that
 * agent's work item are {@code service}'s to read. <b>Absent is a supported configuration</b> — the
 * name is then {@code changedBy} — and <b>nothing here may throw</b>: it is called inside a write
 * that must not fail for a name.
 */
public interface AssignerNames {

  /**
   * The name a reader knows {@code changedBy} by.
   *
   * @param changedBy the principal of the write, possibly null
   * @return the name, or null when there is nothing better than {@code changedBy}
   */
  String nameOf(String changedBy);
}
