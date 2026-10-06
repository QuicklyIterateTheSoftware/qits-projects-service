package eu.wohlben.qits.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Holds service/pom.xml's surefire executions to the annotations they stand for (qits-965).
 *
 * <p>A surefire JVM never unloads a Quarkus application it has booted, so the pom runs this module's
 * suite in one fork per application: {@code default-test} for every class on the default
 * application, and one {@code app-*} execution per {@code @TestProfile} or test resource, naming
 * exactly the classes that boot it (AGENTS.md "The test-profile budget rule"). Nothing in surefire
 * derives that grouping, so this test does the deriving and compares:
 *
 * <ul>
 *   <li>every {@code *Test} class carrying a class-level {@code @TestProfile}, {@code
 *       @WithTestResource} or {@code @QuarkusTestResource} is included by exactly one {@code app-*}
 *       execution and excluded from {@code default-test} — otherwise it boots its application inside
 *       the default fork, which is the accumulation the split exists to stop;
 *   <li>every class an {@code app-*} execution names carries such an annotation — otherwise it boots
 *       the default application a second time, in a fork of its own;
 *   <li>all the classes of one {@code app-*} execution boot the same application — two in one fork is
 *       two applications held at once again.
 * </ul>
 *
 * <p>Plain JUnit, no application: this reads the pom and the test sources.
 */
class OwnApplicationForksTest {

  static final Path POM = Path.of("pom.xml");

  static final Path SOURCES = Path.of("src/test/java");

  static final String DEFAULT_EXECUTION = "default-test";

  /** A class-level annotation giving the class an application of its own, and what it names. */
  static final Pattern OWN_APPLICATION =
      Pattern.compile(
          "(?m)^@(TestProfile|WithTestResource|QuarkusTestResource)\\(\\s*([A-Za-z0-9_.]+)\\.class");

  @Test
  void everyApplicationOfItsOwnRunsInAForkOfItsOwn() throws Exception {
    Map<String, String> applicationOf = annotatedClasses();
    Map<String, Execution> executions = surefireExecutions();

    Execution defaults = executions.remove(DEFAULT_EXECUTION);
    assertTrue(defaults != null, "service/pom.xml has no surefire execution " + DEFAULT_EXECUTION);

    Map<String, String> forkOf = new TreeMap<>();
    List<String> problems = new ArrayList<>();
    for (Execution fork : executions.values()) {
      Set<String> applications = new TreeSet<>();
      for (String include : fork.includes) {
        String previous = forkOf.put(include, fork.id);
        if (previous != null) {
          problems.add(include + " is included by both " + previous + " and " + fork.id);
        }
        String application = applicationOf.get(include);
        if (application == null) {
          problems.add(
              fork.id
                  + " includes "
                  + include
                  + ", which boots no application of its own (or does not exist): it belongs in"
                  + " "
                  + DEFAULT_EXECUTION);
        } else {
          applications.add(application);
        }
      }
      if (applications.size() > 1) {
        problems.add(fork.id + " boots more than one application: " + applications);
      }
    }
    for (Map.Entry<String, String> annotated : applicationOf.entrySet()) {
      String file = annotated.getKey();
      if (!forkOf.containsKey(file)) {
        problems.add(
            file
                + " boots "
                + annotated.getValue()
                + " but no app-* execution includes it: add it to the execution of that"
                + " application, or a new one");
      }
      if (!defaults.excludes.contains(file)) {
        problems.add(file + " boots " + annotated.getValue() + " but " + DEFAULT_EXECUTION
            + " does not exclude it");
      }
    }
    for (String excluded : defaults.excludes) {
      if (!excluded.contains("*") && !applicationOf.containsKey(excluded)) {
        problems.add(DEFAULT_EXECUTION + " excludes " + excluded
            + ", which boots no application of its own (or does not exist): it would never run");
      }
    }

    assertEquals(List.of(), problems, "service/pom.xml's surefire forks and the test sources disagree");
  }

  /** Each annotated {@code *Test} source, relative to src/test/java, with what it boots. */
  private static Map<String, String> annotatedClasses() throws IOException {
    Map<String, String> found = new TreeMap<>();
    try (Stream<Path> files = Files.walk(SOURCES)) {
      for (Path file : (Iterable<Path>) files::iterator) {
        if (!file.toString().endsWith("Test.java")) {
          continue;
        }
        Matcher matcher = OWN_APPLICATION.matcher(Files.readString(file));
        StringBuilder application = new StringBuilder();
        while (matcher.find()) {
          application.append(application.isEmpty() ? "" : " + ");
          application.append('@').append(matcher.group(1)).append('(').append(matcher.group(2)).append(')');
        }
        if (!application.isEmpty()) {
          found.put(SOURCES.relativize(file).toString().replace('\\', '/'), application.toString());
        }
      }
    }
    return found;
  }

  record Execution(String id, Set<String> includes, Set<String> excludes) {}

  /** The surefire executions declared in this module's own {@code <build><plugins>}. */
  private static Map<String, Execution> surefireExecutions() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    Document pom = factory.newDocumentBuilder().parse(POM.toFile());
    Element build = child(pom.getDocumentElement(), "build");
    Map<String, Execution> executions = new LinkedHashMap<>();
    for (Element plugin : children(child(build, "plugins"), "plugin")) {
      if (!"maven-surefire-plugin".equals(text(child(plugin, "artifactId")))) {
        continue;
      }
      for (Element execution : children(child(plugin, "executions"), "execution")) {
        Element configuration = child(execution, "configuration");
        String id = text(child(execution, "id"));
        executions.put(
            id,
            new Execution(
                id,
                values(child(configuration, "includes"), "include"),
                values(child(configuration, "excludes"), "exclude")));
      }
    }
    return executions;
  }

  private static Set<String> values(Element list, String name) {
    Set<String> values = new TreeSet<>();
    for (Element value : children(list, name)) {
      values.add(text(value));
    }
    return values;
  }

  private static Element child(Element parent, String name) {
    List<Element> found = children(parent, name);
    return found.isEmpty() ? null : found.getFirst();
  }

  private static List<Element> children(Element parent, String name) {
    List<Element> found = new ArrayList<>();
    if (parent == null) {
      return found;
    }
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node instanceof Element element && element.getTagName().equals(name)) {
        found.add(element);
      }
    }
    return found;
  }

  private static String text(Element element) {
    return element == null ? null : element.getTextContent().trim();
  }
}
