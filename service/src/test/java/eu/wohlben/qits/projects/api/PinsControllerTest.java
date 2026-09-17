package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import eu.wohlben.qits.projectsdaemon.protocol.ProjectAgentImage;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceImage;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The launch-pin route — what a container start by this process would pull.
 *
 * <p><b>Neither version is written down here and neither is read from config any more</b>, which is
 * itself the change under test. Both are pom pins carried by a released jar — {@link
 * ProjectAgentImage#VERSION} off {@code qits-projects-daemon-protocol} and {@link
 * WorkspaceImage#VERSION} off {@code qits-workspace-daemon-protocol} — and each is also the tag of
 * the image that release pushed. So the honest assertion is that the route answers those same two
 * constants: a literal would fail the suite on the next bump while proving nothing, and a {@code
 * @ConfigProperty} read of either retired key would assert against a key nothing declares any more,
 * which fails the suite on configuration rather than on behaviour. The <b>image</b> halves stay
 * literals, because the registry-relative spelling is the contract while the configured repo value
 * is fully qualified.
 *
 * <p>The omission rules are exercised against {@link PinsController#pins} directly: a blank version
 * is a config state, and reaching it through a {@code @TestProfile} would cost a Quarkus restart to
 * prove four lines of string handling.
 */
@QuarkusTest
public class PinsControllerTest {

  /**
   * The two pins the route answers, as <b>constants</b> rather than config reads. Each is the
   * version of a released daemon-protocol jar this reactor pins, and that version is also the image
   * tag the matching container starts from — so the constant moves exactly when the pom line moves,
   * which is what makes this assertion survive a bump without being rewritten.
   */
  static final String agentImageVersion = ProjectAgentImage.VERSION;

  static final String refinementImageVersion = WorkspaceImage.VERSION;

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
    assertThat(answer.getString("pins[0].version"), is(agentImageVersion));
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
