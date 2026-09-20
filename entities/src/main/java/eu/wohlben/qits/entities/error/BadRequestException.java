package eu.wohlben.qits.entities.error;

/** Entities error mapped to HTTP 400 by the web layer. */
public class BadRequestException extends EntitiesException {

  public BadRequestException(String message) {
    super(400, message);
  }
}
