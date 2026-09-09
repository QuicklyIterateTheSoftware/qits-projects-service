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
 * @param surfaces every surface, resolved; a surface with no row carries its shipped default
 */
public record AgentConfigurationDocumentDto(
    int version, String generatedAt, List<AgentSurfaceConfigurationDto> surfaces) {

  /** The shape this service writes and the library reads. Bump only on an incompatible change. */
  public static final int CURRENT_VERSION = 1;
}
