package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.AgentSurfaceConfigurationDto;
import eu.wohlben.qits.projects.entity.AgentHarness;
import eu.wohlben.qits.projects.entity.AgentPermissionMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The development guide (qits-1679): how to develop on the platform, served at {@code GET
 * /projects/api/guide} and read with {@code qits help guide}.
 *
 * <p><b>Stored as agent-surface rows.</b> Each part is a row of {@code agent_surface_configuration}
 * under a {@value #KEY_PREFIX} key, its text in {@code system_prompt}, so it is stored, versioned
 * and edited like a surface ({@code PUT /agent-surfaces/guide.flow}). The rows never reach the
 * agent configuration document: {@link AgentSurfaceConfigurationService#listAll} leaves them out.
 *
 * <p><b>Variants.</b> {@code workstation} is for a person on a workstation. The other three are for
 * the platform's holders: a workspace agent, the delivery desk and a refinement room. An
 * in-platform variant is its part, then the holder's own brief (its surface's live system prompt,
 * else its initial prompt, see {@link #HOLDER_SURFACE}) where one is set, then the shared
 * development flow ({@value #FLOW_KEY}). The
 * texts are composed, never copied, so the brief and the flow each live once.
 *
 * <p><b>Rolled forward on boot.</b> A stored part whose text equals one in {@link #PREVIOUS} was
 * never edited and is rewritten to the shipped text; an edited one stays.
 */
public final class DevelopmentGuideDefaults {

  private DevelopmentGuideDefaults() {}

  public static final String WORKSTATION = "workstation";
  public static final String AGENT = "agent";
  public static final String DESK = "desk";
  public static final String REFINEMENT = "refinement";

  /** The variants, in the order {@code qits help guide --list} shows them. */
  public static final List<String> VARIANTS = List.of(WORKSTATION, AGENT, DESK, REFINEMENT);

  /** The key prefix of every guide row. */
  public static final String KEY_PREFIX = "guide.";

  /** The shared development flow, which every in-platform variant ends with. */
  public static final String FLOW_KEY = KEY_PREFIX + "flow";

  /** Every stored part: one per variant, then the flow. */
  public static final List<String> KEYS =
      List.of(key(WORKSTATION), key(AGENT), key(DESK), key(REFINEMENT), FLOW_KEY);

  /** The surface whose system prompt is a holder's brief. A workstation has none. */
  public static final Map<String, String> HOLDER_SURFACE =
      Map.of(
          AGENT, AgentSurfaceDefaults.TICKET_DISPATCH,
          DESK, AgentSurfaceDefaults.PROJECT_DELIVERY,
          REFINEMENT, AgentSurfaceDefaults.PROJECT_WORK);

  /** One line per variant, for the listing. */
  public static final Map<String, String> DESCRIPTIONS =
      Map.of(
          WORKSTATION,
          "A person on a workstation: sign-in, Git, registries, builds and releases.",
          AGENT,
          "A workspace agent: its brief and the development flow.",
          DESK,
          "The delivery desk: its brief and the development flow.",
          REFINEMENT,
          "A refinement room: its brief and the development flow.");

  public static String key(String variant) {
    return KEY_PREFIX + variant;
  }

  public static boolean isGuideKey(String key) {
    return key != null && key.startsWith(KEY_PREFIX);
  }

  /** The variant named, trimmed and lower-cased, or empty when there is no such variant. */
  public static Optional<String> variant(String name) {
    if (name == null) {
      return Optional.empty();
    }
    String wanted = name.trim().toLowerCase(java.util.Locale.ROOT);
    return VARIANTS.contains(wanted) ? Optional.of(wanted) : Optional.empty();
  }

  /** The shipped text of a guide key, or empty for a key that is not one. */
  public static Optional<String> shippedText(String key) {
    return Optional.ofNullable(SHIPPED.get(key));
  }

  /** A guide key as a surface row: its text in the system prompt, nothing else set. */
  public static AgentSurfaceConfigurationDto asSurface(String key) {
    return new AgentSurfaceConfigurationDto(
        key,
        AgentHarness.CLAUDE,
        "",
        "",
        false,
        AgentPermissionMode.SKIP_PERMISSIONS,
        false,
        SHIPPED.getOrDefault(key, ""),
        "",
        List.of(),
        List.of(),
        true);
  }

  // ---------------------------------------------------------------------------------------------
  // The texts
  // ---------------------------------------------------------------------------------------------

  /** A person on a workstation. */
  public static final String WORKSTATION_GUIDE =
      """
      # Development guide: workstation

      How to develop on the qits platform from your own machine. Inside the platform, run
      `qits help guide` there: agents get their own variant.

      ## Sign in

      1. `qits login` signs you in through the browser. The session is stored in
         `~/.config/qits/t.json`.
      2. Keep `qits session-daemon` running (the CLI's README has a systemd user unit). It keeps
         the session fresh, and the registry logins below.
      3. `qits git-login` lets Git push to the platform's git host. Git gets its token from
         `qits git-credential`; do not store one by hand.

      ## Registries for npm and Maven

      `qits registry-login --configure` writes a login for the platform's registries into blocks of
      `~/.npmrc` and `~/.m2/settings.xml` that the CLI owns. The rest of each file stays as it is.

      - Run it with `--dry-run` first: it prints the changes as a redacted diff and writes nothing.
      - `--remove-stale` also drops old `*.dev.localhost` registries and duplicate entries.
      - From then on `qits session-daemon` writes each new token into the blocks.
      - Never write a registry token by hand.

      ## Build

      - Maven resolves through the mirror `qits-maven-host` for the repository `qits-maven`
        (`https://registry.qits.<domain>/artifacts/maven/maven`). With the blocks in place,
        `./mvnw verify` resolves what CI resolves.
      - npm reads its registries from `~/.npmrc`: `npm ci` needs nothing else.
      - Run the tests your change affects, not the whole suite: the release request runs it.
      - Quarkus tests: pass `-Dquarkus.http.test-port=0`, so a test does not take a fixed port.

      ## Ask the delivery desk

      Each project has a delivery desk, an agent that keeps its work moving. To ask it for
      something (an approval, an action your credential cannot do, a decision), comment on the work
      item with a comment that starts with `@desk` and says exactly what you need. This works from a
      workstation too, for example from your own Claude Code session. Then wait for the outcome:
      the request's or the item's final state, not a fold or an intermediate state.

      A person or an admin can also talk to the desk directly:
      `qits projects desk prompt --project <project> "<text>"`, then
      `qits projects desk answer --project <project>` for its answer.

      ## Ship a change

      1. Commit on a branch. Name the work in the subject: `term(<project>-<n>): message`, for
         example `feat(qits-1337): refuse malformed commit subjects`.
      2. Push it to the platform's git host, under `refs/heads/external/`:
         `git push https://githost.qits.<domain>/git/<project>/<repository> HEAD:refs/heads/external/<name>`.
         Never push to `main`, and never to a mirror such as GitHub: deploys come from the
         platform's git host only. A push releases nothing.
      3. Ask for a release: `qits release-request create --project <project> --repository
         <repository> --branch external/<name> --summary '<what this release is>'`. When a request
         is open for the repository, `create` joins it.
      4. Follow it: `qits release-request list --project <project>`. For a red build:
         `qits ci runs --project <project> --repository <repository> --release-request <id>`, then
         `qits ci report show <run id> --project <project> --repository <repository>`.
      5. A request is done at FINALIZED: the tag is merged into `main`, and a service is deployed.
         Fix a REJECTED or CONFLICTED request with a new push to its branch; do not open a second.
      6. Check the change on the running platform before you call it done.

      Name everything by its full id: `qits-1178`, `qits-workspaces-service-rr-165`.\
      """;

  /** A workspace agent. */
  public static final String AGENT_GUIDE =
      """
      # Development guide: workspace agent

      You are an agent in a workspace container, working one epic or ticket. Your session's brief
      comes first, then how work moves from your branch to the running platform.\
      """;

  /** The desk part as qits-1679 shipped it, before qits-1689 added the release-request triggers. */
  public static final String QITS_1679_DESK_GUIDE =
      """
      # Development guide: delivery desk

      You are the project's delivery desk. Your brief comes first, then the development flow that
      the agents you coordinate follow.

      Agents and people ask you for things with a comment that starts with `@desk`; such a comment
      prompts you. When you hold a decision until someone answers, ask them for the answer as an
      `@desk` comment on the item's thread, and declare a `qits wait` with a timeout for it, so the
      hold cannot go silent.\
      """;

  /** The delivery desk. */
  public static final String DESK_GUIDE =
      """
      # Development guide: delivery desk

      You are the project's delivery desk. Your brief comes first, then the development flow that
      the agents you coordinate follow.

      Agents and people ask you for things with a comment that starts with `@desk`; such a comment
      prompts you. When you hold a decision until someone answers, ask them for the answer as an
      `@desk` comment on the item's thread, and declare a `qits wait` with a timeout for it, so the
      hold cannot go silent.

      You are also told of release requests nobody else watches. A request that went REJECTED,
      CONFLICTED or FAILED reaches you only when no active agent was told of it. A request that
      stalls (RELEASED but not FINALIZED, or PENDING on automations or CI) reaches you once per
      stall. For each: retry a run that failed through the platform's fault (`qits ci retry`), or
      regenerate what an automation owes; fix it yourself or hand it to the agent that owns the
      work; withdraw a dependency bump of a repository that is retired. When it is unclear, ask the
      owner on the item's thread.

      When an agent leaves and fewer items run than `max_running`, you are told a slot opened:
      dispatch the next item as your brief says.\
      """;

  /**
   * The refinement part as qits-1679 first shipped it, before the guide said where the refine
   * instruction comes from.
   */
  public static final String QITS_1679_REFINEMENT_GUIDE =
      """
      # Development guide: refinement room

      You are in a refinement room for one epic or ticket. You refine it: you do not implement it.
      The implement phase starts from what you write, so your plan must fit the development flow
      below.\
      """;

  /**
   * A refinement room. Its holder surface ({@code project.work}) carries no brief: the refine
   * instruction is composed per dispatch, so the part promises none here.
   */
  public static final String REFINEMENT_GUIDE =
      """
      # Development guide: refinement room

      You are in a refinement room for one epic or ticket. You refine it: you do not implement it.
      Your dispatched turn carries the refine instruction; the development flow follows. The
      implement phase starts from what you write, so your plan must fit that flow.\
      """;

  /**
   * The development flow: the wrapper's {@code docs/development-flow.md} as it stood when it moved
   * here (qits-1679), with its paths named as the wrapper's.
   */
  public static final String DEVELOPMENT_FLOW =
      """
      # Development flow

      How work moves through the platform today, from a branch to something running.

      ## A branch per slug

      Work happens on a branch named for the thing you were given: `ticket/<slug>`,
      `epic/<slug>` or `task/<slug>`. The same branch name is taken in the wrapper
      and in every submodule you touch — one name across the whole stack, as the
      wrapper's `README.md` sets out under "How code is iterated" and as its
      `WORKSPACE.md` opens by restating. Commit each change in the
      repository it belongs to: a change to a service is a commit in that service's
      repository, and the wrapper only ever carries wrapper content.

      ## A commit is a push

      The workspace daemon (`OriginSync` in qits-workspace-daemon) pushes every agent
      branch that has commits the git host lacks — the wrapper branch and any branch
      made in a submodule — with the agent's own credential, within seconds of the
      commit: it polls and is nudged at every harness hook. Nobody runs `git push`.

      - A local commit on your ticket, epic or task branch counts as published:
        review the diff before `git commit`, not before a push.
      - Fix a mistake with a new commit. Never amend, rebase or reset a commit
        that is already pushed: the daemon never force-pushes, so a rewritten
        branch diverges from origin and quietly stops syncing.
      - A push you did not make is the daemon doing its job, not a subagent or
        another actor; `git log origin/<branch>` shows what landed.
      - A commit on a detached HEAD is never pushed: each submodule worktree
        starts detached at `origin/main`, so a submodule commit reaches the host
        only once it sits on your branch (`git switch -c <your branch>` first).
        The default branch is never pushed either.

      ## Name everything by its full id

      Whenever you name something the platform tracks, write its full qualified id,
      in everything a person or another agent reads: reports, comments, commit
      messages, tickets and handoffs.

      - A work item is `<project>-<n>`, for example `qits-1178`. Never `1178`, `#1178`
        or "the ticket".
      - A release request is `<repository>-rr-<n>`, for example
        `qits-workspaces-service-rr-165`. Never `rr-165`: the number counts per
        repository. `create` prints the qualified id first, and `-o json` carries it
        as `qualifiedId`.
      - Anything known only by a uuid gets the full uuid, or the 8-character prefix
        together with what it is and where: "qits-ci run `9677c59a`", not
        `9677c59a`.

      An abbreviation that is clear to you is ambiguous to the next reader, who has
      no way to tell which repository or project you meant.

      ## Commit subjects name the work

      Every commit subject names the ticket, epic or task it belongs to, by
      qualified id: `term(<project>-<n>): message`, for example
      `feat(qits-1337): refuse malformed commit subjects`. The term is free (feat,
      fix, docs, chore...). The id is the one the dispatch prompt names, and the one
      `qits work` shows. qits-githost refuses a push with a malformed subject, but
      only on a repository whose default branch carries
      `.config/qits/commit-subjects.yml` with `enforce: true`; most repositories do
      not yet. Merge commits and pushes by platform automation are exempt: qits:system
      service clients (release folds, workspace integrations) and qits:ci-run
      (maintenance bumps). When the guard refuses a commit you cannot rewrite,
      `git push -o qits.subject-bypass="<why>"` is the break-glass, and every use is
      recorded and readable at qits-githost
      `GET /githost/api/repositories/{id}/commit-subject-bypasses`.
      `GET /projects/api/repositories/{id}/commit-subjects` in qits-projects reports
      which recent commits on a repository comply.

      ## A release request per repository, and it is the only way to ship

      Nothing reaches the running platform because you pushed it. For each repository
      you changed, ask for its branch to be released:
      `qits release-request create --project <project> --repository <repository>
      --branch <your branch> --summary '<what this release is>'`.
      `qits release-request list` shows what is open for a repository, and
      `qits release-request join --request <id> --branch <your branch>` adds your
      branch to a request that is already open. If a request is open, join it — do not
      wait for it to finish and do not open a second one beside it. The wrapper's
      `WORKSPACE.md` carries the same API as a plain HTTP call, with the curl body, if
      you ever need it without the CLI.

      A request's state, verbatim from `qits release-request --help`: PENDING (waiting
      for its builds), READY, RELEASED (the tag is cut, waiting on its remaining
      gates), FINALIZED (the tag is merged into main, done), REJECTED (a gating build
      was red), FAILED (the release itself failed), CONFLICTED (the branches do not
      merge), WITHDRAWN, and OBSOLETE (a later request for the repository superseded
      this one before it finished).

      A REJECTED or CONFLICTED request comes back by itself when one of its source
      branches gets a new push. Fix the branch and push; do not open a second request.
      When a build was red through the platform's fault rather than the code's — a
      flaked container, a registry that was down — retry that run with
      `qits ci retry <run id>`, and find the runs with
      `qits ci runs --release-request <id>`. `withdraw` is only for a request that
      must not ship, because the change is wrong or nobody wants it any more:
      WITHDRAWN is final.

      ## Waiting for a request or a run: declare it and end your turn

      A release takes minutes to hours. Do not poll for it, in a loop or by hand, and
      do not sit idle at the prompt: an agent that ends its turn with nothing declared
      reads as blocked on a person. Declare what you wait on and end your turn:

          qits wait release-request <request id>   # any final state: FINALIZED, REJECTED, FAILED, ...
          qits wait ci-run <run id>
          qits wait work <work id> --status VERIFIED --timeout 2h

      The command returns at once. While the wait is open, your work item reads
      "waiting on" its label, not blocked. When the request, run or item reaches what
      you named, or the timeout passes, the platform gives you a new turn that says
      which. Then carry on: a REJECTED request means fix and push, then wait again.
      `qits wait list` shows your open waits and `qits wait cancel <id>` ends one.

      ## Ask the delivery desk

      To ask the project's delivery desk for something (an approval, an action your credential
      cannot do, a decision), write a comment on the work item that starts with `@desk` and says
      exactly what you need. An `@desk` comment prompts the desk through its triggers (qits-1660);
      the Desk page lists it, with a link to the comment. Do not block yourself on "a person" for
      what the desk can do, and do not wait for its reply on some generic event.

      After asking, wait for the answer with `qits wait`: on the comment thread, or on the outcome
      itself, the request's or the item's state. Prefer final states (FINALIZED, REJECTED, FAILED,
      VERIFIED). Never key a wait to a fold's sha or to an intermediate state a refold can skip:
      waits keyed to a fold missed the release of qits-projects-service-rr-368.

      ## Open them early; do not batch

      Open each repository's release request as soon as that repository's part is
      ready. The platform is resource-restricted, and a request you hold back so it
      can go out together with others lengthens every cycle behind it — yours and
      everybody else's. Ship the piece that is done.

      ## The gate

      A release request folds `main` with its source branches and builds that fold.
      The repository's `.config/qits/release.yml` returns the verdict, and a green
      verdict is the whole quality gate: every step of that pipeline gates and no step
      may opt out (the wrapper's `README.md`, "Branching model"). There is nothing else
      to satisfy and nothing to bypass.

      That gate runs the whole suite, so do not run it locally first. Before you push,
      run only the tests you know your change affects — the new ones and the existing
      classes covering the code you touched — on a best-effort basis, and leave the rest
      to the gate. A full local run duplicates work the release request is about to do,
      on a platform where that capacity is shared.

      For a deployable service the primary gate is a successful deployment. Such a
      repository is FINALIZED into `main` only once the deployer reports that version
      live; a repository that declares no `.config/qits/deployments.yml` — a library,
      an SPA, a docs repo, the wrapper — is finalized at the release itself, because
      nothing will ever deploy it. So `main` moves last, and merged is not released: a
      branch that merged proves nothing until its version is live.

      ## Libraries pull their consumers along

      When a library releases, you do not go around updating the things that depend on
      it. qits-maintenance notices the new version, commits the bump onto a
      `maintenance/*` branch in each consumer, and that branch goes through a release
      request like anything else. Forcing this by hand is for work that genuinely
      cannot wait. Never move a pin or a submodule gitlink yourself — the bump owns
      it, and a hand-written pin is overwritten by the next one.

      ## Verify each deliverable on the running platform

      For every deliverable, check it on the live platform, as far as is sensibly
      doable without causing data loss or disturbing somebody else's work. Call the
      endpoint, look at the page, read the event, query the logs. A passing test suite
      is not the claim being made: the claim is that the behaviour is there in the
      environment, and only the environment can answer it.

      ## Transition when the phase is done

      When a phase is finished, transition the ticket or epic with the transition
      tool. A transition normally hands you the next phase's instruction in the same
      session, so it is not bookkeeping you do afterwards — it is how you are given
      your next task. Transition when you are done, and read what comes back.

      A phase that does not finish has two other words, and neither of them is a
      status you fake. If the phase cannot go on — waiting on somebody, on a
      decision, or on an API that is shut to you — **block it** with `work_block`,
      for a ticket, an epic or a campaign alike, and say what is in the way: the
      reason is required, it lands on the thread, and while the flag is set nothing
      dispatches an agent onto that phase again. It is temporary and scoped to the
      phase it blocks, so the next transition clears it. Moving a status back corrects
      a claim that turned out wrong; it is never how a phase reports that it failed.
      A verification that finds a defect it can fix is not stuck either: fix it on
      the same branch, release it and verify again, with the item left at
      VERIFYING. Block only for what you cannot resolve yourself — a decision only
      a person can make, an API your credential cannot use, or someone else's
      unreleased work. If the work is not stuck but has been decided against,
      **drop the ticket**: `DROPPED` is reachable from
      every status that is not already closed, and it is what stops abandoned work
      from sitting at `REFINED` looking like work that is ready to be picked up. A
      dropped ticket reopens to `REPORTED`, because changing your mind about
      abandoned work means asking again what it is for.

      ## The workspace is resolved last

      Only once every component you touched has been released, and the ticket or epic
      has been transitioned to VERIFIED, do you trigger the release or integration of
      the workspace. There is no `qits workspace` verb, and for the workspace branch
      the mechanism is the wrapper repository's own release request: a release deletes
      its source branches, and a workspace branch that is gone is a workspace that is
      resolved. That is the signal the work is closed.

      This is emphatically **not** the end of the implement phase. A ticket's implement
      instruction forbids integrating the workspace, because the verification that
      follows runs in this same workspace and needs it alive. For an epic, "integrate
      the workspace" means at the end of the whole epic, not at the end of a task
      inside it. When in doubt, leave the workspace standing: somebody can always
      resolve it later, and nobody can bring it back.\
      """;

  /** What each guide key ships with. */
  private static final Map<String, String> SHIPPED =
      Map.of(
          key(WORKSTATION), WORKSTATION_GUIDE,
          key(AGENT), AGENT_GUIDE,
          key(DESK), DESK_GUIDE,
          key(REFINEMENT), REFINEMENT_GUIDE,
          FLOW_KEY, DEVELOPMENT_FLOW);

  /**
   * Texts a key shipped with before its current one, oldest first. A stored row that holds one of
   * them byte for byte was never edited, and the boot rolls it to the shipped text. Never edit an
   * entry: the rollout would then miss every unedited row.
   */
  public static final Map<String, List<String>> PREVIOUS =
      Map.of(
          key(DESK), List.of(QITS_1679_DESK_GUIDE),
          key(REFINEMENT), List.of(QITS_1679_REFINEMENT_GUIDE));
}
