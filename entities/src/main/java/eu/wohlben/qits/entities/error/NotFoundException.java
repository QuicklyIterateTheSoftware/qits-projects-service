package eu.wohlben.qits.entities.error;

/** Entities error mapped to HTTP 404 by the web layer. */
public class NotFoundException extends EntitiesException {

  public NotFoundException(String message) {
    super(404, message);
  }
}
