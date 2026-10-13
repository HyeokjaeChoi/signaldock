import { createContext, createElement, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { resolveFromBrowser, save, syncUrl } from "./language";
import { LANGUAGES } from "./messages";
import type { Language, MessageKey, Messages } from "./messages";
import { translate } from "./translate";
import type { TOptions } from "./translate";

export interface I18n<M extends Messages> {
  t: (key: MessageKey<M>, options?: TOptions) => string;
  i18n: { language: Language; changeLanguage: (language: Language) => void };
}

/**
 * Binds one page's dictionaries. `useTranslation()` returns the main fields of react-i18next's hook
 * (`t`, `i18n.language`, `i18n.changeLanguage`), so a later switch to i18next needs no change in components.
 * No JSX here: two pages compile this file, and each resolves `react` from its own `node_modules`.
 */
export function createI18n<M extends Messages>(dictionaries: Record<Language, Messages> & { en: M }) {
  const Context = createContext<I18n<M> | null>(null);

  function I18nProvider({ initialLanguage, children }: { initialLanguage?: Language; children: ReactNode }) {
    const [language, setLanguage] = useState<Language>(() => initialLanguage ?? resolveFromBrowser());
    useEffect(() => {
      document.documentElement.lang = language;
    }, [language]);
    const changeLanguage = useCallback((next: Language) => {
      if (!LANGUAGES.includes(next)) return;
      setLanguage(next);
      save(next);
      syncUrl(next);
    }, []);
    const value = useMemo<I18n<M>>(
      () => ({
        t: (key, options) => translate(dictionaries[language], language, key, options),
        i18n: { language, changeLanguage },
      }),
      [language, changeLanguage],
    );
    return createElement(Context.Provider, { value }, children);
  }

  function useTranslation(): I18n<M> {
    const value = useContext(Context);
    if (!value) throw new Error("useTranslation needs an I18nProvider");
    return value;
  }

  return { I18nProvider, useTranslation };
}
