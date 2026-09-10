package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.StaleWriteException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Maps the projects domain's framework-free {@link DomainException}s (each carrying a status code)
 * to HTTP responses.
 *
 * <p>It lives here, in {@code service}, for the same reason the sibling {@code EpicsExceptionMapper}
 * does: the {@code domain} module carries no JAX-RS, which is what lets it stay a plain library jar.
 *
 * <p>Not inherited from anywhere. The monorepo's {@code eu.wohlben.qits.api.DomainExceptionMapper}
 * is monolith-only (migration-plan.md §3.9), so without this class every {@code BadRequestException}
 * this context throws would surface as a 500 where the suite — and the frontend — expect a 400.
 *
 * <p>One subtype is mapped with a body rather than a message alone: a {@link StaleWriteException}
 * answers 409 carrying {@code current}, the row as it stands, so the caller can see what its write
 * would have overwritten instead of losing the text it typed.
 *
 * <p>Scoped to <em>this</em> context's exception type. An application that also runs the monorepo's
 * {@code eu.wohlben.qits.domain.error.DomainException}, or qits-workspaces' or epics' equivalents,
 * keeps its own mapper for each; they coexist because they map unrelated types.
 */
@Provider
public class ProjectsExceptionMapper implements ExceptionMapper<DomainException> {

  @Override
  public Response toResponse(DomainException exception) {
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
