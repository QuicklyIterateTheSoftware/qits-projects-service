package eu.wohlben.qits.projects.contracts.consumer;

import eu.wohlben.qits.containers.client.contracts.ContainersClientPact;
import eu.wohlben.qits.containers.client.contracts.Feature;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * <b>What qits-containers-client asks qits-containers on this service's behalf</b> (qits-1163): the
 * rows of {@code eu.wohlben.qits:qits-containers-client-contracts}, selected by the kit's scan of
 * this module's compiled classes and driven through the real {@code ContainersClient}, committed as
 * {@code pacts/qits-projects-service_qits-containers-service.json} with {@code qits-via} naming the
 * client and its version. Replaces the hand-written {@code ContainersContract} rows.
 *
 * <p>Only {@code service/} calls the client ({@code ContainersRefinementRuntime}, {@code
 * LegacyAgentPlaces}); {@code domain}, {@code entities} and {@code gitmirror} do not. What a class
 * file cannot show is named by hand: {@code LegacyAgentPlaces} deletes the legacy agent place with
 * {@code volumes=true}, which is {@link Feature#DELETE_WITH_VOLUMES}.
 */
class ContainersClientPactTest {

  static final ContainersClientPact.Builder CONTAINERS =
      ContainersClientPact.forConsumer("qits-projects-service")
          .scanning(Path.of("target/classes"))
          .uses(Feature.DELETE_WITH_VOLUMES);

  @Test
  void rows() {
    CONTAINERS.runRecorded();
  }

  @Test
  void pactFile() {
    CONTAINERS.pact().compareOrWritePactFile();
  }
}
