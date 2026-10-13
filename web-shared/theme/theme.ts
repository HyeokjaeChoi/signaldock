/** ADR-0016 (Q139, Q140): dark is the default, `light` is a switch. Nothing else (not `prefers-color-scheme`) picks the theme. */
export const THEMES = ["dark", "light"] as const;
export type Theme = (typeof THEMES)[number];
export const DEFAULT_THEME: Theme = "dark";
export const THEME_STORAGE_KEY = "signaldock.theme";

export const isTheme = (v: string | null | undefined): v is Theme => THEMES.includes(v as Theme);

// Storage can throw (private mode, blocked site data). The theme must still work without it.
function readSaved(): string | null {
  try {
    return window.localStorage.getItem(THEME_STORAGE_KEY);
  } catch {
    return null;
  }
}

/** The saved choice, else the default. */
export const resolveTheme = (saved: string | null | undefined = readSaved()): Theme => (isTheme(saved) ? saved : DEFAULT_THEME);

/** The theme on the page right now (the attribute that `tokens.css` reads). */
export const currentTheme = (): Theme => {
  const v = document.documentElement.dataset.theme;
  return isTheme(v) ? v : DEFAULT_THEME;
};

export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme;
  // Browser chrome color follows the page (same values as --bg in tokens.css).
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", theme === "light" ? "#f4f6f9" : "#0d1117");
}

/** Call once before the first render. */
export function initTheme(): void {
  applyTheme(resolveTheme());
}

export function saveTheme(theme: Theme): void {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // The choice then lasts for this page load only.
  }
}

/** Switch and keep the choice. */
export function changeTheme(theme: Theme): void {
  if (!isTheme(theme)) return;
  applyTheme(theme);
  saveTheme(theme);
}
