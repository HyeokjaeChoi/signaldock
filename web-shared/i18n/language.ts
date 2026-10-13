import { LANGUAGES } from "./messages";
import type { Language } from "./messages";

/** Q136: URL `?lang=` first, then the saved choice, then `navigator.language` (`ko...` is Korean, anything else English). */
export const STORAGE_KEY = "signaldock.lang";

const isLanguage = (v: string | null | undefined): v is Language => LANGUAGES.includes(v as Language);

export interface LanguageSources {
  search?: string;
  saved?: string | null;
  navigatorLanguage?: string;
}

export function resolveLanguage({ search = "", saved, navigatorLanguage = "" }: LanguageSources): Language {
  const fromUrl = new URLSearchParams(search).get("lang");
  if (isLanguage(fromUrl)) return fromUrl;
  if (isLanguage(saved)) return saved;
  return navigatorLanguage.toLowerCase().startsWith("ko") ? "ko" : "en";
}

// Storage can throw (private mode, blocked site data). The language must still work without it.
function readSaved(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

export function save(language: Language): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, language);
  } catch {
    // The choice then lasts for this page load only.
  }
}

export function resolveFromBrowser(): Language {
  return resolveLanguage({ search: window.location.search, saved: readSaved(), navigatorLanguage: window.navigator.language });
}

/** If the URL carries `?lang=`, keep it in step with the choice, so a reload does not undo the choice. */
export function syncUrl(language: Language): void {
  try {
    const url = new URL(window.location.href);
    if (!url.searchParams.has("lang")) return;
    url.searchParams.set("lang", language);
    window.history.replaceState(window.history.state, "", url);
  } catch {
    // Not critical.
  }
}
