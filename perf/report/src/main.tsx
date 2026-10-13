import { createRoot } from "react-dom/client";
import { App } from "./App";
import { I18nProvider } from "./i18n";
import { initTheme } from "../../../web-shared/theme";
import "../../../web-shared/theme/tokens.css";
import "../../../web-shared/theme/base.css";
import "./styles.css";

initTheme();

createRoot(document.getElementById("root")!).render(
  <I18nProvider>
    <App />
  </I18nProvider>,
);
