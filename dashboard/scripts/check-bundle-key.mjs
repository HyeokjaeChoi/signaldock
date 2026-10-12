// Q129: the production bundle must not contain the query key. Builds with a
// unique key in the environment (the same place the dev proxy and the build
// would read it) and fails if any output file contains the value or the
// SIGNALDOCK_QUERY_KEY name.
import { execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { mkdtempSync, readdirSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const key = `qk-${randomBytes(12).toString("hex")}`;
const out = mkdtempSync(join(tmpdir(), "signaldock-bundle-"));
try {
  execFileSync("node_modules/.bin/vite", ["build", "--outDir", out, "--emptyOutDir"], {
    env: { ...process.env, SIGNALDOCK_QUERY_KEY: key, VITE_SIGNALDOCK_QUERY_KEY: key },
    stdio: "ignore",
  });
  const files = readdirSync(out, { recursive: true, withFileTypes: true }).filter((e) => e.isFile());
  if (files.length === 0) throw new Error("build produced no files");
  const leaks = files.filter((e) => {
    const text = readFileSync(join(e.parentPath, e.name), "utf8");
    return text.includes(key) || text.includes("SIGNALDOCK_QUERY_KEY");
  });
  if (leaks.length > 0) {
    console.error(`FAIL: query key found in bundle: ${leaks.map((e) => e.name).join(", ")}`);
    process.exit(1);
  }
  console.log(`ok: query key not in ${files.length} bundle files`);
} finally {
  rmSync(out, { recursive: true, force: true });
}
