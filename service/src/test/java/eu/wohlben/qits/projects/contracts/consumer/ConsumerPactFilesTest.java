package eu.wohlben.qits.projects.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.PactSpecVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.projects.contracts.GoldenFiles;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>The committed consumer pacts, {@code pacts/qits-projects-service_<provider>.json}</b> (ticket
 * qits-1149), one per provider with at least one row whose state is recorded.
 *
 * <p><b>Compare by default.</b> The test writes each pact with pact-jvm's own writer, normalises it
 * (interactions sorted by description then state, pact-jvm's version stripped, 2-space indentation,
 * a trailing newline) and compares it byte for byte with the committed file. {@code
 * -Dgolden.update=true} (or {@code QITS_GOLDEN_UPDATE=true}) rewrites the files instead, and removes
 * the file of a provider that has no recorded row any more.
 *
 * <p><b>Published as the platform publishes every pact</b>: {@code .config/qits/release.yml}'s
 * {@code contracts.pacts} names each provider with a committed file, and names no other. A file
 * nobody publishes is a contract no provider can verify; a declaration with no file publishes
 * nothing.
 */
class ConsumerPactFilesTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void theCommittedPactsAreWhatTheRecordedRowsWrite() throws IOException {
    ConsumerGoldenMasters masters = ConsumerGoldenMasters.classpath();
    Path dir = GoldenFiles.repositoryRoot().resolve("pacts");
    for (Map.Entry<String, List<ConsumerRow>> provider : ConsumerContracts.byProvider().entrySet()) {
      Path file = dir.resolve(ConsumerContracts.fileName(provider.getKey()));
      List<ConsumerRow> live =
          provider.getValue().stream().filter(row -> row.pending(masters).isEmpty()).toList();
      if (live.isEmpty()) {
        if (GoldenFiles.updating()) {
          Files.deleteIfExists(file);
        }
        assertFalse(
            Files.exists(file),
            file + " is committed, but no row against " + provider.getKey() + " is recorded yet");
        continue;
      }
      String raw = written(masters, live);
      Path scratch = Path.of("target", "pacts", file.getFileName().toString());
      Files.createDirectories(scratch.getParent());
      Files.writeString(scratch, raw);
      GoldenFiles.compareOrWrite(file, normalise(raw));
      references(provider.getKey(), normalise(raw), live.size());
    }
  }

  @Test
  void releaseYmlPublishesExactlyTheCommittedPacts() throws IOException {
    Path root = GoldenFiles.repositoryRoot();
    Set<String> committed = new TreeSet<>();
    Path dir = root.resolve("pacts");
    if (Files.isDirectory(dir)) {
      try (Stream<Path> files = Files.list(dir)) {
        String prefix = ConsumerRow.Trigger.CONSUMER + "_";
        files
            .map(path -> path.getFileName().toString())
            .filter(name -> name.startsWith(prefix) && name.endsWith(".json"))
            .forEach(name -> committed.add(name.substring(prefix.length(), name.length() - 5)));
      }
    }
    assertEquals(
        committed,
        declaredPacts(Files.readString(root.resolve(".config/qits/release.yml"))),
        "contracts.pacts in .config/qits/release.yml must name each provider with a committed"
            + " pacts/qits-projects-service_<provider>.json, and no other");
  }

  /** The provider keys under {@code contracts:} → {@code pacts:}, read off the YAML's indentation. */
  static Set<String> declaredPacts(String yaml) {
    Set<String> declared = new TreeSet<>();
    boolean inContracts = false;
    boolean inPacts = false;
    Pattern entry = Pattern.compile("^    ([A-Za-z0-9-]+):");
    for (String line : yaml.split("\n")) {
      if (line.isBlank() || line.stripLeading().startsWith("#")) {
        continue;
      }
      if (!line.startsWith(" ")) {
        inContracts = line.startsWith("contracts:");
        inPacts = false;
        continue;
      }
      if (inContracts && line.startsWith("  ") && !line.startsWith("   ")) {
        inPacts = line.startsWith("  pacts:");
        continue;
      }
      Matcher m = entry.matcher(line);
      if (inPacts && m.find()) {
        declared.add(m.group(1));
      }
    }
    return declared;
  }

  @Test
  void declaredPactsAreReadOffTheContractsSection() {
    String yaml =
        "archetype: java-service\n"
            + "contracts:\n"
            + "  application: qits-projects\n"
            + "  # a comment\n"
            + "  pacts:\n"
            + "    qits-events-service: { packages: [maven] }\n"
            + "    qits-ci-service: { packages: [maven] }\n"
            + "userflows: qits-projects\n";
    assertEquals(Set.of("qits-ci-service", "qits-events-service"), declaredPacts(yaml));
    assertTrue(declaredPacts("contracts:\n  application: x\n").isEmpty());
  }

  /** Both references on every interaction, as the platform reads them. */
  private static void references(String provider, String pactJson, int rows) throws IOException {
    JsonNode pact = MAPPER.readTree(pactJson);
    assertEquals(ConsumerRow.Trigger.CONSUMER, pact.path("consumer").path("name").asText());
    assertEquals(provider, pact.path("provider").path("name").asText());
    assertEquals("4.0", pact.path("metadata").path("pactSpecification").path("version").asText());
    JsonNode interactions = pact.path("interactions");
    assertEquals(rows, interactions.size(), provider + ": one interaction per recorded row");
    Map<String, String> keyOfKind =
        Map.of("operation", "operationId", "event", "event", "schedule", "schedule");
    for (JsonNode interaction : interactions) {
      String description = interaction.path("description").asText();
      JsonNode refs = interaction.path("comments").path("references");
      JsonNode call = refs.path("qits-call");
      assertEquals(provider, call.path("app").asText(), description);
      assertFalse(call.path("operationId").asText().isBlank(), description);
      JsonNode trigger = refs.path("qits-trigger");
      String kind = trigger.path("kind").asText();
      assertTrue(keyOfKind.containsKey(kind), description + ": unknown trigger kind " + kind);
      assertEquals(ConsumerRow.Trigger.CONSUMER, trigger.path("app").asText(), description);
      assertTrue(
          description.startsWith(trigger.path(keyOfKind.get(kind)).asText() + ": "),
          description + ": the description leads with the trigger");
    }
  }

  static String written(ConsumerGoldenMasters masters, List<ConsumerRow> rows) {
    StringWriter out = new StringWriter();
    try (PrintWriter writer = new PrintWriter(out)) {
      DefaultPactWriter.INSTANCE.writePact(
          ConsumerInteractions.pact(masters, rows), writer, PactSpecVersion.V4);
    }
    return out.toString();
  }

  /** Sorted, version-stripped, 2-space indented, one trailing newline. */
  static String normalise(String raw) throws IOException {
    ObjectNode pact = (ObjectNode) MAPPER.readTree(raw);
    if (pact.path("metadata") instanceof ObjectNode meta) {
      meta.remove("pact-jvm");
    }
    if (pact.path("interactions") instanceof ArrayNode interactions) {
      List<JsonNode> sorted = new ArrayList<>();
      interactions.forEach(sorted::add);
      sorted.sort(
          Comparator.comparing((JsonNode i) -> i.path("description").asText())
              .thenComparing(i -> i.path("providerStates").path(0).path("name").asText()));
      interactions.removeAll();
      sorted.forEach(interactions::add);
    }
    StringBuilder out = new StringBuilder();
    print(pact, "", out);
    return out.append('\n').toString();
  }

  /** {@code JSON.stringify(value, null, 2)}: no space before a colon, empty containers as {} / []. */
  private static void print(JsonNode node, String indent, StringBuilder out) throws IOException {
    String inner = indent + "  ";
    if (node.isObject()) {
      if (node.isEmpty()) {
        out.append("{}");
        return;
      }
      out.append("{\n");
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        out.append(inner).append(MAPPER.writeValueAsString(field.getKey())).append(": ");
        print(field.getValue(), inner, out);
        out.append(fields.hasNext() ? ",\n" : "\n");
      }
      out.append(indent).append('}');
    } else if (node.isArray()) {
      if (node.isEmpty()) {
        out.append("[]");
        return;
      }
      out.append("[\n");
      for (int i = 0; i < node.size(); i++) {
        out.append(inner);
        print(node.get(i), inner, out);
        out.append(i < node.size() - 1 ? ",\n" : "\n");
      }
      out.append(indent).append(']');
    } else {
      out.append(MAPPER.writeValueAsString(node));
    }
  }
}
