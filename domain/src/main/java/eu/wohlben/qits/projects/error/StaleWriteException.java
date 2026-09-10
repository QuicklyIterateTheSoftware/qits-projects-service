package eu.wohlben.qits.projects.error;

/**
 * A write carrying a version that is no longer the current one — refused with 409, and <b>carrying
 * the row as it stands now</b>.
 *
 * <p>The payload is the point. On a surface where nobody accepts a write, in-place writing is the
 * only writing there is, and a person editing in the SPA while an agent writes from a prompt is the
 * ordinary case rather than an edge one. A bare 409 leaves the caller with the text it typed and no
 * idea what it would have overwritten; this one hands back the current document so the SPA can show
 * both sides and an agent can re-read, merge and retry.
 *
 * <p>Never a silent merge. The two writers are a person and a model, and guessing which sentence
 * wins is exactly the judgement neither this class nor the database is entitled to make.
 */
public class StaleWriteException extends DomainException {

  private final transient Object current;

  public StaleWriteException(String message, Object current) {
    super(409, message);
    this.current = current;
  }

  /** The row as it stands, in whatever DTO shape the door speaks. */
  public Object current() {
    return current;
  }
}
