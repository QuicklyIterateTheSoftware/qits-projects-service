package eu.wohlben.qits.projects.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * A drift guard over the keys that decide whether this service's application logs, traces and
 * metrics leave the process at all, and where they go — plus two more (qits-1202) that decide
 * whether a request's queries show up as child spans of its trace at all.
 *
 * <p><b>The behavioural proof is not here and deliberately is not.</b> qits-events owns the log
 * half of it: {@code OtelLogBridgeTest} decodes a real {@code ExportLogsServiceRequest} off an
 * offline OTLP stub and asserts identity, severity, body, exception fields and trace correlation,
 * and {@code PackagedLogBridgeIT} repeats the claim against the packaged artifact, where the
 * handler's build-time initialisation is a different question. This repo runs the SAME Quarkus
 * extension on the SAME four log keys, so that evidence carries — and copying the suite into every
 * service would be ten copies of one measurement, nine of them able to rot unnoticed. That all
 * three signals really leave a packaged process, carrying the `qits` oidc client's bearer, is
 * qits-telemetry-core's own claim against its exporters, not this repository's to re-measure.
 *
 * <p>What does NOT carry is the configuration, because it is per-repository and anyone can change it
 * here. Three of the four log keys are Quarkus' own defaults and Quarkus still labels the logging
 * integration preview, so a version bump that flipped one, or an edit that dropped a line, would
 * stop this service's logging with a green build and nothing anywhere to say so. It asserts values
 * rather than presence: a key reverting to {@code ALL}, or to a second separately-configured
 * exporter, is the failure mode, and only the value can see it.
 *
 * <p>The three signals' exporters are pinned beside the log keys (epic qits-428): {@code cdi} is
 * what makes the SDK take qits-telemetry-core's CDI beans instead of building Quarkus' own OTLP
 * exporters, and the receiver's address is asserted RESOLVED at its edge host — the library's own
 * default, not configured here. {@code qits.observability.url} and
 * {@code quarkus.otel.exporter.otlp.*} are gone: with the library on the classpath none of Quarkus'
 * own OTLP exporters is ever built, so they reached nothing.
 *
 * <p>The values come from {@code service/src/main/resources/application.properties}, which the suite
 * inherits rather than shadows, so this is the shipped config under test. The suite also runs with
 * {@code quarkus.otel.sdk.disabled=true} under {@code %test} — nothing is exported during a build,
 * which is exactly why the configuration needs a reader of its own.
 *
 * <p>{@link #jdbcSpansAreOnForBothDatasources()} pins a second, unrelated pair: {@code
 * quarkus.datasource.projects.jdbc.telemetry} and {@code quarkus.datasource.epics.jdbc.telemetry},
 * the Agroal-side switch that wraps each named datasource's JDBC objects with {@code
 * io.opentelemetry.instrumentation:opentelemetry-jdbc} so a query shows up as a child span of the
 * request that issued it. Both are BUILD-TIME keys (read by {@code
 * io.quarkus.agroal.runtime.DataSourceJdbcBuildTimeConfig}), so — like the log keys above — a
 * dropped line here is invisible at runtime and only a value assertion at augmentation time catches
 * it.
 */
@QuarkusTest
class OtelLogConfigTest {

  @Inject Config config;

  @Test
  void applicationLogsAreExportedOverOtlp() {
    // The handler that turns an org.jboss.logging.Logger call into an OTLP record, and the switch
    // that lets it exist at all. Either one false is silence.
    assertEquals("true", value("quarkus.otel.logs.enabled"));
    assertEquals("true", value("quarkus.otel.logs.handler.enabled"));

    // `cdi` routes the records at the exporter configured below, rather than at a second one
    // configured somewhere else — or at none.
    assertEquals("cdi", value("quarkus.otel.logs.exporter"));

    // The one value here that is not Quarkus' default (ALL): the deliberate outbound floor.
    assertEquals("INFO", value("quarkus.otel.logs.level"));
  }

  @Test
  void everySignalGoesThroughTheCdiExporters() {
    // qits-telemetry-core's beans are the exporters (epic qits-428): `cdi` is what makes the SDK
    // take them. Quarkus' default for all three, pinned for the reason the logs keys are.
    assertEquals("cdi", value("quarkus.otel.traces.exporter"));
    assertEquals("cdi", value("quarkus.otel.logs.exporter"));
    assertEquals("true", value("quarkus.otel.metrics.enabled"));
    assertEquals("cdi", value("quarkus.otel.metrics.exporter"));
  }

  @Test
  void theExportersPointAtTheReceiversEdgeHost() {
    // The library's default, not overridden here: the receiver at its edge host, QITS_DOMAIN
    // expanded. The exporters append /v1/<signal>.
    assertEquals(
        "https://observability.qits." + value("QITS_DOMAIN") + "/observability/api/otel",
        value("qits.telemetry.endpoint"));
    assertEquals("true", value("qits.telemetry.bearer.enabled"));
    assertEquals("qits", value("qits.telemetry.bearer.client"));
    // The wire-alias address is gone with Quarkus' own exporter, which was all that read it.
    assertTrue(
        config.getOptionalValue("qits.observability.url", String.class).isEmpty(),
        "qits.observability.url reaches no exporter and must not come back");
  }

  @Test
  void jdbcSpansAreOnForBothDatasources() {
    // qits-1202: a request trace showed one SERVER span with its queries invisible inside it.
    // Both named datasources (domain's `projects`, entities' `epics`) wrap at the Agroal layer, so
    // a span-free query is silent the same way a dropped log record would be — only the value, not
    // the key's mere presence, can catch either one reverting.
    assertEquals("true", value("quarkus.datasource.projects.jdbc.telemetry"));
    assertEquals("true", value("quarkus.datasource.epics.jdbc.telemetry"));
  }

  private String value(String key) {
    return config
        .getOptionalValue(key, String.class)
        .orElseThrow(() -> new AssertionError(key + " is not set"));
  }
}
