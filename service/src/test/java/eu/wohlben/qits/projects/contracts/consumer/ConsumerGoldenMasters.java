package eu.wohlben.qits.projects.contracts.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>The providers' recorded answers, as this repository's consumer pacts read them</b> (ticket
 * qits-1149).
 *
 * <p>Each provider publishes its golden masters as a jar that holds {@code golden-masters/index.json}
 * plus one JSON file per (state, operation). This repository pins several of them, so the classpath
 * holds several {@code index.json} files at the same name. This class tells them apart by the
 * index's own {@code provider} field (the application name, such as {@code qits-events}) and reads
 * each recording relative to the index that names it, so two jars never mix.
 *
 * <p>A provider with no jar on the classpath, or a state or operation its index does not record,
 * is an empty answer, never an error: the row that needs it is skipped with the state it needs (see
 * {@link ConsumerRow#pending}).
 */
public final class ConsumerGoldenMasters {

  /** Where every provider's jar puts its tree. */
  public static final String ROOT = "golden-masters/";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final ConsumerGoldenMasters CLASSPATH = new ConsumerGoldenMasters(ROOT);

  private final String root;
  private final Map<String, Optional<Index>> indexes = new ConcurrentHashMap<>();

  /** The golden masters under {@code root} on the test classpath; the machinery test uses its own. */
  public ConsumerGoldenMasters(String root) {
    this.root = root.endsWith("/") ? root : root + "/";
  }

  /** The providers' own published golden masters. */
  public static ConsumerGoldenMasters classpath() {
    return CLASSPATH;
  }

  /** One recorded (state, operation), as an index describes it. */
  public record Recorded(
      String provider,
      String state,
      Map<String, String> params,
      String operationId,
      String method,
      String path,
      Map<String, String> query,
      int status,
      Set<String> ids,
      Set<String> instants,
      URL file) {

    /** The recorded answer, read when a row binds some of it: a status-only row never needs it. */
    public JsonNode body() {
      try (InputStream in = file.openStream()) {
        return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException(
            provider + "'s golden masters name " + file + ", which its jar does not hold", e);
      }
    }
  }

  /** One provider's index and where it was read from. */
  private record Index(URL url, JsonNode tree) {}

  /** The recording for (state, operation) of {@code provider}, when its jar records one. */
  public Optional<Recorded> find(String provider, String state, String operationId) {
    Optional<Index> index = indexes.computeIfAbsent(provider, this::load);
    if (index.isEmpty()) {
      return Optional.empty();
    }
    for (JsonNode stateNode : index.get().tree().path("states")) {
      if (!state.equals(stateNode.path("name").asText())) {
        continue;
      }
      for (JsonNode op : stateNode.path("operations")) {
        if (operationId.equals(op.path("operationId").asText())) {
          return Optional.of(recorded(provider, index.get(), stateNode, op));
        }
      }
    }
    return Optional.empty();
  }

  private Recorded recorded(String provider, Index index, JsonNode stateNode, JsonNode op) {
    Map<String, String> params = new LinkedHashMap<>();
    stateNode.path("params").fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
    Map<String, String> query = new LinkedHashMap<>();
    op.path("query").fields().forEachRemaining(e -> query.put(e.getKey(), e.getValue().asText()));
    JsonNode frozen = op.path("frozen");
    URL file;
    try {
      file = new URL(index.url(), op.path("file").asText());
    } catch (java.net.MalformedURLException e) {
      throw new IllegalStateException(provider + "'s index names an unusable file", e);
    }
    return new Recorded(
        provider,
        stateNode.path("name").asText(),
        Map.copyOf(params),
        op.path("operationId").asText(),
        op.path("method").asText(),
        op.path("path").asText(),
        query,
        op.path("status").asInt(),
        strings(frozen.path("ids")),
        strings(frozen.path("instants")),
        file);
  }

  private Optional<Index> load(String provider) {
    ClassLoader loader = ConsumerGoldenMasters.class.getClassLoader();
    try {
      Enumeration<URL> found = loader.getResources(root + "index.json");
      while (found.hasMoreElements()) {
        URL url = found.nextElement();
        JsonNode tree;
        try (InputStream in = url.openStream()) {
          tree = MAPPER.readTree(in);
        }
        if (!provider.equals(tree.path("provider").asText())) {
          continue;
        }
        if (tree.path("formatVersion").asInt() != 1) {
          throw new IllegalStateException(
              url + " is formatVersion " + tree.path("formatVersion") + "; this reader reads 1");
        }
        return Optional.of(new Index(url, tree));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Optional.empty();
  }

  private static Set<String> strings(JsonNode array) {
    Set<String> out = new LinkedHashSet<>();
    array.forEach(e -> out.add(e.asText()));
    return Set.copyOf(out);
  }
}
