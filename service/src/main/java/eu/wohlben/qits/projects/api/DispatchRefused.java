package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.error.DomainException;

/**
 * <b>A dispatch refused before anything happened</b> (qits-417): every refusal {@link
 * EntityDispatch#precheck} makes — a feature or a task, a block, no phase left (or not the phase asked for),
 * no workspaces context, no wrapper — and nothing else. No bit was written, no workspace was asked
 * for, no comment was posted.
 *
 * <p>A subtype rather than a message, so the campaign executor can tell "refused, nothing happened —
 * release the claim and say why" from "the call out failed or something after it did — the outcome
 * is unknown, keep the claim" without parsing a sentence. A plain {@link DomainException} thrown by a
 * dispatch — the port's 502/503 included — is the second case. Over REST it answers exactly as the
 * {@link DomainException} it is, with its own status and message.
 */
public class DispatchRefused extends DomainException {

  public DispatchRefused(int statusCode, String message) {
    super(statusCode, message);
  }
}
