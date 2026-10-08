package eu.wohlben.qits.projects.deskhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The direct agent path stays retired (qits-767): no production class asks qits-containers about the
 * {@code project-agent} workload except the legacy one-shot that clears it, the retired classes do
 * not come back, and the service boots without {@code agent_credential}.
 */
@QuarkusTest
class DirectAgentPathRetiredTest {

  /** The production sources, read as text: a grep is the honest test for "nobody names it". */
  private static final Path MAIN = Path.of("src/main/java");

  @Inject
  @DataSource("projects")
  AgroalDataSource projects;

  @Test
  void onlyTheLegacyOneShotNamesTheProjectAgentWorkloadBesideTheContainersClient()
      throws IOException {
    List<String> offenders;
    try (Stream<Path> files = Files.walk(MAIN)) {
      offenders =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(DirectAgentPathRetiredTest::callsContainersForProjectAgents)
              .map(p -> MAIN.relativize(p).toString())
              .toList();
    }
    assertEquals(
        List.of("eu/wohlben/qits/projects/deskhost/LegacyAgentPlaces.java"),
        offenders,
        "a class other than the legacy one-shot reaches ContainersClient for project-agent");
  }

  private static boolean callsContainersForProjectAgents(Path file) {
    try {
      String text = Files.readString(file);
      return text.contains("ContainersClient") && text.contains("\"project-agent\"");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void theRetiredClassesAreGone() {
    for (String name :
        List.of(
            "eu.wohlben.qits.projects.agenthost.AgentContainers",
            "eu.wohlben.qits.projects.agenthost.ContainerRuntime",
            "eu.wohlben.qits.projects.agenthost.AgentContainerFactory",
            "eu.wohlben.qits.projects.agenthost.AgentIdleSweep",
            "eu.wohlben.qits.projects.agenthost.AgentStaleImageSweep",
            "eu.wohlben.qits.projects.agenthost.AgentCommissions",
            "eu.wohlben.qits.projects.agenthost.AgentCredentials",
            "eu.wohlben.qits.projects.agenthost.AgentCredentialReconcile",
            "eu.wohlben.qits.projects.agenthost.AgentCredentialException",
            "eu.wohlben.qits.projects.agenthost.AgentContainerState",
            "eu.wohlben.qits.projects.containershost.ContainersAgentRuntime",
            "eu.wohlben.qits.projects.idphost.IdpAgentCredentials",
            "eu.wohlben.qits.projects.entity.AgentCredential",
            "eu.wohlben.qits.projects.persistence.AgentCredentialRepository")) {
      assertThrows(ClassNotFoundException.class, () -> Class.forName(name), name);
    }
  }

  @Test
  void theServiceBootsWithNoAgentCredentialTable() throws Exception {
    try (Connection connection = projects.getConnection();
        Statement sql = connection.createStatement();
        ResultSet rows =
            sql.executeQuery(
                "select count(*) from information_schema.tables where table_name ="
                    + " 'agent_credential'")) {
      assertTrue(rows.next());
      assertEquals(0, rows.getInt(1));
    }
  }
}
