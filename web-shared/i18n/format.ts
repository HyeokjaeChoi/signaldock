import type { Language } from "./messages";

/** Q135: numbers and dates through `Intl`. Times are shown in the project timezone (Q78, Q79). */
const LOCALE: Record<Language, string> = { en: "en-GB", ko: "ko-KR" };

export const formatNumber = (value: number, language: Language, options?: Intl.NumberFormatOptions): string =>
  new Intl.NumberFormat(LOCALE[language], options).format(value);

/** Date and time with milliseconds, 24-hour clock, in `timeZone`. */
export const formatDateTime = (instant: number | string | Date, timeZone: string, language: Language): string =>
  new Intl.DateTimeFormat(LOCALE[language], {
    timeZone, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", fractionalSecondDigits: 3,
  }).format(new Date(instant));

/** Time of day with seconds, in `timeZone`. */
export const formatClock = (instant: number | string | Date, timeZone: string, language: Language): string =>
  new Intl.DateTimeFormat(LOCALE[language], { timeZone, hourCycle: "h23", timeStyle: "medium" }).format(new Date(instant));

/** Short label for a chart bucket: hour and minute for hour buckets, month and day otherwise. */
export const formatBucketLabel = (instant: number | string | Date, hourly: boolean, timeZone: string, language: Language): string =>
  new Intl.DateTimeFormat(
    LOCALE[language],
    hourly
      ? { timeZone, hourCycle: "h23", hour: "2-digit", minute: "2-digit" }
      : { timeZone, month: "2-digit", day: "2-digit" },
  ).format(new Date(instant));
