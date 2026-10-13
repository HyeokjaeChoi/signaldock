// Message dictionary types (Q135). The shape is a subset of what i18next accepts:
// nested plain objects of strings, dot-separated keys, `_one` / `_other` plural suffixes, `{{name}}` interpolation.

export const LANGUAGES = ["en", "ko"] as const;
export type Language = (typeof LANGUAGES)[number];

/** A dictionary: nested plain objects whose leaves are strings. JSON-serializable. */
export interface Messages {
  [key: string]: string | Messages;
}

/** i18next plural suffixes. `Intl.PluralRules` picks the category. */
export const PLURAL_CATEGORIES = ["zero", "one", "two", "few", "many", "other"] as const;
type PluralSuffix = (typeof PLURAL_CATEGORIES)[number];

type LeafPaths<T> = {
  [K in keyof T & string]: T[K] extends string ? K : T[K] extends Messages ? `${K}.${LeafPaths<T[K]>}` : never;
}[keyof T & string];

type StripPlural<P extends string> = P extends `${infer Base}_${PluralSuffix}` ? Base : P;

/** Keys that `t()` accepts for a dictionary. A plural pair `x_one` / `x_other` is addressed as `x`. */
export type MessageKey<T extends Messages> = StripPlural<LeafPaths<T>>;

/**
 * Compile-time check that two dictionaries have the same keys. Plural suffixes are ignored, because
 * languages differ in their plural categories (Korean has only `_other`).
 * Use: `const check: SameKeys<typeof en, typeof ko> = true;` A missing or extra key makes `tsc` fail.
 */
export type SameKeys<A extends Messages, B extends Messages> = [
  Exclude<MessageKey<A>, MessageKey<B>>,
  Exclude<MessageKey<B>, MessageKey<A>>,
] extends [never, never]
  ? true
  : never;
