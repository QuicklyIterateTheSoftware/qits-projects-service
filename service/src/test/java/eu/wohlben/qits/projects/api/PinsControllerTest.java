package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import eu.wohlben.qits.projectsdaemon.protocol.ProjectAgentImage;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The launch-pin route — what a container start by this process would pull.
 *
 * <p>The versions are read from the source each one actually has rather than written down here: they
 * are a release train's to move, and a literal would fail the suite on the next bump while proving
 * nothing. What is asserted about them is that the answer carries <em>this process's</em> value. The
 * <b>image</b> halves are literals, because the registry-relative spelling is the contract while the
 * configured value is fully qualified.
 *
 * <p><b>The two rows no longer read the same kind of source, and that is the change under test as
 * much as anything.</b> The agent version is {@link ProjectAgentImage#VERSION} — a pom pin carried
 * by the released jar — while the refinement version is still a configuration key qits-configuration
 * writes on every {@code qits/workspace} release. Reading the agent row through {@code
 * @ConfigProperty("qits.projects.agent-image-version")} would now assert against a retired key that
 * is normally absent, and {@code …-version-override} is shipped unset, so there is no config value
 * to compare to at all.
 *
 * <p>The omission rules are exercised against {@link PinsController#pins} directly: a blank version
 * is a config state, and reaching it through a {@code @TestProfile} would cost a Quarkus restart to
 * prove four lines of string handling.
 */
@QuarkusTest
public class PinsControllerTest {

  @ConfigProperty(name = "qits.projects.refinement-image-version")
  String refinementImageVersion;

  @Test
  public void theTwoLaunchImagesAnswerRegistryRelativeAndInImageOrder() {
    JsonPath answer =
        given().when().get("/projects/api/pins").then().statusCode(200).extract().jsonPath();

    assertThat(answer.getString("generatedAt"), notNullValue());
    // Ordered by image, then by what launches it — the consumer diffs one run against the next,
    // and the registry host the launch reference carries is not the registry's own name for it.
    assertThat(
        answer.getList("pins.image", String.class),
        is(List.of("qits/project-agent", "qits/workspace")));
    assertThat(answer.getList("pins.launches", String.class), is(List.of("agent", "refinement")));
    assertThat(answer.getString("pins[0].version"), is(ProjectAgentImage.VERSION));
    assertThat(answer.getString("pins[1].version"), is(refinementImageVersion));
  }

  /** A half-composed reference names nothing, so the row is left out rather than half-answered. */
  @Test
  public void aBlankVersionOmitsTheRowAndAnEmptyAnswerIsValid() {
    List<PinsController.LaunchPin> oneBlank =
        PinsController.pins(
            "registry.dev.localhost:8080/qits/project-agent",
            "2026.904.160152",
            "registry.dev.localhost:8080/qits/workspace",
            "  ");
    assertThat(oneBlank.size(), is(1));
    assertThat(oneBlank.get(0).launches(), is("agent"));

    assertThat(PinsController.pins("qits/project-agent", null, "", ""), is(List.of()));
  }

  /**
   * A first segment carrying a {@code .} or a {@code :} is a registry host; anything else is the
   * namespace the registry itself holds the image under.
   */
  @Test
  public void onlyALeadingHostSegmentIsStripped() {
    assertThat(
        PinsController.registryRelative("registry.dev.localhost:8080/qits/project-agent"),
        is("qits/project-agent"));
    assertThat(
        PinsController.registryRelative("localhost:5000/qits/workspace"), is("qits/workspace"));
    assertThat(PinsController.registryRelative("qits/workspace"), is("qits/workspace"));
    assertThat(
        PinsController.registryRelative("qits/build-images/maven"), is("qits/build-images/maven"));
    assertThat(PinsController.registryRelative("workspace"), is("workspace"));
  }
}
