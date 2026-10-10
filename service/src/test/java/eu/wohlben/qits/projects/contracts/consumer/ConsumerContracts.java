package eu.wohlben.qits.projects.contracts.consumer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Every REST call qits-projects makes to another qits service, as consumer pact rows</b> (ticket
 * qits-1149), grouped by provider repository. The inventory behind it — every call, the non-REST
 * traffic, and the provider states still needed — is the ticket's; this table is its executable
 * half.
 */
final class ConsumerContracts {

  private ConsumerContracts() {}

  /** Every row, by provider repository, in a fixed order. */
  static Map<String, List<ConsumerRow>> byProvider() {
    Map<String, List<ConsumerRow>> all = new LinkedHashMap<>();
    for (List<ConsumerRow> rows :
        List.of(
            GitHostContract.ROWS,
            CiContract.ROWS,
            DeploymentsContract.ROWS,
            MaintenanceContract.ROWS,
            WorkspacesContract.ROWS,
            IdpContract.ROWS,
            ConfigurationContract.ROWS,
            ContainersContract.ROWS,
            EventsContract.ROWS,
            ProjectsDaemonContract.ROWS)) {
      for (ConsumerRow row : rows) {
        all.computeIfAbsent(row.provider(), k -> new ArrayList<>()).add(row);
      }
    }
    return all;
  }

  /** Every row. */
  static List<ConsumerRow> all() {
    return byProvider().values().stream().flatMap(List::stream).toList();
  }

  /** The committed pact file for {@code provider}: both repository names. */
  static String fileName(String provider) {
    return ConsumerRow.Trigger.CONSUMER + "_" + provider + ".json";
  }
}
