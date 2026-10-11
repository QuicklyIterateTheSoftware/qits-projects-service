package eu.wohlben.qits.projects;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import eu.wohlben.qits.archrules.HttpClientRules;

/**
 * The platform's shared ArchUnit rules over this module's classes. {@code service} is where every
 * host adapter that builds a {@code java.net.http.HttpClient} lives, so this is the completeness
 * guard for traced service-to-service HTTP: a class that calls {@code HttpClient.newBuilder()} or
 * {@code HttpClient.newHttpClient()} must also touch {@link
 * eu.wohlben.qits.http.QitsHttpClients} (inject it and {@code wrap(...)} the client) or declare
 * {@code @RawHttpClient(reason = "...")} for a client that must stay untraced — an external host, an
 * OTLP exporter.
 *
 * <p>The rule set lives in qits-arch-rules (qits-integrations-quarkus); a new set added there
 * arrives here as one more {@code @ArchTest} line, the same shape {@code domain}'s and {@code
 * entities}' own {@code ArchRulesTest} take for the causation-row guard.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.projects",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRulesTest {

  @ArchTest static final ArchTests HTTP_CLIENTS = ArchTests.in(HttpClientRules.class);
}
