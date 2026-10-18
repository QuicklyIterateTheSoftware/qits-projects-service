package eu.wohlben.qits.projects.contracts.consumer;

import eu.wohlben.qits.eventstream.contracts.EventstreamPact;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.bus.CampaignCriteriaListener;
import eu.wohlben.qits.projects.bus.DeploymentActiveListener;
import eu.wohlben.qits.projects.bus.ReleasePipelineRunListener;
import eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener;
import eu.wohlben.qits.projects.bus.ScmBackupTriggerListener;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>What qits-eventstream asks qits-events on this service's behalf</b> (qits-1163): the outbox's
 * publish and each durable listener's catch-up reads, pacted by this service against the library
 * version it pins, and committed as {@code
 * pacts/qits-projects-service_qits-eventstream-javalib_qits-events-service.json}. Replaces the
 * hand-written {@code EventsContract} rows. The guard fails naming any durable or raw listener, or
 * {@code QitsEventBus} user, in the service and domain modules that this class leaves out.
 */
class EventstreamPactTest {

  static final EventstreamPact PACT =
      EventstreamPact.forConsumer("qits-projects-service")
          .listeners(
              new BuildStatusListener(),
              new CampaignCriteriaListener(),
              new DeploymentActiveListener(),
              new ReleasePipelineRunListener(),
              new ReleaseRequestHeadListener(),
              new ScmBackupTriggerListener())
          .publishes(true)
          .guard(Path.of("target/classes"), Path.of("../domain/target/classes"));

  @TestFactory
  Stream<DynamicTest> rows() {
    return PACT.tests();
  }

  @Test
  void pactFile() {
    PACT.compareOrWritePactFile();
  }
}
