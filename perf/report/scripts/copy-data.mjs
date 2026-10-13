// Copies perf/results/index.json (written by perf/summarize.py --index) and perf/results/sdk-index.json (written by
// perf/sdk_summarize.py --index) next to the built page, and as index.js (window.SIGNALDOCK_RESULTS and
// window.SIGNALDOCK_SDK_RESULTS) so the page works from file:// where fetch is blocked.
import { copyFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";

const files = [["index.json", "SIGNALDOCK_RESULTS", "perf/summarize.py --index"], ["sdk-index.json", "SIGNALDOCK_SDK_RESULTS", "perf/sdk_summarize.py --index"]];
let script = "";
for (const [name, global, writer] of files) {
  const src = new URL(`../../results/${name}`, import.meta.url);
  if (!existsSync(src)) {
    console.warn(`perf/results/${name} not found: run ${writer} first. The page will say so.`);
    continue;
  }
  copyFileSync(src, new URL(`../dist/${name}`, import.meta.url));
  script += `window.${global}=${readFileSync(src, "utf8").trim()};\n`;
  console.log(`copied perf/results/${name} into dist/`);
}
writeFileSync(new URL("../dist/index.js", import.meta.url), script);
