// Bundle budget gate (ADR-0049 W3). Run it from a web package directory (dashboard/ or perf/report/) as `npm run check-bundle-budget`,
// which calls this file (`node ../scripts/...` from dashboard/, `node ../../scripts/...` from perf/report/); pass the mode after `--`:
//   (no mode)                                         builds with Vite into a temp dir and fails when the gzip size
//                                                     of all dist/**/*.js files is above `budgetGzipBytes` of ./bundle-budget.json
//   --measure                                         builds and prints the sizes; it writes nothing
//   --write                                           builds and writes ./bundle-budget.json from the measured size
// The budget is the first measured size plus 10 %, a rule and not a guess (ADR-0049 decision 2). The gzip size is the one
// that a browser downloads from nginx, which the Dashboard image serves with gzip. No dependency: node:zlib only.
//
// To raise the budget on purpose: run `--write` in the same PR that adds the size, and put the reason and the new
// measured size in an ADR-0049 amendment. Do not raise it only to make the job pass.
//
// perf/report/ is built with `vite build` here, not `npm run build`: its `copy-data.mjs` step writes the result data as
// dist/index.js, which grows with each measurement run and is not code.
import { execFileSync } from "node:child_process";
import { mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

export const HEADROOM = 1.1;

/** Budget for a measured size: the size plus 10 %, rounded up to a whole byte. */
export const budgetFor = (measuredBytes) => Math.ceil(measuredBytes * HEADROOM);

/** Gzip size (level 9) of every `.js` file below `dir`: `{ files: [{ path, bytes }], total }`. */
export function gzipSizes(dir) {
  const files = readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((e) => e.isFile() && e.name.endsWith(".js"))
    .map((e) => {
      const file = join(e.parentPath, e.name);
      return { path: relative(dir, file), bytes: gzipSync(readFileSync(file), { level: 9 }).length };
    })
    .sort((a, b) => a.path.localeCompare(b.path));
  if (files.length === 0) throw new Error(`no .js file below ${dir}`);
  return { files, total: files.reduce((sum, f) => sum + f.bytes, 0) };
}

/** `null` when the size is within the budget, else the failure text. */
export function verdict(total, budget) {
  return total <= budget ? null : `gzip JS is ${total} bytes, ${total - budget} above the budget of ${budget} bytes`;
}

function build() {
  const out = mkdtempSync(join(tmpdir(), "signaldock-budget-"));
  try {
    execFileSync("node_modules/.bin/vite", ["build", "--outDir", out, "--emptyOutDir", "--logLevel", "error"], { stdio: ["ignore", "ignore", "inherit"] });
    return gzipSizes(out);
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
}

function main() {
  const mode = process.argv[2] ?? "check";
  const { files, total } = build();
  for (const f of files) console.log(`  ${f.bytes} bytes gzip  ${f.path}`);
  console.log(`total gzip JS: ${total} bytes`);
  if (mode === "--measure") return;
  if (mode === "--write") {
    writeFileSync("bundle-budget.json", `${JSON.stringify({ measuredGzipBytes: total, budgetGzipBytes: budgetFor(total) }, null, 2)}\n`);
    console.log(`wrote bundle-budget.json: budget ${budgetFor(total)} bytes`);
    return;
  }
  const { measuredGzipBytes, budgetGzipBytes } = JSON.parse(readFileSync("bundle-budget.json", "utf8"));
  if (budgetGzipBytes !== budgetFor(measuredGzipBytes)) {
    console.error(`FAIL: bundle-budget.json: budgetGzipBytes must be the measured size plus 10 % (${budgetFor(measuredGzipBytes)})`);
    process.exit(1);
  }
  const failure = verdict(total, budgetGzipBytes);
  if (failure) {
    console.error(`FAIL: ${failure}. See the header of scripts/check-bundle-budget.mjs to raise it on purpose.`);
    process.exit(1);
  }
  console.log(`ok: within the budget of ${budgetGzipBytes} bytes (measured at budget time: ${measuredGzipBytes})`);
}

// Compare real paths: a link or a `#` in the checkout path must not turn the CLI into a silent no-op that exits 0.
if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) main();
