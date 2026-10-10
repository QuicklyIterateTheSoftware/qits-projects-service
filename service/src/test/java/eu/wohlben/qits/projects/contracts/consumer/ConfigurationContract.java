package eu.wohlben.qits.projects.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.projects.confighost.ConfigurationBearer;
import eu.wohlben.qits.projects.confighost.HttpMcpCredentials;
import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>What qits-projects asks qits-configuration</b> (ticket qits-1149): the entries of the {@code
 * qits-agent-mcp} application in one environment, read for an external MCP server's credential.
 * qits-configuration publishes no golden masters yet, so the row waits on its state.
 */
final class ConfigurationContract {

  static final String PROVIDER = "qits-configuration-service";
  static final String APP = "qits-configuration";

  private static final ConfigurationBearer BEARER = () -> Optional.of("Bearer machine-token");

  private ConfigurationContract() {}

  static final List<ConsumerRow> ROWS =
      List.of(
          new ConsumerRow(
              PROVIDER,
              APP,
              "listEntries",
              "an application with an entry in an environment",
              "GET",
              "/configuration/api/applications/{application}/envs/{env}/entries",
              Map.of(),
              null,
              List.of("$.entries[*].key", "$.entries[*].value"),
              200,
              Trigger.operation("AgentMcpCatalogController.save"),
              (base, p) ->
                  assertEquals(
                      Optional.of(true),
                      Fields.with(
                              new HttpMcpCredentials(),
                              "configurationUrl", Optional.of(base),
                              "configurationEnv", Optional.of(p.get("env")),
                              "bearer", BEARER)
                          .exists(p.get("key"))),
              "GET .../applications/qits-agent-mcp/envs/{env}/entries answering 200 with entries[]"
                  + " of key and value, one of them {key} with a value"));
}
