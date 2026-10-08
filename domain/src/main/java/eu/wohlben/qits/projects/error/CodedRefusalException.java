package eu.wohlben.qits.projects.error;

/**
 * A refusal that names itself (qits-767): {@code code} is a stable word a client branches on — e.g.
 * {@code RUNNER_OWNS_DESKS}, {@code RUNNER_PLANE_UNCONFIGURED} — answered as {@code error} beside
 * the message by {@code ProjectsExceptionMapper}. qits-workspaces-service's coded {@code
 * DomainException}, as a subtype here so no existing constructor's meaning moves.
 */
public class CodedRefusalException extends DomainException {

  private final String code;

  public CodedRefusalException(int statusCode, String code, String message) {
    super(statusCode, message);
    this.code = code;
  }

  /** The refusal's own name. */
  public String code() {
    return code;
  }
}
