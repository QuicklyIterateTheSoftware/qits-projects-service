package eu.wohlben.qits.projects.contracts.consumer;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.matchingrules.NullMatcher;
import au.com.dius.pact.core.model.matchingrules.RegexMatcher;
import au.com.dius.pact.core.model.matchingrules.TypeMatcher;
import au.com.dius.pact.core.support.Json;
import au.com.dius.pact.core.support.json.JsonParser;
import au.com.dius.pact.core.support.json.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Turns {@link ConsumerRow}s into pact-jvm V4 interactions</b> (ticket qits-1149).
 *
 * <p><b>The answer binds only what this service reads.</b> The recorded body is cut down to the
 * row's {@link ConsumerRow#consumes} paths before any matcher is made, so a field the provider adds,
 * renames or drops that this service never looks at cannot break the pact. Paths are written
 * {@code $.a.b}, {@code $.list[*].field} and {@code $[*].field} for a root array; a path that ends
 * on an object or array binds all of it. A lone {@code $} binds "a JSON object" and nothing in it,
 * for a client that decodes the answer into a type but reads no field of it. A row that consumes nothing binds the status only, with no
 * body and no content type.
 *
 * <p><b>The matchers</b>, on what is left:
 *
 * <ul>
 *   <li>a path the provider listed under {@code frozen.ids}: a UUID regex;
 *   <li>under {@code frozen.instants}: an ISO-8601 regex ({@link #ISO_INSTANT});
 *   <li>any other leaf: a type match; {@code null} exactly where the recording holds only null;
 *   <li>a non-empty array: "at least one element like this", the template merged from every
 *       recorded element (a leaf null or absent in some element matches type-or-null, at any
 *       depth; an array empty in some element matches "zero or more like this"). This
 *       service reads each element in a loop, so neither the count nor the order is its business;
 *   <li>except that an object or array null or absent in some element makes the array's example
 *       its recorded elements, in order, under the same {@code [*]} rules: pact-jvm refuses a
 *       null against an object example before any matcher is consulted, so "this object or null"
 *       has no rule that verifies; each answered element is held against the recorded one at its
 *       index instead (qits-maintenance's automations {@code failure}, qits-1149);
 *   <li>an empty array: empty, exactly, because there is nothing to build a template from.
 * </ul>
 *
 * <p>Every interaction carries {@code comments.references}: {@code qits-call} (the provider and its
 * operation) and {@code qits-trigger} (the entry point here that makes the call). A provider this
 * service reaches through a published client library adds {@code qits-via}, the library and its
 * version ({@link ConsumerContracts#via}).
 */
public final class ConsumerInteractions {

  /** An ISO-8601 timestamp, any fraction length, Z or a numeric offset. */
  public static final String ISO_INSTANT =
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})$";

  private static final String UUID_REGEX =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z0-9_]+)}");

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private ConsumerInteractions() {}

  /** One pact for {@code rows}, all against the same provider, each bound to its recording. */
  public static V4Pact pact(ConsumerGoldenMasters masters, List<ConsumerRow> rows) {
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("a pact needs at least one row");
    }
    String provider = rows.get(0).provider();
    PactBuilder builder = new PactBuilder(ConsumerRow.Trigger.CONSUMER, provider, PactSpecVersion.V4);
    for (ConsumerRow row : rows) {
      if (!provider.equals(row.provider())) {
        throw new IllegalArgumentException(
            row.description() + " is against " + row.provider() + ", not " + provider);
      }
      ConsumerGoldenMasters.Recorded recorded =
          row.recorded(masters)
              .orElseThrow(() -> new IllegalStateException(row.pending(masters).orElseThrow()));
      interaction(builder, row, recorded);
    }
    return builder.toPact();
  }

  /** Add one row's interaction, after checking the row asks what the recording answered. */
  static void interaction(PactBuilder builder, ConsumerRow row, ConsumerGoldenMasters.Recorded op) {
    String where = row.provider() + " " + op.state() + "/" + op.operationId();
    if (!row.method().equals(op.method()) || !row.path().equals(op.path())) {
      throw new IllegalStateException(
          where + " records " + op.method() + " " + op.path() + ", but the row asks "
              + row.method() + " " + row.path());
    }
    if (row.status() != op.status()) {
      throw new IllegalStateException(
          where + " records status " + op.status() + ", but the row expects " + row.status());
    }
    Map<String, String> params = op.params();
    DslPart answer = row.consumes().isEmpty() ? null : responseBody(op, row.consumes());
    DslPart request = row.requestBody() == null ? null : requestBody(row.requestBody(), params, where);
    Map<String, Object> references = new LinkedHashMap<>();
    Map<String, String> call = new LinkedHashMap<>();
    call.put("app", row.provider());
    call.put("operationId", row.operationId());
    references.put("qits-call", call);
    references.put("qits-trigger", row.trigger().reference());
    ConsumerContracts.via(row.provider()).ifPresent(via -> references.put("qits-via", via));
    builder.expectsToReceiveHttpInteraction(
        row.description(),
        http -> {
          http.state(op.state(), new LinkedHashMap<String, Object>(params));
          http.withRequest(
              r -> {
                r.method(row.method());
                if (hasParam(row.path())) {
                  r.path(Matchers.fromProviderState(expression(row.path()), example(row.path(), params, where)));
                } else {
                  r.path(row.path());
                }
                for (Map.Entry<String, String> q : row.query().entrySet()) {
                  String value = q.getValue();
                  r.queryParameter(
                      q.getKey(),
                      hasParam(value)
                          ? Matchers.fromProviderState(expression(value), example(value, params, where))
                          : value);
                }
                for (Map.Entry<String, String> h : row.headers().entrySet()) {
                  String value = h.getValue();
                  r.header(
                      h.getKey(),
                      hasParam(value)
                          ? Matchers.fromProviderState(expression(value), example(value, params, where))
                          : value);
                }
                if (request != null) {
                  r.header("Content-Type", "application/json");
                  r.body(request);
                }
                if (row.formBody() != null) {
                  r.body(row.formBody(), "application/x-www-form-urlencoded");
                }
                return r;
              });
          http.willRespondWith(
              response -> {
                response.status(op.status());
                if (answer != null) {
                  response
                      .header("Content-Type", Matchers.regexp("application/json.*", "application/json"))
                      .body(answer);
                }
                return response;
              });
          // pact-jvm 4.6's DSL has no setter for an arbitrary comment group, but the V4 model's
          // comments map is mutable and written verbatim.
          http.getInteraction().getComments().put("references", Json.toJson(references));
          return http;
        });
  }

  // --- the request ------------------------------------------------------------------------------

  static boolean hasParam(String text) {
    return text != null && PARAM.matcher(text).find();
  }

  /** {@code {name}} becomes {@code ${name}}: a provider-state expression. */
  static String expression(String template) {
    return substitute(template, name -> "${" + name + "}");
  }

  /** {@code {name}} becomes the state's recorded example. */
  static String example(String template, Map<String, String> params, String where) {
    return substitute(
        template,
        name -> {
          String value = params.get(name);
          if (value == null) {
            throw new IllegalStateException(
                where + ": the row names {" + name + "}, which the state's params do not hold");
          }
          return value;
        });
  }

  private static String substitute(String template, Function<String, String> value) {
    Matcher m = PARAM.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(out, Matcher.quoteReplacement(value.apply(m.group(1))));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** The consumer's own request body: values exact, a {@code "{param}"} string a state expression. */
  static DslPart requestBody(JsonNode body, Map<String, String> params, String where) {
    if (!body.isObject()) {
      throw new IllegalStateException(where + ": only an object request body is supported");
    }
    PactDslJsonBody root = new PactDslJsonBody();
    requestObject(root, body, params, where);
    return root;
  }

  private static void requestObject(
      PactDslJsonBody target, JsonNode body, Map<String, String> params, String where) {
    for (Iterator<Map.Entry<String, JsonNode>> it = body.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> field = it.next();
      String name = field.getKey();
      JsonNode value = field.getValue();
      if (value.isObject()) {
        PactDslJsonBody nested = target.object(name);
        requestObject(nested, value, params, where);
        nested.closeObject();
      } else if (value.isArray()) {
        PactDslJsonArray nested = target.array(name);
        for (JsonNode element : value) {
          if (element.isObject()) {
            PactDslJsonBody inner = nested.object();
            requestObject(inner, element, params, where);
            inner.closeObject();
          } else if (element.isTextual() && hasParam(element.asText())) {
            nested.valueFromProviderState(
                expression(element.asText()), example(element.asText(), params, where));
          } else if (element.isTextual()) {
            nested.stringValue(element.asText());
          } else if (element.isNumber()) {
            nested.numberValue(element.numberValue());
          } else if (element.isBoolean()) {
            nested.booleanValue(element.asBoolean());
          } else {
            nested.nullValue();
          }
        }
        nested.closeArray();
      } else if (value.isNull()) {
        target.nullValue(name);
      } else if (value.isTextual() && hasParam(value.asText())) {
        target.valueFromProviderState(
            name, expression(value.asText()), example(value.asText(), params, where));
      } else if (value.isTextual()) {
        target.stringValue(name, value.asText());
      } else if (value.isNumber()) {
        target.numberValue(name, value.numberValue());
      } else if (value.isBoolean()) {
        target.booleanValue(name, value.asBoolean());
      }
    }
  }

  // --- the answer -------------------------------------------------------------------------------

  /** The recorded body, cut down to {@code consumes}, with the matchers the class javadoc lists. */
  static DslPart responseBody(ConsumerGoldenMasters.Recorded op, List<String> consumes) {
    Need need = Need.of(consumes, op);
    JsonNode recorded = op.body();
    if (recorded.isObject()) {
      PactDslJsonBody root = new PactDslJsonBody();
      fillObject(root, recorded, need, "$", op, new Nullable());
      return root;
    }
    if (recorded.isArray()) {
      if (recorded.isEmpty()) {
        throw unsupported(op, "$", "an empty root array, which leaves nothing to bind");
      }
      Nullable nullable = new Nullable();
      JsonNode template = merge(recorded, "$[*]", nullable);
      Need element = need.element("$", op);
      if (template.isObject()) {
        PactDslJsonBody body = PactDslJsonArray.arrayMinLike(1, 1);
        fillObject(body, template, element, "$[*]", op, nullable);
        PactDslJsonArray root = (PactDslJsonArray) body.closeObject();
        if (nullable.perElement) {
          examplesAsRecorded(root, recorded, need);
        }
        return root;
      }
      return PactDslJsonArray.arrayMinLike(1, 1, rootLeaf(template, "$[*]", op));
    }
    throw unsupported(op, "$", "a " + recorded.getNodeType() + " body");
  }

  private static void fillObject(
      PactDslJsonBody target, JsonNode node, Need need, String path, ConsumerGoldenMasters.Recorded op, Nullable nullable) {
    Iterable<String> names = need.whole ? node::fieldNames : need.fields.keySet();
    for (String name : names) {
      String childPath = path + "." + name;
      JsonNode value = node.get(name);
      if (value == null) {
        throw unsupported(op, childPath, "consumed but not in the recording");
      }
      Need child = need.whole ? Need.WHOLE : need.fields.get(name);
      if (value.isNull()) {
        target.nullValue(name);
      } else if (value.isObject()) {
        // An object null or absent in some element binds its fields as usual, and the array's
        // example becomes the recorded elements (perElement): pact-jvm refuses a null where the
        // example holds an object whatever the matchers say, so "object or null" cannot be one
        // template's rule; each element is matched against its own recorded example instead.
        nullable.perElement |= nullable.contains(childPath);
        PactDslJsonBody nested = target.object(name);
        fillObject(nested, value, child, childPath, op, nullable);
        nested.closeObject();
      } else if (value.isArray()) {
        nullable.perElement |= nullable.contains(childPath);
        nullable.perElement |=
            array(target, name, value, child, childPath, op, nullable.mayBeEmpty(childPath));
      } else {
        leaf(target, name, value, childPath, op, nullable.contains(childPath));
      }
    }
  }

  /**
   * Binds the array {@code name}; true when its example had to be its recorded elements, which an
   * enclosing array's example must then be too (its single template would hold one of them only).
   */
  private static boolean array(
      PactDslJsonBody target,
      String name,
      JsonNode value,
      Need need,
      String path,
      ConsumerGoldenMasters.Recorded op,
      boolean mayBeEmpty) {
    if (value.isEmpty()) {
      target.array(name).closeArray();
      return false;
    }
    Nullable nullable = new Nullable();
    String elementPath = path + "[*]";
    JsonNode template = merge(value, elementPath, nullable);
    Need element = need.whole ? Need.WHOLE : need.element(path, op);
    // Empty in some recorded element: the provider answers no element there, so zero is allowed.
    int min = mayBeEmpty ? 0 : 1;
    if (template.isObject()) {
      PactDslJsonBody body = target.minArrayLike(name, min, 1);
      fillObject(body, template, element, elementPath, op, nullable);
      PactDslJsonArray array = (PactDslJsonArray) body.closeObject();
      array.closeArray();
      if (nullable.perElement) {
        examplesAsRecorded(array, value, need);
      }
      return nullable.perElement;
    } else if (template.isArray()) {
      throw unsupported(op, elementPath, "an array of arrays");
    } else {
      target.minArrayLike(name, min, rootLeaf(template, elementPath, op), 1);
      return false;
    }
  }

  /**
   * Replace {@code array}'s single template example with the recorded elements, each cut to what
   * {@code need} reaches, in recorded order. The matching rules stay those of the merged template
   * ({@code [*]} paths, so they hold for every element); only the examples change, and pact-jvm
   * compares the n-th answered element with the n-th example (the first once they run out). So
   * an element the provider recorded with {@code failure: null} is checked against {@code null},
   * and one recorded with a failure object against that object's fields. The parent holds the
   * same {@code JsonValue} the array builds, so this edits the body in place.
   */
  private static void examplesAsRecorded(PactDslJsonArray array, JsonNode recorded, Need need) {
    JsonValue.Array examples = (JsonValue.Array) array.getBody();
    examples.getValues().clear();
    for (JsonNode element : cut(recorded, need)) {
      examples.getValues().add(JsonParser.parseString(element.toString()));
    }
  }

  private static void leaf(
      PactDslJsonBody target, String name, JsonNode example, String path, ConsumerGoldenMasters.Recorded op, boolean orNull) {
    if (op.ids().contains(path)) {
      requireText(example, path, op);
      if (orNull) {
        target.or(name, example.asText(), new RegexMatcher(UUID_REGEX, example.asText()), NullMatcher.INSTANCE);
      } else {
        target.uuid(name, example.asText());
      }
    } else if (op.instants().contains(path)) {
      requireText(example, path, op);
      if (orNull) {
        target.or(name, example.asText(), new RegexMatcher(ISO_INSTANT, example.asText()), NullMatcher.INSTANCE);
      } else {
        target.stringMatcher(name, ISO_INSTANT, example.asText());
      }
    } else if (orNull) {
      target.or(name, scalar(example), TypeMatcher.INSTANCE, NullMatcher.INSTANCE);
    } else if (example.isTextual()) {
      target.stringType(name, example.asText());
    } else if (example.isNumber()) {
      target.numberType(name, example.numberValue());
    } else if (example.isBoolean()) {
      target.booleanType(name, example.asBoolean());
    } else {
      throw unsupported(op, path, "a " + example.getNodeType() + " leaf");
    }
  }

  private static PactDslJsonRootValue rootLeaf(JsonNode example, String path, ConsumerGoldenMasters.Recorded op) {
    if (op.ids().contains(path)) {
      requireText(example, path, op);
      return PactDslJsonRootValue.uuid(example.asText());
    }
    if (op.instants().contains(path)) {
      requireText(example, path, op);
      return PactDslJsonRootValue.stringMatcher(ISO_INSTANT, example.asText());
    }
    if (example.isTextual()) {
      return PactDslJsonRootValue.stringType(example.asText());
    }
    if (example.isNumber()) {
      return PactDslJsonRootValue.numberType(example.numberValue());
    }
    if (example.isBoolean()) {
      return PactDslJsonRootValue.booleanType(example.asBoolean());
    }
    throw unsupported(op, path, "a " + example.getNodeType() + " array element");
  }

  /** {@code value} with only the fields {@code need} reaches. */
  private static JsonNode cut(JsonNode value, Need need) {
    if (need.whole) {
      return value;
    }
    if (value.isArray()) {
      com.fasterxml.jackson.databind.node.ArrayNode out = MAPPER.createArrayNode();
      value.forEach(element -> out.add(need.element == null ? element : cut(element, need.element)));
      return out;
    }
    ObjectNode out = MAPPER.createObjectNode();
    need.fields.forEach(
        (name, child) -> {
          if (value.has(name)) {
            out.set(name, cut(value.get(name), child));
          }
        });
    return out;
  }

  /**
   * Every element of {@code array} merged into one template: field union, a leaf's first non-null
   * example, and — at any depth — the path of each field that is null or absent in some element
   * noted in {@code nullable}, and of each array that is empty in some element. Nested arrays keep
   * their first non-empty value.
   */
  private static JsonNode merge(JsonNode array, String elementPath, Nullable nullable) {
    JsonNode merged = null;
    for (JsonNode element : array) {
      merged = merged == null ? element.deepCopy() : mergeTwo(merged, element, elementPath, nullable);
    }
    if (merged != null && merged.isObject()) {
      for (JsonNode element : array) {
        markVariance(merged, element, elementPath, nullable);
      }
    }
    return merged;
  }

  /**
   * Compare one element against the merged template, field by field and into nested objects: a
   * field null or absent there is nullable, a non-empty template array that is empty there may be
   * empty.
   */
  private static void markVariance(
      JsonNode template, JsonNode element, String path, Nullable nullable) {
    for (Iterator<Map.Entry<String, JsonNode>> it = template.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> field = it.next();
      String childPath = path + "." + field.getKey();
      JsonNode want = field.getValue();
      JsonNode have = element.get(field.getKey());
      if (have == null || have.isNull()) {
        nullable.add(childPath);
      } else if (want.isObject() && have.isObject()) {
        markVariance(want, have, childPath, nullable);
      } else if (want.isArray() && !want.isEmpty() && have.isArray() && have.isEmpty()) {
        nullable.addEmpty(childPath);
      }
    }
  }

  private static JsonNode mergeTwo(JsonNode into, JsonNode next, String path, Nullable nullable) {
    if (into.isNull()) {
      return next.deepCopy();
    }
    if (!into.isObject() || !next.isObject()) {
      return into;
    }
    ObjectNode target = (ObjectNode) into;
    for (Iterator<Map.Entry<String, JsonNode>> it = next.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> field = it.next();
      JsonNode have = target.get(field.getKey());
      if (have == null || have.isNull()) {
        target.set(field.getKey(), field.getValue().deepCopy());
      } else if (have.isObject()) {
        target.set(field.getKey(), mergeTwo(have, field.getValue(), path + "." + field.getKey(), nullable));
      } else if (have.isArray() && have.isEmpty() && field.getValue().isArray()) {
        target.set(field.getKey(), field.getValue().deepCopy());
      }
    }
    return target;
  }

  private static Object scalar(JsonNode example) {
    if (example.isTextual()) {
      return example.asText();
    }
    if (example.isNumber()) {
      return example.numberValue();
    }
    if (example.isBoolean()) {
      return example.asBoolean();
    }
    throw new IllegalStateException("not a scalar: " + example);
  }

  private static void requireText(JsonNode example, String path, ConsumerGoldenMasters.Recorded op) {
    if (!example.isTextual()) {
      throw unsupported(op, path, "frozen as an id or instant but holds " + example.getNodeType());
    }
  }

  private static IllegalStateException unsupported(ConsumerGoldenMasters.Recorded op, String path, String what) {
    return new IllegalStateException(
        "golden master " + op.provider() + " " + op.state() + "/" + op.operationId() + ": " + path
            + " is " + what);
  }

  /**
   * Paths whose value is null or absent in some element of their array, and paths of arrays that
   * are empty in some element.
   */
  private static final class Nullable {
    private final java.util.Set<String> paths = new java.util.HashSet<>();
    private final java.util.Set<String> empty = new java.util.HashSet<>();
    /** The array's example must be its recorded elements ({@link #examplesAsRecorded}). */
    boolean perElement;

    void add(String path) {
      paths.add(path);
    }

    boolean contains(String path) {
      return paths.contains(path);
    }

    void addEmpty(String path) {
      empty.add(path);
    }

    boolean mayBeEmpty(String path) {
      return empty.contains(path);
    }
  }

  /** The consumed paths, as a tree: named fields, an array's element, or the whole value. */
  static final class Need {
    static final Need WHOLE = new Need(true);

    final boolean whole;
    final LinkedHashMap<String, Need> fields = new LinkedHashMap<>();
    Need element;

    private Need(boolean whole) {
      this.whole = whole;
    }

    Need element(String path, ConsumerGoldenMasters.Recorded op) {
      if (element == null) {
        throw unsupported(op, path, "an array no consumed path reaches into (consume " + path + "[*]...)");
      }
      return element;
    }

    static Need of(List<String> consumes, ConsumerGoldenMasters.Recorded op) {
      Need root = new Need(false);
      for (String path : consumes) {
        if ("$".equals(path)) {
          // A JSON object the client must be able to read, no field of it read: binds "{}".
          continue;
        }
        if (!path.startsWith("$")) {
          throw new IllegalArgumentException("a consumed path starts at $: " + path);
        }
        List<String> tokens = tokens(path.substring(1), path);
        Need at = root;
        for (int i = 0; i < tokens.size(); i++) {
          boolean last = i == tokens.size() - 1;
          String token = tokens.get(i);
          if (at.whole) {
            break;
          }
          if ("[*]".equals(token)) {
            if (at.element == null || last) {
              at.element = last ? WHOLE : (at.element == null ? new Need(false) : at.element);
            }
            at = at.element;
          } else {
            Need next = at.fields.get(token);
            if (next == null || last) {
              next = last ? WHOLE : new Need(false);
              at.fields.put(token, next);
            }
            at = next;
          }
        }
      }
      return root;
    }

    private static List<String> tokens(String rest, String path) {
      List<String> out = new ArrayList<>();
      int i = 0;
      while (i < rest.length()) {
        if (rest.startsWith("[*]", i)) {
          out.add("[*]");
          i += 3;
        } else if (rest.charAt(i) == '.') {
          int end = i + 1;
          while (end < rest.length() && rest.charAt(end) != '.' && rest.charAt(end) != '[') {
            end++;
          }
          out.add(rest.substring(i + 1, end));
          i = end;
        } else {
          throw new IllegalArgumentException("cannot read the consumed path " + path);
        }
      }
      if (out.isEmpty()) {
        throw new IllegalArgumentException("a consumed path names something below $: " + path);
      }
      return out;
    }
  }
}
