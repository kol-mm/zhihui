import { isSessionExpiredError, toUserMessage } from '../api/client';

/**
 * What to tell the visitor about an error no handler caught, or null for nothing.
 *
 * Many click handlers await a request without a catch of their own — publishing a draft, removing an avatar,
 * reporting a user. Without an app-level handler their failures reached only the console: the button did
 * nothing, and a refusal the service had explained in plain Chinese was never shown.
 *
 * Declining a confirmation is not an error: ElMessageBox rejects with the action taken, 'cancel' or 'close'.
 * An ended session is already announced once by the page.
 */
export function messageForUnhandled(error: unknown): string | null {
  if (isDismissal(error)) return null;
  if (isSessionExpiredError(error)) return null;
  return toUserMessage(error);
}

/** The visitor closed a confirmation rather than anything going wrong. */
export function isDismissal(error: unknown): boolean {
  return error === 'cancel' || error === 'close';
}
