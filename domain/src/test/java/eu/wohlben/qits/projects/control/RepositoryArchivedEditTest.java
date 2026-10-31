package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.error.CodedRefusalException;
import org.junit.jupiter.api.Test;

/** The text edit behind the archive action (qits-1784): one line moves, every other byte stays. */
class RepositoryArchivedEditTest {

  /** qits-containers-javalib's {@code .config/qits/repository.yml}, verbatim. */
  private static final String REAL_WORLD =
      """
      # This repository's qits configuration — the single source of truth for its services, actions and
      # bootstrap chain. It is read in-container per workspace from your branch's checkout, so changing it
      # is an ordinary commit and takes effect in every workspace whose branch carries it.
      #
      # A blank component has nothing to run yet. Uncomment and fill in the sections below as it grows.
      #
      # `repository.archetype` is deliberately absent: the directory this repository is mounted under in
      # its project's wrapper decides that, and a value here would only be a second opinion.
      version: 1

      repository:
        main-branch: main

      # Long-running processes a workspace can start — dev servers, watchers. `web-view` makes one
      # viewable in the browser through qits' proxy.
      # services:
      #   - name: dev server
      #     description: The component's dev server.
      #     start: ./run-dev.sh
      #     web-view:
      #       port: 8080
      #       entry-path: /

      # One-shot commands runnable from the workspace — builds, test suites, linters.
      # actions:
      #   - name: build
      #     execute: ./build.sh

      # Commands run once when a workspace container is first provisioned, in order.
      # bootstrap:
      #   - name: install dependencies
      #     execute: ./install.sh
      """;

  @Test
  void aRealWorldFileGainsOneLineAndKeepsEveryOtherByte() {
    RepositoryArchivedEdit.Edit edit = RepositoryArchivedEdit.apply(REAL_WORLD, true);

    assertTrue(edit.changed());
    String expected =
        REAL_WORLD.replace("repository:\n  main-branch: main\n", "repository:\n  archived: true\n  main-branch: main\n");
    assertEquals(expected, edit.content());
    // Exactly one line more, and removing it gives the original back byte for byte.
    assertEquals(REAL_WORLD, edit.content().replace("  archived: true\n", ""));
    assertTrue(RepositoryArchivedEdit.archived(edit.content()));
    assertFalse(RepositoryArchivedEdit.archived(REAL_WORLD));
  }

  @Test
  void anAbsentFileIsCreatedWithTheArchivedFlag() {
    RepositoryArchivedEdit.Edit edit = RepositoryArchivedEdit.apply(null, true);

    assertTrue(edit.changed());
    assertEquals(
        "# This repository's qits configuration. `repository.archived` was written by the"
            + " platform's archive action (qits-1784).\n"
            + "version: 1\n"
            + "repository:\n"
            + "  archived: true\n",
        edit.content());
    assertTrue(RepositoryArchivedEdit.archived(edit.content()));
  }

  @Test
  void anAbsentFileIsAlreadyUnarchived() {
    RepositoryArchivedEdit.Edit edit = RepositoryArchivedEdit.apply(null, false);

    assertFalse(edit.changed());
    assertNull(edit.content());
  }

  @Test
  void anExistingFalseIsReplacedInPlaceKeepingItsComment() {
    String original =
        "version: 1\nrepository:\n  main-branch: main\n  archived: false  # set by hand\nother: x\n";

    RepositoryArchivedEdit.Edit edit = RepositoryArchivedEdit.apply(original, true);

    assertEquals(
        "version: 1\nrepository:\n  main-branch: main\n  archived: true  # set by hand\nother: x\n",
        edit.content());
  }

  @Test
  void anExistingFalseIsAlreadyUnarchived() {
    String original = "repository:\n  archived: false\n";

    assertFalse(RepositoryArchivedEdit.apply(original, false).changed());
  }

  @Test
  void anArchivedFileIsAlreadyArchived() {
    String original = "repository:\n    archived: true\n";

    RepositoryArchivedEdit.Edit edit = RepositoryArchivedEdit.apply(original, true);

    assertFalse(edit.changed());
    assertEquals(original, edit.content());
  }

  @Test
  void unarchiveRemovesTheLineAndNothingElse() {
    String archived = RepositoryArchivedEdit.apply(REAL_WORLD, true).content();

    RepositoryArchivedEdit.Edit edit = RepositoryArchivedEdit.apply(archived, false);

    assertTrue(edit.changed());
    assertEquals(REAL_WORLD, edit.content());
  }

  @Test
  void unarchiveMayLeaveAnEmptySection() {
    RepositoryArchivedEdit.Edit edit =
        RepositoryArchivedEdit.apply("version: 1\nrepository:\n  archived: true\n", false);

    assertEquals("version: 1\nrepository:\n", edit.content());
    assertFalse(RepositoryArchivedEdit.archived(edit.content()));
  }

  @Test
  void theInsertedLineFollowsTheSectionsOwnIndentation() {
    String original = "repository:\n    main-branch: main\n";

    assertEquals(
        "repository:\n    archived: true\n    main-branch: main\n",
        RepositoryArchivedEdit.apply(original, true).content());
  }

  @Test
  void aFileWithNoRepositorySectionGetsOneAppended() {
    String original = "# a comment\nversion: 1\n";

    RepositoryArchivedEdit.Edit edit = RepositoryArchivedEdit.apply(original, true);

    assertEquals("# a comment\nversion: 1\n\nrepository:\n  archived: true\n", edit.content());
  }

  @Test
  void aFlowStyleSectionIsRefused() {
    CodedRefusalException refused =
        assertThrows(
            CodedRefusalException.class,
            () -> RepositoryArchivedEdit.apply("version: 1\nrepository: {main-branch: main}\n", true));

    assertEquals(422, refused.statusCode());
    assertEquals(RepositoryArchivedEdit.REFUSED, refused.code());
  }

  @Test
  void aDocumentThatDoesNotParseIsRefused() {
    CodedRefusalException refused =
        assertThrows(
            CodedRefusalException.class,
            () -> RepositoryArchivedEdit.apply("repository:\n  main-branch: [unclosed\n", true));

    assertEquals(422, refused.statusCode());
  }

  @Test
  void aRepositoryThatIsNotAMappingIsRefused() {
    assertThrows(
        CodedRefusalException.class,
        () -> RepositoryArchivedEdit.apply("repository:\n  - a\n  - b\n", true));
  }

  @Test
  void anArchivedValueOnItsOwnLineBelowTheKeyIsRefusedRatherThanGuessed() {
    // `archived:` with its value on the next line: replacing the key's line leaves the value behind,
    // and the re-parse refuses the result instead of writing it.
    assertThrows(
        CodedRefusalException.class,
        () -> RepositoryArchivedEdit.apply("repository:\n  archived:\n    false\n", true));
  }
}
