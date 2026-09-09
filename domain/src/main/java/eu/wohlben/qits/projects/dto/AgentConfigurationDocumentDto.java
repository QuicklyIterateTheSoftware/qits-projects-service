package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * The whole resolved agent configuration a container is created with — every surface it may serve,
 * ready to be written to disk and mounted.
 *
 * <p><b>A snapshot, not a subscription.</b> A container keeps what it was born with for its whole
 * life; an edit applies to the next container. That is the deliberate trade: the launch path stays a
 * pure local render with no runtime dependency on this service, which is worth more than immediacy
 * because a container's life is already scoped to a piece of work.
 *
 * <p>{@code version} is this document's shape, not this service's: a daemon reading a document it
 * does not understand must be able to say so at boot rather than mis-render a launch three hours
 * later. It starts at 1 and only moves when the shape changes incompatibly.
 *
 * @param version the document shape's version
 * @param generatedAt when this snapshot was taken, as an ISO-8601 instant — a container's record of
 *     how old the configuration it holds is, which is the only honest way to read a snapshot
 * <p><b>It is the only shape that carries a credential.</b> Each surface's attached external MCP
 * servers arrive fully rendered — url and header value — so a container needs no second lookup and
 * never holds a qits-configuration reference it would have to resolve from inside a workspace. So
 * this document must not be logged, echoed into an event, or answered to anyone but a container's
 * provisioner and an operator.
 *
 * <p><b>It reaches a container as an environment variable and lands on disk inside it, which is not
 * what the epic assumed and is the only shape available.</b> The epic settled on a mounted file, on
 * the strength of the credential above and of a boot-time validation naming a key. Neither host that
 * creates a container can produce one: both qits-projects and qits-workspaces reach the docker
 * daemon through qits-containers, whose {@code Spec} carries {@code volumeMounts} and {@code
 * sharedMounts} and <em>no</em> way to materialize a host file — and neither service holds a docker
 * socket or writes to the docker host's filesystem at all. So the document travels in the spec's
 * env, beside a second variable naming the path, and the daemon writes it there at boot before
 * handing that path to the shared library's {@code readFrom}. Everything the epic decided survives
 * intact — the file the library validates at boot, the path passed in by the host, the snapshot
 * taken at creation, and the recreate-only reach — and the one thing that changes is that the bytes
 * ride the env rather than a bind mount. The cost is honest and worth writing down: a value in env
 * is readable in {@code docker inspect}, which a mounted file would not be. This container's env
 * already carries {@code QITS_COMMISSIONED_CLIENT_SECRET} on the same terms.
 *
 * @param surfaces every surface, resolved; a surface with no row carries its shipped default
 */
public record AgentConfigurationDocumentDto(
    int version, String generatedAt, List<AgentDocumentSurfaceDto> surfaces) {

  /**
   * The shape this service writes and the library reads. Bump only on an incompatible change.
   *
   * <p>2 since the external MCP catalog: a surface entry is now {@code {configuration,
   * externalMcpServers}} rather than the configuration record flat, because the rendered servers
   * carry credentials and had nowhere honest to sit inside a record the editor also reads. Nothing
   * consumed version 1 — the library's reader is written against this shape — so the bump is a
   * statement rather than a migration.
   */
  public static final int CURRENT_VERSION = 2;
}
