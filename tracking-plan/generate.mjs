// Tracking plan generator (ADR-0021). No dependencies: plain Node.
// Usage: node tracking-plan/generate.mjs [outDir]   (default: tracking-plan/generated)
// Reads tracking-plan/plan.json, validates it, and writes:
//   kotlin/TrackingPlan.kt        typed SignalDock extension functions (demo app)
//   typescript/events.ts          types + builders that return { name, properties }
//   swift/TrackingPlan.swift      typed SignalDockSDK extension functions (iOS; compiled by the sdk-ios tests)
//   manifest.json                 what all outputs must agree on (checked by generate.test.mjs)
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
// Plan value types -> contract wire type -> Kotlin and Swift type. "integer" is Long: the contract allows +-(2^53-1).
const TYPES = {
  string: { wire: "string", kotlin: "String", swift: "String" },
  integer: { wire: "number", kotlin: "Long", swift: "Int64" },
  number: { wire: "number", kotlin: "Double", swift: "Double" },
  boolean: { wire: "boolean", kotlin: "Boolean", swift: "Bool" },
};
// Naming convention (ADR-0021): lowercase snake_case. A stricter subset of Q64, which only bans
// empty/whitespace-padded names, so every valid plan name is a valid SDK and server name.
const SNAKE = /^[a-z][a-z0-9]*(_[a-z0-9]+)*$/;
const MAX_LEN = 64;
const KOTLIN_KEYWORDS = new Set(
  "as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while".split(" "),
);

// A generated Swift parameter or local name must not be one of these (the parameter `isolation` is the one of `track`).
const SWIFT_RESERVED = new Set(
  ("associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private precedencegroup protocol public " +
    "rethrows static struct subscript typealias var break case catch continue default defer do else fallthrough for guard if in repeat return " +
    "switch where while any as await false is nil self super throw throws true try isolation").split(" "),
);

// Value names the generated TypeScript declares itself, and public members of SignalDock (Android) and SignalDockSDK (iOS);
// an event function with the same name would clash or, as an SDK member wins, silently call the member.
const RESERVED_IDS = new Set(["eventNames", "clearQueue", "isOptedOut", "setOptOut", "diagnostics", "track", "initialize"]);

export function validatePlan(plan) {
  const errors = [];
  const bad = (m) => errors.push(m);
  const isText = (s) => typeof s === "string" && s.trim() !== "" && !/[\r\n]|\*\/|\/\*/.test(s) && s === s.trim();
  if (plan?.version !== 1) bad("version must be 1");
  // typeof first: a regex test or Object.hasOwn would turn an array like ["a.b"] into a string.
  const pkg = plan?.kotlinPackage;
  if (typeof pkg !== "string" || !/^[a-z][a-z0-9]*(\.[a-z][a-z0-9_]*)*$/.test(pkg) || pkg.split(".").some((w) => KOTLIN_KEYWORDS.has(w))) {
    bad("kotlinPackage must be a lowercase dotted package name with no Kotlin keyword");
  }
  if (!Array.isArray(plan?.events) || plan.events.length === 0) bad("events must be a non-empty array");
  const names = new Set();
  const ids = new Set(); // generated identifiers: snake_case names that differ can give one camelCase name
  for (const e of Array.isArray(plan?.events) ? plan.events : []) {
    const at = `event "${e?.name}"`;
    if (typeof e?.name !== "string" || !SNAKE.test(e.name) || e.name.length > MAX_LEN || e.name.split("_").length < 2) {
      bad(`${at}: name must be snake_case (${SNAKE}), at least two words, at most ${MAX_LEN} characters`);
    }
    if (names.has(e?.name)) bad(`${at}: duplicate event name`);
    names.add(e?.name);
    if (typeof e?.name === "string") {
      if (RESERVED_IDS.has(camel(e.name))) bad(`${at}: generated function name ${camel(e.name)} is reserved by the generated code`);
      else if (ids.has(camel(e.name))) bad(`${at}: generated function name ${camel(e.name)} is also made by another event`);
      ids.add(camel(e.name));
    }
    if (!isText(e?.description)) bad(`${at}: description must be one trimmed line of text without "/*" or "*/"`);
    if (!Array.isArray(e?.properties)) { bad(`${at}: properties must be an array`); continue; }
    const keys = new Set();
    const params = new Set();
    for (const p of e.properties) {
      const pat = `${at}, property "${p?.key}"`;
      if (typeof p?.key !== "string" || !SNAKE.test(p.key) || p.key.length > MAX_LEN) bad(`${pat}: key must be snake_case (${SNAKE}), at most ${MAX_LEN} characters`);
      else if (KOTLIN_KEYWORDS.has(p.key)) bad(`${pat}: key is a Kotlin keyword`);
      else if (SWIFT_RESERVED.has(camel(p.key))) bad(`${pat}: key is a Swift keyword or the Swift parameter name "isolation"`);
      if (keys.has(p?.key)) bad(`${pat}: duplicate key`);
      keys.add(p?.key);
      if (typeof p?.key === "string") {
        if (params.has(camel(p.key))) bad(`${pat}: generated parameter name ${camel(p.key)} is also made by another key`);
        params.add(camel(p.key));
      }
      if (typeof p?.type !== "string" || !Object.hasOwn(TYPES, p.type)) bad(`${pat}: type must be one of ${Object.keys(TYPES).join(", ")}`);
      if (typeof p?.required !== "boolean") bad(`${pat}: required must be true or false`);
      if (!isText(p?.description)) bad(`${pat}: description must be one trimmed line of text without "/*" or "*/"`);
    }
  }
  if (errors.length) throw new Error("invalid tracking plan:\n- " + errors.join("\n- "));
}

const camel = (s) => s.replace(/_([a-z0-9])/g, (_, c) => c.toUpperCase());
const pascal = (s) => camel(s).replace(/^./, (c) => c.toUpperCase());
const HEADER = "Generated by tracking-plan/generate.mjs from tracking-plan/plan.json. Do not edit.";

// Required properties first, so Kotlin call sites can omit the optional ones.
const ordered = (e) => [...e.properties.filter((p) => p.required), ...e.properties.filter((p) => !p.required)];

function kotlin(plan) {
  const out = [`// ${HEADER}`, `package ${plan.kotlinPackage}`, "", "import local.signaldock.SignalDock", "import local.signaldock.TrackResult", ""];
  out.push("/** Event names from the tracking plan. */", "internal object EventNames {");
  for (const e of plan.events) out.push(`    const val ${e.name.toUpperCase()} = "${e.name}"`);
  out.push("}");
  for (const e of plan.events) {
    const params = ordered(e).map((p) => `    ${camel(p.key)}: ${TYPES[p.type].kotlin}${p.required ? "," : "? = null,"}`);
    out.push("", `/** ${e.description} Calls [SignalDock.track]; optional properties that are null are left out. */`);
    out.push(`internal suspend fun SignalDock.${camel(e.name)}(${params.length ? "\n" + params.join("\n") + "\n" : ""}): TrackResult =`);
    out.push(`    track("${e.name}") {`);
    for (const p of ordered(e)) {
      const call = `property("${p.key}", ${camel(p.key)})`;
      out.push(p.required ? `        ${call}` : `        if (${camel(p.key)} != null) ${call}`);
    }
    out.push("    }");
  }
  return out.join("\n") + "\n";
}

// Word-wraps a doc comment so that every line stays within the 120 columns of `swift format`.
function swiftDoc(text) {
  const lines = [];
  let line = "///";
  for (const word of text.split(" ")) {
    if (line.length + 1 + word.length > 100 && line !== "///") { lines.push(line); line = "///"; }
    line += ` ${word}`;
  }
  return [...lines, line];
}

function swift(plan) {
  const out = [`// ${HEADER}`, "", "import SignalDock", ""];
  out.push("/// Event names from the tracking plan.", "internal enum EventNames {");
  for (const e of plan.events) out.push(`  static let ${camel(e.name)} = "${e.name}"`);
  out.push("}");
  out.push("", "extension SignalDockSDK {");
  plan.events.forEach((e, n) => {
    const params = ordered(e).map((p) => `${camel(p.key)}: ${TYPES[p.type].swift}${p.required ? "" : "? = nil"}`);
    if (n) out.push("");
    out.push(...swiftDoc(`${e.description} Calls \`track\`; optional properties that are nil are left out.`).map((l) => `  ${l}`));
    // Same shape as `track`: the caller's isolation first, typed throws, so the closure runs on the caller's actor.
    out.push(`  internal func ${camel(e.name)}(`, "    isolation: isolated (any Actor)? = #isolation" + (params.length ? "," : ""));
    params.forEach((p, i) => out.push(`    ${p}${i < params.length - 1 ? "," : ""}`));
    out.push("  ) async throws(CancellationError) -> TrackResult {", `    try await self.track(isolation: isolation, EventNames.${camel(e.name)}) {`);
    for (const p of ordered(e)) {
      const call = `$0.property("${p.key}", ${camel(p.key)})`;
      out.push(p.required ? `      ${call}` : `      if let ${camel(p.key)} { ${call} }`);
    }
    out.push("    }", "  }");
  });
  out.push("}");
  return out.join("\n") + "\n";
}

function typescript(plan) {
  const out = [
    `// ${HEADER}`,
    "",
    "/** A property value the contract accepts: string, number, or boolean (no null, no nesting). */",
    "export type PropertyValue = string | number | boolean;",
    "",
    "/** The name and properties of one event: the part of the contract EventIngest that the app chooses. */",
    "export interface EventInput {",
    "  name: string;",
    "  properties: { [key: string]: PropertyValue };",
    "}",
    "",
    "export const eventNames = {",
    ...plan.events.map((e) => `  ${camel(e.name)}: "${e.name}",`),
    "} as const;",
  ];
  for (const e of plan.events) {
    out.push("", `export interface ${pascal(e.name)}Props {`);
    for (const p of ordered(e)) out.push(`  /** ${p.description} */`, `  ${camel(p.key)}${p.required ? "" : "?"}: ${TYPES[p.type].wire}${p.required ? "" : " | null"};`);
    out.push("}", "", `/** ${e.description} Optional properties that are undefined or null are left out. */`);
    out.push(`export function ${camel(e.name)}(props: ${pascal(e.name)}Props): EventInput {`, "  const properties: EventInput[\"properties\"] = {};");
    for (const p of ordered(e)) {
      const set = `properties["${p.key}"] = props.${camel(p.key)};`;
      out.push(p.required ? `  ${set}` : `  if (props.${camel(p.key)} != null) ${set}`);
    }
    out.push(`  return { name: "${e.name}", properties };`, "}");
  }
  return out.join("\n") + "\n";
}

function manifest(plan) {
  return JSON.stringify({
    events: plan.events.map((e) => ({
      name: e.name,
      function: camel(e.name),
      properties: ordered(e).map((p) => ({ key: p.key, param: camel(p.key), type: p.type, wire: TYPES[p.type].wire, kotlin: TYPES[p.type].kotlin, swift: TYPES[p.type].swift, required: p.required })),
    })),
  }, null, 2) + "\n";
}

/** Returns { relativePath: content }. Throws on an invalid plan. */
export function generate(plan) {
  validatePlan(plan);
  return { "kotlin/TrackingPlan.kt": kotlin(plan), "typescript/events.ts": typescript(plan), "swift/TrackingPlan.swift": swift(plan), "manifest.json": manifest(plan) };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const out = resolve(process.argv[2] ?? join(HERE, "generated"));
    const files = generate(JSON.parse(readFileSync(join(HERE, "plan.json"), "utf8")));
    for (const [rel, text] of Object.entries(files)) {
      mkdirSync(dirname(join(out, rel)), { recursive: true });
      writeFileSync(join(out, rel), text);
    }
    console.log(`generated ${Object.keys(files).length} files -> ${out}`);
  } catch (err) {
    console.error(`error: ${err.message}`);
    process.exit(1);
  }
}
