import type { T } from "./i18n";
import type { QueryError } from "./polling";

const KNOWN_CODES = [
  "CONTRACT_HEADER_MISSING", "CONTRACT_VERSION_UNSUPPORTED", "QUERY_KEY_INVALID", "INVALID_PARAMETER",
  "INVALID_CURSOR", "PERIOD_TOO_LONG", "EVENT_NOT_FOUND", "TEMPORARILY_UNAVAILABLE",
] as const;
type KnownCode = (typeof KNOWN_CODES)[number];
const isKnown = (code: string | undefined): code is KnownCode => KNOWN_CODES.includes(code as KnownCode);

/**
 * Text of a failed query in the selected language. A known API error code gets a translated sentence and keeps the
 * code in its original form. Other server messages and `HTTP <status>` stay as the server sent them.
 */
export function errorText(t: T, error: Error | QueryError): string {
  const e = error as Partial<QueryError>;
  if (e.i18nKey) return t(e.i18nKey);
  if (isKnown(e.code)) return `${t(`errors.code.${e.code}`)} (${e.code})`;
  return error.message;
}
