package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.control.ArchetypeRegistryDocument;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * <b>The archetype registry, served: {@code GET /projects/api/entities/archetypes}.</b>
 *
 * <p>What a target archetype requires, permits and declares as status words — the whole of {@code
 * Archetypes}, as one JSON document, so a client can gather the gate's demands as <em>form
 * fields</em> before it submits a transition rather than as violations after a button press. That is
 * {@code Archetypes.validate} returning every violation at once, moved one step earlier: told after
 * the press, a caller fixes one problem per round trip; told before it, there is no round trip.
 *
 * <p><b>It is served rather than duplicated, and that is the decision this route is.</b> The
 * alternative is a second copy of the registry in the client's own language, which is a copy that
 * drifts the day a property is added — and drifts silently, because nothing compiles the two
 * against each other. {@code Archetypes} is already a first-class registry with one declaration
 * site; this makes that declaration reachable. Everything in the answer is derived at read time from
 * {@link ArchetypeRegistryDocument#describe()}, so a new archetype or a new property changes the
 * document with no edit to any file on this path.
 *
 * <h2>Why a class of its own, and not a method on {@code EntityTransitionController}</h2>
 *
 * <p>That class is {@code @RolesAllowed("qits:admin")} at class level and its javadoc argues the
 * reason: a transition is a write, an agent keeps every read and gains no write, and there is
 * nothing to bind a re-shaping of a project's whole plan to. A read route there would need a
 * method-level override, and a method-level {@code @RolesAllowed} <em>replaces</em> the class list
 * rather than adding to it — so the class would end up saying two things at once and its argument
 * would be harder to read than the exception to it. A separate resource with its own class-level
 * list says the honest thing: this whole class is a read.
 *
 * <p>A second root resource at {@code /entities} is the shape the segment already has — {@code
 * MigrationVerificationController} is the other one — and for the same reason: JAX-RS gives a class
 * one {@code @Path}, and these are separate subjects with separate doors. {@code /entities} is the
 * merged model's segment, argued once in {@code EntityTransitionController}: the unified entity is
 * the noun, and a question about all four archetypes filed under {@code /epics} or {@code /tickets}
 * would be filed under one of them. It sits under {@code /projects} like every machine surface here,
 * so {@code quarkus.quinoa.ignored-path-prefixes} needs no line.
 *
 * <p><b>{@code qits:admin} and {@code qits:agent}</b>, the standing rule for every read on this
 * surface. An agent assembling a transition is exactly the caller this document is for — it has no
 * screen to be told about a missing impetus on — and the answer contains no row, no project and no
 * identity: it is the same four declarations for every caller, which is the same public constants
 * the deployed binary already carries.
 */
@Path("/entities")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:agent"})
public class EntityArchetypesController {

  /**
   * The registry as it stands.
   *
   * <p><b>One object and not an {@code entries} envelope</b>, unlike every listing on this surface:
   * a listing answers rows that happen to be there and this answers the model itself, which is one
   * thing with named parts. The three members are the vocabulary, the properties a caller may not
   * state, and the four declarations — see {@link ArchetypeRegistryDocument} for what each is and
   * for the two orderings, one of which (the status words') deliberately means nothing.
   */
  @GET
  @Path("/archetypes")
  public ArchetypeRegistryDocument archetypes() {
    return ArchetypeRegistryDocument.describe();
  }
}
