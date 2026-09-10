package eu.wohlben.qits.epics.error;

/**
 * A write carrying a version that is no longer the current one — 409, and <b>carrying the row as it
 * stands now</b>.
 *
 * <p>The payload is the point. On the dossier route nobody accepts a write, so a person editing in
 * the SPA while an agent writes from a prompt is the ordinary case rather than an edge one. A bare
 * 409 leaves the caller holding text it composed and no idea what it would have overwritten; this
 * one hands back the current page so the tab can show both sides and an agent can re-read and
 * retry. Never a silent merge: choosing whose sentence wins is not a decision this layer is
 * entitled to make.
 */
public class StaleWriteException extends ConflictException {

  private final transient Object current;

  public StaleWriteException(String message, Object current) {
    super(message);
    this.current = current;
  }

  /** The row as it stands, in the DTO shape the door speaks. */
  public Object current() {
    return current;
  }
}
