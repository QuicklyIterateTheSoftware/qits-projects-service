package eu.wohlben.qits.projects.control;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import eu.wohlben.qits.projects.entity.ReleaseRequest;

/**
 * <b>{@link ReleaseRequestStates} is the only writer of a release request's state</b> (qits-1153).
 * A write anywhere else would change the state without announcing {@code
 * ReleaseRequestStateChanged}, and an agent waiting on that request would never wake.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.projects",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ReleaseRequestStatesRuleTest {

  @ArchTest
  static final ArchRule ONLY_THE_STATES_WRITE_THE_STATE =
      noClasses()
          .that()
          .doNotHaveFullyQualifiedName(ReleaseRequestStates.class.getName())
          .should()
          .setField(ReleaseRequest.class, "state")
          .because("ReleaseRequestStates.move is what announces a state change (qits-1153)");
}
