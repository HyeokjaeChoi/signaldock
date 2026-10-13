import { createI18n } from "../../../../web-shared/i18n";
import { en } from "./en";
import { ko } from "./ko";

export const dictionaries = { en, ko };
export const { I18nProvider, useTranslation } = createI18n(dictionaries);
export type T = ReturnType<typeof useTranslation>["t"];
