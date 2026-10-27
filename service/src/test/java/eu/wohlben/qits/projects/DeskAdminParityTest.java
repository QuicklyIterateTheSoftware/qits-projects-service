package eu.wohlben.qits.projects;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import eu.wohlben.qits.archrules.DeskAdminParityRules;
import java.util.Set;

/**
 * <b>{@code qits:desk-admin} is admitted wherever {@code qits:admin-agent} is</b> (epic qits-1621),
 * read off the effective {@code @RolesAllowed} of every route on both surfaces this service serves
 * — {@code projects.api} and {@code entities.api} — except the refusals below, which are the
 * delivery desk's own rights or a written ref.
 *
 * <p>{@code AgentSurfaceConfigurationController.update} is not here: it admits the desk-admin and
 * refuses it in the body for {@code project.delivery} alone, which the rule cannot see; {@code
 * DeskAdminRightsDoorTest} pins that 403 instead. The release-request create and join stay refused
 * to the desk in their bodies, as before.
 */
@AnalyzeClasses(
    packages = {"eu.wohlben.qits.projects", "eu.wohlben.qits.entities"},
    importOptions = ImportOption.DoNotIncludeTests.class)
class DeskAdminParityTest {

  /** The desk's own rights, then a commit, a push or another ref write. */
  static final Set<String> REFUSED =
      Set.of(
          "AgentMcpCatalogController.save",
          "AgentMcpCatalogController.delete",
          "GcController.collectTags",
          "ProjectController.create",
          "ProjectController.delete",
          "ProjectController.backupSyncProject",
          "RepositoryController.deleteBranch",
          "RepositoryController.delete",
          "RepositoryController.pull",
          "RepositoryController.push",
          "RepositoryController.sync",
          "RepositoryController.backupSync",
          // The repository sign-in terminal is an interactive git push to the forge twin.
          "RemoteLoginTerminalSocket.onOpen",
          "RemoteLoginTerminalSocket.onMessage");

  @ArchTest
  static final ArchRule PARITY = DeskAdminParityRules.everyAdminAgentRouteAdmitsDeskAdmin(REFUSED);

  @ArchTest
  static final ArchRule REFUSALS = DeskAdminParityRules.refusedRoutesExistAndRefuse(REFUSED);
}
