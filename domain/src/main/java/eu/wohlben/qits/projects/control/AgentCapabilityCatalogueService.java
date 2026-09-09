package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.AgentCapabilityCatalogueDto;
import eu.wohlben.qits.projects.dto.AgentCapabilityImageVersionDto;
import eu.wohlben.qits.projects.dto.AgentHarnessCapabilityDto;
import eu.wohlben.qits.projects.entity.AgentHarness;
import eu.wohlben.qits.projects.entity.AgentHarnessCapability;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.persistence.AgentHarnessCapabilityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Recording what containers report their harnesses can do, and serving the editor the catalogue
 * behind its model and effort dropdowns.
 *
 * <p><b>Written on the way in, read on the way out, and the two sides have different problems.</b>
 * The write is a container reporting at its own start — one row per {@code (harness, image version)},
 * replaced each time that pair reports again. The read is the editor's, which has no container in
 * front of it and may neither spawn a process nor block on one; it is one query and a fold.
 *
 * <p><b>Newest wins whole; the losers are named, not merged.</b> Where two builds of the workspace
 * image report different answers for the same harness, a union would offer a set of models no single
 * binary actually has — pick one from build A and one from build B and the launch fails on whichever
 * container it lands in. So the newest report is served entire, saying which image version it came
 * from, and the other versions appear beside it as {@code otherImageVersions}: a fact an operator can
 * read, rather than content folded into the answer.
 *
 * <p><b>Newest by arrival, not by version string.</b> A CalVer image version happens to sort, but an
 * image version is a <em>name</em> and a report is an <em>event</em>; only the second has a time. A
 * rebuilt-and-rolled-back image would sort wrong and time right, and time is what the editor means
 * by "what is running now".
 *
 * <p><b>An empty cache is a supported state, not a degraded one.</b> A harness nobody has reported
 * answers {@link AgentCapabilityDefaults}, flagged {@code shipped}, so the editor works on a fresh
 * estate before a single container has started. A row naming a harness this service does not know is
 * ignored rather than fatal, for the reason an unknown surface reads as a default: a newer peer must
 * be able to report something this service will understand next release.
 */
@ApplicationScoped
public class AgentCapabilityCatalogueService {

  @Inject AgentHarnessCapabilityRepository capabilities;

  // -------------------------------------------------------------------------------------------
  // Read
  // -------------------------------------------------------------------------------------------

  /**
   * The catalogue: one entry per harness, the newest report for it, shipped fallback where nothing
   * has reported.
   *
   * <p>Every harness the platform knows is present, in {@link AgentHarness}'s own order, so the
   * editor's harness dropdown and this catalogue cannot disagree about what exists.
   */
  public AgentCapabilityCatalogueDto catalogue() {
    Map<String, List<AgentHarnessCapability>> byHarness = new LinkedHashMap<>();
    for (AgentHarnessCapability row : capabilities.allNewestFirst()) {
      byHarness.computeIfAbsent(row.harness, key -> new ArrayList<>()).add(row);
    }
    List<AgentHarnessCapabilityDto> answer = new ArrayList<>();
    for (AgentHarness harness : AgentHarness.values()) {
      List<AgentHarnessCapability> reports = byHarness.get(harness.name());
      answer.add(
          reports == null || reports.isEmpty()
              ? AgentCapabilityDefaults.shipped(harness)
              : toDto(reports.get(0), reports.subList(1, reports.size())));
    }
    return new AgentCapabilityCatalogueDto(List.copyOf(answer));
  }

  // -------------------------------------------------------------------------------------------
  // Write
  // -------------------------------------------------------------------------------------------

  /**
   * Record one container's report — every harness it probed, keyed by the image version it runs.
   *
   * <p>Upsert per {@code (harness, image version)}: a container restarting on the same build replaces
   * its own row rather than adding one, so the table grows with image builds and not with container
   * starts.
   *
   * <p><b>A failed probe is still recorded.</b> The report says so ({@code probeFailed}) and the
   * lists hold whatever the reporter fell back to; the editor then shows a usable dropdown and can
   * say why it may be stale. Dropping the row instead would leave the editor on a shipped fallback
   * with nothing to explain it.
   *
   * @param reportedBy free text naming the reporting container; display only
   * @param imageVersion the image build it runs; blank is allowed and means "could not name it"
   * @param reports one entry per harness the container probed
   * @return how many rows were written
   */
  @Transactional
  public int record(String reportedBy, String imageVersion, List<AgentHarnessCapabilityDto> reports) {
    if (reports == null || reports.isEmpty()) {
      throw new BadRequestException("A capability report must name at least one harness");
    }
    String image = imageVersion == null ? "" : imageVersion.trim();
    String reporter = reportedBy == null || reportedBy.isBlank() ? "unnamed" : reportedBy.trim();
    Instant now = Instant.now();
    int written = 0;
    for (AgentHarnessCapabilityDto report : reports) {
      AgentHarness harness =
          parseHarness(report.harness())
              .orElseThrow(
                  () ->
                      new BadRequestException(
                          "Unknown harness in capability report: "
                              + report.harness()
                              + " (known: CLAUDE, KIMI)"));
      AgentHarnessCapability row =
          capabilities.find(harness.name(), image).orElseGet(AgentHarnessCapability::new);
      boolean fresh = row.id == null;
      if (fresh) {
        row.id = UUID.randomUUID().toString();
        row.harness = harness.name();
        row.imageVersion = image;
      }
      row.harnessVersion = blankIfNull(report.harnessVersion());
      row.models = AgentHarnessCapability.encode(orEmpty(report.models()));
      row.effortLevels =
          AgentHarnessCapability.encode(
              // A harness with no effort concept carries no levels, whatever it sent. Storing
              // levels beside effortSupported=false would be a set the editor must never show and
              // the render path must never pass.
              report.effortSupported() ? orEmpty(report.effortLevels()) : List.of());
      row.modelsEnumerated = report.modelsEnumerated();
      row.effortSupported = report.effortSupported();
      row.authenticated = report.authenticated();
      row.authDetail = blankIfNull(report.authDetail());
      row.probeFailed = report.probeFailed();
      row.probeDetail = blankIfNull(report.probeDetail());
      row.reportedBy = reporter;
      row.reportedAt = now;
      if (fresh) {
        capabilities.persist(row);
      }
      written++;
    }
    capabilities.flush();
    return written;
  }

  // -------------------------------------------------------------------------------------------
  // Plumbing
  // -------------------------------------------------------------------------------------------

  private static AgentHarnessCapabilityDto toDto(
      AgentHarnessCapability newest, List<AgentHarnessCapability> superseded) {
    return new AgentHarnessCapabilityDto(
        newest.harness,
        newest.imageVersion,
        newest.harnessVersion,
        newest.modelList(),
        newest.modelsEnumerated,
        newest.effortSupported,
        newest.effortLevelList(),
        newest.authenticated,
        newest.authDetail,
        newest.probeFailed,
        newest.probeDetail,
        newest.reportedBy,
        newest.reportedAt == null ? null : newest.reportedAt.toString(),
        false,
        superseded.stream()
            .map(
                row ->
                    new AgentCapabilityImageVersionDto(
                        row.imageVersion, row.reportedAt == null ? null : row.reportedAt.toString()))
            .toList());
  }

  private static Optional<AgentHarness> parseHarness(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(AgentHarness.valueOf(value.trim()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static List<String> orEmpty(List<String> values) {
    return values == null ? List.of() : values;
  }

  private static String blankIfNull(String value) {
    return value == null ? "" : value;
  }
}
