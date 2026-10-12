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
    server: {
      host: "127.0.0.1",
      proxy: {
        "/v1": {
          target: env.SIGNALDOCK_SERVER_URL ?? `http://127.0.0.1:${env.PORT ?? "8080"}`,
          headers: queryKey ? { Authorization: `Bearer ${queryKey}` } : {},
        },
      },
    },
    test: { environment: "jsdom", include: ["src/**/*.test.{ts,tsx}"] },
  };
});
