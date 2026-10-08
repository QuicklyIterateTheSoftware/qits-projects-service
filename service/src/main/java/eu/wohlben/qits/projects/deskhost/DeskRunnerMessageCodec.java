package eu.wohlben.qits.projects.deskhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerCodec;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerMessage;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerProtocol;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

/**
 * The bridge between a front-desk runner frame and its JSON text (qits-767) —
 * qits-workspaces-service's {@code WorkspaceRunnerMessageCodec} for this socket. The framework-free
 * codecs do the field mapping: qits-runner-protocol's {@link RunnerCodec} for the lifecycle frames,
 * handing every other {@code type} to {@link DeskRunnerCodec}. This class only bolts on Jackson, so
 * the wire is spelled in the protocol jars and never here.
 *
 * <p>{@link #decode} lets the codecs' strictness through as an exception and {@link
 * DeskRunnerSocket} catches it: a frame this host cannot read costs that frame, never the socket.
 */
@ApplicationScoped
public class DeskRunnerMessageCodec {

  /** Stateless and immutable, so one serves every connection. */
  static final RunnerCodec<DeskRunnerMessage> CODEC =
      new RunnerCodec<>(DeskRunnerProtocol.VOCABULARY, new DeskRunnerCodec());

  @Inject ObjectMapper objectMapper;

  /** Serialize a frame to the JSON text sent over the socket. */
  public String encode(RunnerMessage message) {
    try {
      return objectMapper.writeValueAsString(CODEC.encode(message));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode a desk-runner frame", e);
    }
  }

  /** Parse a received JSON text frame. */
  @SuppressWarnings("unchecked")
  public RunnerMessage decode(String json) {
    try {
      return CODEC.decode(objectMapper.readValue(json, Map.class));
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to decode a desk-runner frame", e);
    }
  }
}
