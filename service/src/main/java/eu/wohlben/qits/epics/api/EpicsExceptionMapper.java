package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.error.EpicsException;
import eu.wohlben.qits.epics.error.StaleWriteException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Maps epics' framework-free {@link EpicsException}s (carrying a status code) to HTTP responses —
 * the sibling of {@code DomainExceptionMapper}/{@code ArtifactsExceptionMapper}, kept here in
 * {@code service} because the epics module carries no JAX-RS (same stance as {@code domain}).
 *
 * <p>One subtype is mapped with a body rather than a message alone: a {@link StaleWriteException}
 * answers 409 carrying {@code current}, the row as it stands, so a caller whose write was refused
 * can see what it would have overwritten instead of losing what it composed.
 */
@Provider
public class EpicsExceptionMapper implements ExceptionMapper<EpicsException> {

  @Override
  public Response toResponse(EpicsException exception) {
    int status = exception.statusCode();
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      message = Response.Status.fromStatusCode(status).getReasonPhrase();
    }
    Object body =
        exception instanceof StaleWriteException stale && stale.current() != null
            ? Map.of("message", message, "current", stale.current())
            : Map.of("message", message);
    return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
  }
}
