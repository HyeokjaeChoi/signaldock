// Run: node --test tracking-plan/generate.test.mjs   (ADR-0021; wired into scripts/verify-contract.sh)
// 1. The generator rejects invalid plans.
// 2. Cross-language check: for every plan event, the Kotlin output and the TypeScript output agree
//    on the event name, the property keys, the value types, and which properties are optional.
//    TypeScript is run (Node strips the types). Kotlin is read as text: its calls need an Android
//    SDK instance, and the demo build compiles the same file (ADR-0021, "Test mechanism").
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";
import { generate, validatePlan } from "./generate.mjs";

const plan = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), "plan.json"), "utf8"));
const files = generate(plan);
const manifest = JSON.parse(files["manifest.json"]);

test("the checked-in outputs are what the plan generates", () => {
  const dir = join(dirname(fileURLToPath(import.meta.url)), "generated");
  for (const [rel, text] of Object.entries(files)) assert.equal(readFileSync(join(dir, rel), "utf8"), text, rel);
});

const withEvent = (patch) => ({ ...plan, events: [{ ...plan.events[0], ...patch }] });
const withProp = (patch) => withEvent({ properties: [{ ...plan.events[0].properties[0], ...patch }] });
const invalid = [
  ["camelCase event name", withEvent({ name: "productViewed" }), /name must be snake_case/],
  ["one-word event name", withEvent({ name: "viewed" }), /at least two words/],
  ["event name with spaces", withEvent({ name: "product viewed" }), /name must be snake_case/],
  ["upper case key", withProp({ key: "Product_Id" }), /key must be snake_case/],
  ["key with padding", withProp({ key: " product_id" }), /key must be snake_case/],
  ["Kotlin keyword key", withProp({ key: "is" }), /Kotlin keyword/],
  ["unknown type", withProp({ type: "array" }), /type must be one of/],
  ["required not boolean", withProp({ required: "yes" }), /required must be true or false/],
  ["missing description", withEvent({ description: "" }), /description/],
  ["comment end in description", withEvent({ description: "bad */ text" }), /description/],
  ["duplicate key", withEvent({ properties: [plan.events[0].properties[0], plan.events[0].properties[0]] }), /duplicate key/],
  ["duplicate event", { ...plan, events: [plan.events[0], plan.events[0]] }, /duplicate event name/],
  ["no events", { ...plan, events: [] }, /non-empty/],
];
for (const [label, bad, message] of invalid) {
  test(`rejects: ${label}`, () => assert.throws(() => validatePlan(bad), message));
}

// Sample value per plan type.
const SAMPLE = { string: "sample", integer: 7, number: 1.5, boolean: true };

// Read the Kotlin functions back out of the generated text.
function parseKotlin(text) {
  const events = {};
  const re = /internal suspend fun SignalDock\.(\w+)\(([\s\S]*?)\): TrackResult =\s*track\("([^"]+)"\) \{([\s\S]*?)\n    \}/g;
  for (const m of text.matchAll(re)) {
    const params = {};
    for (const p of m[2].matchAll(/(\w+): (\w+)(\?)?( = null)?,/g)) params[p[1]] = { type: p[2], optional: Boolean(p[3]) };
    const props = [...m[4].matchAll(/property\("([^"]+)", (\w+)\)/g)].map((c) => ({ key: c[1], param: c[2] }));
    events[m[3]] = { fn: m[1], params, props };
  }
  return events;
}
const kotlin = parseKotlin(files["kotlin/TrackingPlan.kt"]);

test("Kotlin output has exactly the plan events", () => {
  assert.deepEqual(Object.keys(kotlin), plan.events.map((e) => e.name));
});

const dir = mkdtempSync(join(tmpdir(), "tracking-plan-"));
writeFileSync(join(dir, "events.ts"), files["typescript/events.ts"]);
const ts = await import(pathToFileURL(join(dir, "events.ts")).href);

for (const e of manifest.events) {
  test(`Kotlin and TypeScript agree: ${e.name}`, () => {
    const k = kotlin[e.name];
    assert.equal(k.fn, e.function);
    // Kotlin: one property() call per plan property, with the param of the plan's type and optionality.
    assert.deepEqual(k.props.map((c) => c.key), e.properties.map((p) => p.key));
    for (const p of e.properties) {
      assert.equal(k.props.find((c) => c.key === p.key).param, p.param, `${p.key}: property() argument`);
      assert.deepEqual(k.params[p.param], { type: p.kotlin, optional: !p.required }, `${p.key}: Kotlin param`);
    }
    assert.deepEqual(Object.keys(k.params), e.properties.map((p) => p.param));
    // TypeScript: run the builder with all properties, then with the required ones only.
    const all = Object.fromEntries(e.properties.map((p) => [p.param, SAMPLE[p.type]]));
    const required = Object.fromEntries(e.properties.filter((p) => p.required).map((p) => [p.param, SAMPLE[p.type]]));
    const wireOf = Object.fromEntries(e.properties.map((p) => [p.key, p.wire]));
    for (const [input, keys] of [[all, e.properties], [required, e.properties.filter((p) => p.required)]]) {
      const event = ts[e.function](input);
      assert.equal(event.name, e.name);
      assert.deepEqual(Object.keys(event.properties).sort(), keys.map((p) => p.key).sort());
      for (const [key, value] of Object.entries(event.properties)) assert.equal(typeof value, wireOf[key], `${key}: value type`);
    }
    // Kotlin type -> wire type must match what the TypeScript builder emitted.
    const KOTLIN_WIRE = { String: "string", Long: "number", Double: "number", Boolean: "boolean" };
    for (const p of e.properties) assert.equal(KOTLIN_WIRE[k.params[p.param].type], wireOf[p.key], `${p.key}: wire type`);
    // An optional property that is absent or null is left out in TypeScript, like null in Kotlin.
    const optional = e.properties.filter((p) => !p.required);
    for (const p of optional) assert.ok(!(p.key in ts[e.function]({ ...required, [p.param]: null }).properties));
  });
}
