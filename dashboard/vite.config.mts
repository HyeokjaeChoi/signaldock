import react from "@vitejs/plugin-react";
import { defineConfig, loadEnv } from "vite";

// Q129: the browser bundle never holds the query key. The dev proxy adds the
// header on the Node side. The key comes from process.env (or the repo `.env`,
// which sits below process.env) and is never passed to `define`, so it cannot
// reach the bundle.
export default defineConfig(({ mode }) => {
  const env = { ...loadEnv(mode, "..", ""), ...process.env };
  const queryKey = env.SIGNALDOCK_QUERY_KEY;
  return {
    plugins: [react()],
    // web-shared/ (Q137) has no node_modules: its imports of react (and vitest, in its tests) resolve to this page's copy.
    resolve: { dedupe: ["react", "react-dom", "vitest"] },
    server: {
      host: "127.0.0.1",
      fs: { allow: [".."] }, // ../generated and ../web-shared
      proxy: {
        "/v1": {
          target: env.SIGNALDOCK_SERVER_URL ?? `http://127.0.0.1:${env.PORT ?? "8080"}`,
          headers: queryKey ? { Authorization: `Bearer ${queryKey}` } : {},
        },
      },
    },
    // The tests of web-shared/ run here too (Q137): this is the one place that runs them.
    test: { environment: "jsdom", include: ["src/**/*.test.{ts,tsx}", "../web-shared/**/*.test.{ts,tsx}"] },
  };
});
