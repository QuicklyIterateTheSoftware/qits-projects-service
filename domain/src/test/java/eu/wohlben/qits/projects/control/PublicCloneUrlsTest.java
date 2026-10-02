package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublicCloneUrlsTest {

  private static PublicCloneUrls urls(String publicUrl, String domain) {
    PublicCloneUrls urls = new PublicCloneUrls();
    urls.publicUrl = Optional.ofNullable(publicUrl);
    urls.domain = Optional.ofNullable(domain);
    return urls;
  }

  @Test
  void theConfiguredOriginWinsAndLosesItsTrailingSlash() {
    assertEquals(
        "https://git.example.test/git/qits/qits-ci-service",
        urls("https://git.example.test/", "qits.wohlben.eu").cloneUrl("qits", "qits-ci-service"));
  }

  @Test
  void withoutOneTheGitHostOfTheDomainIsUsed() {
    assertEquals(
        "https://githost.qits.wohlben.eu/git/qits/qits-ci-service",
        urls(null, "qits.wohlben.eu").cloneUrl("qits", "qits-ci-service"));
  }

  @Test
  void withNeitherOrWithoutANameThereIsNoUrl() {
    assertNull(urls(null, null).cloneUrl("qits", "qits-ci-service"));
    assertNull(urls(" ", " ").cloneUrl("qits", "qits-ci-service"));
    assertNull(urls(null, "qits.wohlben.eu").cloneUrl("qits", null));
    assertNull(urls(null, "qits.wohlben.eu").cloneUrl(null, "qits-ci-service"));
  }
}
