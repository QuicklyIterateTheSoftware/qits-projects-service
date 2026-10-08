package eu.wohlben.qits.projects.idphost;

/**
 * A credential or token could not be commissioned at qits-idp (it was {@code
 * agenthost/AgentCredentialException} until the direct agent path was retired, qits-767).
 *
 * <p><b>A plain {@link RuntimeException} and deliberately not a {@code
 * eu.wohlben.qits.projects.error.DomainException}</b>: it is not an answer about the request — the
 * idp could not be reached, or refused this service's own credential — so a caller reports it the
 * way it reports any other failure to bring something up, not as a status.
 *
 * <p>{@link #retryable} is what a patient loop reads: an answer about the moment (nothing
 * answering, 401, 403, a 5xx) is asked again inside the window; an answer about the request is one
 * attempt, because no window fixes it.
 */
public class IdpCommissionException extends RuntimeException {

  private final boolean retryable;

  public IdpCommissionException(String message, boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  public IdpCommissionException(String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.retryable = retryable;
  }

  /** Whether another attempt inside the patience window could answer differently. */
  public boolean retryable() {
    return retryable;
  }
}
