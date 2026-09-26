# `components/` — one directory per component

Every part of this project lives here, grouped by the component it belongs to and never by the role
it plays:

    components/<component>/<repository>

A **component** is any cohesive unit of the product. It does not need a deployable: a component may
be one service, or a service with its frontend and its daemon beside it, or two libraries and
nothing else.

The component directory name says what the thing *is*, never how it is built — `payments`, not
`payments-postgres`. The implementation may change; the component does not.

## Names say the role

The repository inside a component carries the role in its name:

    <component>[-<modifier>]-<role>[-<tech>]

| Role suffix | What it is |
|---|---|
| `-service` | A deployable component — the things that run in production. |
| `-daemon` | A long-running background agent nobody calls. |
| `-frontend` | A microfrontend a service carries and serves — no deployment of its own. |
| `-app` | A standalone web application — its own server, its own image, its own deployment. |
| `-cli` | A command-line entry point. |
| `-oci` | A build definition consumed through its published OCI image. |
| `-javalib` / `-jslib` | Shared technical code consumed by the components, never deployed on its own. |

So `components/payments/payments-service` sits next to `components/payments/payments-frontend`, and
the two things you change together are two lines apart instead of two directories apart.

The **name** is what says the kind here, and qits reads it: creating a repository called
`payments-daemon` needs no archetype stated, and renaming one to carry a role suffix restamps the
kind. A name with no role suffix is allowed — qits simply has nothing to derive from it, so state
the kind when you create it.

Add the tech suffix only where the role alone is ambiguous, which in practice is the library pair.

**The modifier slot is optional and currently has no instance, which is deliberate.** It held exactly
one word — `platform`, on a service that ran once for the whole estate rather than once per
environment — and that distinction was deleted along with the plane it named, so seventeen
repositories were renamed to drop it (`qits-idp-platform-service` became `qits-idp-service`). The
slot stays in the grammar because a project may find a genuine modifier of its own; it is not a
place to re-encode where something is deployed. Where a thing runs is deployment configuration, and
a name that carries it has to be changed whenever that answer does.

## The landing convention

A project's landing page lives in the component `<project>-landing`, and any role may serve it —
this is not a frontend feature. `<project>-landing-app` and `<project>-landing-service` claim the
door identically: a landing page that wants an SSR runtime reaches for an `-app`, one that is a view
over the project's own data reaches for a `-service` with a Quinoa client, and both take the door on
the same terms.

The door itself is a label, not the name. What puts a deployable on the project's own address,
`<project>.<domain>`, is publishing `host: landing` in its `.config/qits/deployments.yml` — nothing
in the edge or the deployer asks what kind of repository published it or what it is called.
**The label is what the platform reads; the component name is only the convention.** Somebody who
names the component something else and publishes `landing` still takes the door, and somebody who
names it `<project>-landing` and forgets the label does not.

## Start inline

Put the code directly under the component (`components/payments/payments-service/`). Nothing has to
become its own repository until it earns it — that decision is meant to be deferred, not made on day
one. When a directory does earn one, extracting it and re-attaching it as a submodule at the same
path changes nothing about where it sits.
