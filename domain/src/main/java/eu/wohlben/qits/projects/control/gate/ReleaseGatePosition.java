package eu.wohlben.qits.projects.control.gate;

/**
 * The pipeline slot a gate stands in: after one phase and before the next. A new slot is a new
 * member here.
 */
public enum ReleaseGatePosition {
  /**
   * After the pre-run, before QA (qits-1133): the release-request automations, which have to be
   * fresh before QA is even asked for.
   */
  PRE_RUN_QA("pre-run-qa"),
  /** After QA, before publish. */
  QA_PUBLISH("qa-publish"),
  /** After publish, before deploy. */
  PUBLISH_DEPLOY("publish-deploy"),
  /** After deploy, before the release is finalized. */
  DEPLOY_FINALIZED("deploy-finalized");

  private final String wire;

  ReleaseGatePosition(String wire) {
    this.wire = wire;
  }

  /** The word on the wire: {@code qa-publish}. */
  public String wire() {
    return wire;
  }
}
