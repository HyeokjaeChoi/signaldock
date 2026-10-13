/** Calendar periods in the project timezone, converted to instants (defaults 1, 2; ADR-0007 "Period"). */

export type Preset = "today" | "7d" | "30d" | "custom";
export const MAX_DATES = 30;

/** A failed period names a message key (and a count for plurals); the UI shows it in the selected language. */
export type PeriodError = { key: "period.chooseDates" | "period.endBeforeStart" | "period.tooLong"; count?: number };
export type Period = { ok: true; from: string; to: string } | ({ ok: false } & PeriodError);

const parts = (ms: number, timeZone: string) => {
  const f = new Intl.DateTimeFormat("en-CA", {
    timeZone, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit",
  });
  return Object.fromEntries(f.formatToParts(ms).map((p) => [p.type, Number(p.value)])) as Record<string, number>;
};

/** Calendar date `YYYY-MM-DD` of an instant in `timeZone`. */
export function dateIn(ms: number, timeZone: string): string {
  const p = parts(ms, timeZone);
  return `${p.year}-${String(p.month).padStart(2, "0")}-${String(p.day).padStart(2, "0")}`;
}

export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number) as [number, number, number];
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** Instant of 00:00 on `date` in `timeZone`. */
export function midnight(date: string, timeZone: string): number {
  const [y, m, d] = date.split("-").map(Number) as [number, number, number];
  const wall = Date.UTC(y, m - 1, d);
  let guess = wall;
  for (let i = 0; i < 2; i++) {
    const p = parts(guess, timeZone);
    guess += wall - Date.UTC(p.year!, p.month! - 1, p.day!, p.hour!, p.minute!, p.second!);
  }
  return guess;
}

const daysBetween = (a: string, b: string) => Math.round((Date.parse(b) - Date.parse(a)) / 86400000);

export function resolvePeriod(
  preset: Preset, customStart: string, customEnd: string, timeZone: string, nowMs: number,
): Period {
  const today = dateIn(nowMs, timeZone);
  let start = today;
  let end = today;
  if (preset === "7d") start = addDays(today, -6);
  if (preset === "30d") start = addDays(today, -(MAX_DATES - 1));
  if (preset === "custom") {
    if (!customStart || !customEnd) return { ok: false, key: "period.chooseDates" };
    if (customEnd < customStart) return { ok: false, key: "period.endBeforeStart" };
    if (daysBetween(customStart, customEnd) + 1 > MAX_DATES) {
      return { ok: false, key: "period.tooLong", count: MAX_DATES };
    }
    start = customStart;
    end = customEnd;
  }
  return {
    ok: true,
    from: new Date(midnight(start, timeZone)).toISOString(),
    to: new Date(midnight(addDays(end, 1), timeZone)).toISOString(),
  };
}
