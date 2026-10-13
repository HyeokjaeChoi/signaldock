import { useCallback, useState } from "react";
import { changeTheme, currentTheme } from "./theme";
import type { Theme } from "./theme";

/** `{ theme, setTheme }` for the theme switch. The attribute on `<html>` is the single source of truth. */
export function useTheme(): { theme: Theme; setTheme: (theme: Theme) => void } {
  const [theme, set] = useState<Theme>(currentTheme);
  const setTheme = useCallback((next: Theme) => {
    changeTheme(next);
    set(currentTheme());
  }, []);
  return { theme, setTheme };
}
