package eu.wohlben.qits.entities.error;

/**
 * Entities error mapped to HTTP 403 by the web layer: the caller is authenticated and the request is
 * well formed, but the row it names is outside what this caller may write — an agent reaching for
 * another project's entities. Distinct from a 404 on purpose: the id resolved, and saying so is the
 * whole information a refusal carries.
 */
public class ForbiddenException extends EntitiesException {

  public ForbiddenException(String message) {
    super(403, message);
  }
}
