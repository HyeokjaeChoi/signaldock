import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Static page for perf/results/index.json. It must open from file://, where Chrome blocks module scripts:
// build one IIFE bundle and load it as a classic deferred script.
export default defineConfig({
  base: "./",
  // web-shared/ (Q137) has no node_modules: its import of react resolves to this page's copy.
  resolve: { dedupe: ["react", "react-dom"] },
  server: { fs: { allow: ["../.."] } },
  plugins: [
    react(),
    {
      name: "classic-script",
      enforce: "post",
      // `crossorigin` on the stylesheet link would fail from file:// as well (the page has its CSS in a file since issue 28).
      transformIndexHtml: (html) => html.replace(/<script type="module" crossorigin/g, "<script defer").replace(/(<link rel="stylesheet") crossorigin/g, "$1"),
    },
  ],
  build: { rollupOptions: { output: { format: "iife" } }, modulePreload: false, chunkSizeWarningLimit: 800 },
});
