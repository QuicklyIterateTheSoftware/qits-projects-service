package eu.wohlben.qits.entities.entity;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * <b>Why a DROPPED work item was closed</b> (qits-248), stored as the enum name in {@code
 * entity.outcome} (epics V30) and closed there by {@code ck_entity_outcome}.
 *
 * <p>DROPPED says one thing — this work will not be done — and before this enum the reason lived
 * only as prose on the thread. Five different closes shared that one word: the subject went away,
 * another item fixed it, it duplicated another, it was a probe, somebody decided against it. Each is
 * said here, and three of them name the item that answers for it ({@code supersededBy}).
 *
 * <p><b>An outcome is set only together with a move into DROPPED, and every such move carries
 * one</b> ({@code WorkEntityService.move}); a reopen clears it. A row DROPPED before V30 has none,
 * which reads as "unrecorded" and is never backfilled.
 */
public enum WorkOutcome {

  /** Another item asks for the same thing. Names it in {@code supersededBy}. */
  DUPLICATE(true),

  /** Another item's change resolved it. Names it in {@code supersededBy}. */
  RESOLVED_ELSEWHERE(true),

  /** Replaced by a successor — what the epic supersede records. Names it in {@code supersededBy}. */
  SUPERSEDED(true),

  /** The subject went away by itself; nothing is left to do. Names nothing. */
  OBSOLETE(false),

  /** It never was a problem (a misreport, a stale environment). Names nothing. */
  INVALID(false),

  /** A decision was taken not to do it — scratch and probe items included. Names nothing. */
  WONT_DO(false);

  private final boolean namesSuccessor;

  WorkOutcome(boolean namesSuccessor) {
    this.namesSuccessor = namesSuccessor;
  }

  /**
   * Whether this outcome <b>requires</b> {@code supersededBy} — and, read the other way, whether it
   * is the kind that may carry one at all: the three that name something require it, the three that
   * do not refuse it.
   */
  public boolean namesSuccessor() {
    return namesSuccessor;
  }

  /** The six words, in declaration order — for a refusal that lists what would have been legal. */
  public static List<String> words() {
    return Arrays.stream(values()).map(Enum::name).toList();
  }

  /** The outcome a word names, case-insensitively; empty for a blank or unknown word. */
  public static Optional<WorkOutcome> parse(String word) {
    if (word == null || word.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(valueOf(word.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException unknown) {
      return Optional.empty();
    }
  }
}
