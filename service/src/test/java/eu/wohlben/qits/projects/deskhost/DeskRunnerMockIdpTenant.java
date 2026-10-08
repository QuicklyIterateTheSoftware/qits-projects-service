package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.security.MockIdpTenant;

/**
 * {@link MockIdpTenant} under a class of its own, for the suites that dial runners and desks with
 * bearers (qits-767). Quarkus keeps one application per resource set, so sharing {@link
 * MockIdpTenant} itself put these suites in {@code BearerJwksTest}'s application — and its first
 * method asserts that nothing has fetched the idp's keys since boot, which a runner's bearer already
 * had. A distinct class is a distinct application: one more boot, and {@code BearerJwksTest} boots
 * clean again.
 */
public class DeskRunnerMockIdpTenant extends MockIdpTenant {}
