// Copies perf/results/index.json (written by perf/summarize.py --index) next to the built page, and as index.js
// (window.SIGNALDOCK_RESULTS) so the page works from file:// where fetch is blocked.
import { copyFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";

const src = new URL("../../results/index.json", import.meta.url);
if (!existsSync(src)) {
  console.warn("perf/results/index.json not found: run perf/summarize.py --index first. The page will say so.");
  process.exit(0);
}
copyFileSync(src, new URL("../dist/index.json", import.meta.url));
writeFileSync(new URL("../dist/index.js", import.meta.url), `window.SIGNALDOCK_RESULTS=${readFileSync(src, "utf8").trim()};\n`);
console.log("copied perf/results/index.json into dist/");
