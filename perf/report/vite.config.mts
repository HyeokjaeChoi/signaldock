import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Static page for perf/results/index.json. It must open from file://, where Chrome blocks module scripts:
// build one IIFE bundle and load it as a classic deferred script.
export default defineConfig({
  base: "./",
  plugins: [
    react(),
    {
      name: "classic-script",
      enforce: "post",
      transformIndexHtml: (html) => html.replace(/<script type="module" crossorigin/g, "<script defer"),
    },
  ],
  build: { rollupOptions: { output: { format: "iife" } }, modulePreload: false, chunkSizeWarningLimit: 800 },
});
