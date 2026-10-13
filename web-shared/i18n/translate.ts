import { PLURAL_CATEGORIES } from "./messages";
import type { Language, Messages } from "./messages";

export type TOptions = { count?: number } & Record<string, string | number | undefined>;

const lookup = (messages: Messages, key: string): string | undefined => {
  let node: string | Messages | undefined = messages;
  for (const part of key.split(".")) {
    if (typeof node !== "object" || !Object.hasOwn(node, part)) return undefined;
    node = node[part];
  }
  return typeof node === "string" ? node : undefined;
};

/** i18next default syntax: `{{name}}`, with optional spaces inside the braces. A missing value stays as written. */
const interpolate = (text: string, options: TOptions): string =>
  text.replace(/\{\{\s*([\w.]+)\s*\}\}/g, (whole, name: string) => {
    const value = options[name];
    return value === undefined ? whole : String(value);
  });

/**
 * i18next-style `t()`. With `options.count`, the key `x` resolves to `x_<category>` (category from
 * `Intl.PluralRules`), then to `x_other`, then to `x`. An unknown key returns the key, as i18next does.
 */
export function translate(messages: Messages, language: Language, key: string, options: TOptions = {}): string {
  let text: string | undefined;
  if (typeof options.count === "number") {
    const category = new Intl.PluralRules(language).select(options.count);
    text = lookup(messages, `${key}_${category}`) ?? lookup(messages, `${key}_other`);
  }
  text ??= lookup(messages, key);
  return text === undefined ? key : interpolate(text, options);
}

/** Every leaf of a dictionary as `[dotted key, value]`. Used by the format test. */
export function leaves(messages: Messages, prefix = ""): [string, unknown][] {
  return Object.entries(messages).flatMap(([k, v]) =>
    v !== null && typeof v === "object" ? leaves(v as Messages, `${prefix}${k}.`) : [[`${prefix}${k}`, v] as [string, unknown]],
  );
}

export const pluralSuffix = (leafKey: string) => PLURAL_CATEGORIES.find((c) => leafKey.endsWith(`_${c}`));
