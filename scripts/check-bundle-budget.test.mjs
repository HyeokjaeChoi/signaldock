import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { budgetFor, gzipSizes, verdict } from "./check-bundle-budget.mjs";

test("the budget is the measured size plus 10 %, rounded up", () => {
  assert.equal(budgetFor(1000), 1100);
  assert.equal(budgetFor(1001), 1102);
});

test("the verdict fails above the budget and passes at it", () => {
  assert.equal(verdict(1100, 1100), null);
  assert.match(verdict(1101, 1100), /1 above the budget of 1100/);
});

test("gzipSizes sums only .js files, below sub directories as well", () => {
  const dir = mkdtempSync(join(tmpdir(), "budget-test-"));
  try {
    mkdirSync(join(dir, "assets"));
    writeFileSync(join(dir, "assets", "a.js"), "x".repeat(5000));
    writeFileSync(join(dir, "b.js"), "y".repeat(5000));
    writeFileSync(join(dir, "c.css"), "z".repeat(5000));
    const { files, total } = gzipSizes(dir);
    assert.deepEqual(files.map((f) => f.path), ["assets/a.js", "b.js"]);
    assert.equal(total, files[0].bytes + files[1].bytes);
    assert.ok(total < 200, "5000 repeated characters compress to a few bytes");
    assert.throws(() => gzipSizes(join(dir, "assets", "..", "assets", "nothing-here")), /ENOENT/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
