// Issue 27: no visible text is hard-coded in the components of the Dashboard and the benchmark report.
// Lint-style scan of the syntax tree of every component file (parser from vite's bundler, so no new dependency).
// It flags, in .tsx files that are not tests:
//   - JSX text with a word (two or more letters in a row),
//   - a string or template literal with a word inside a JSX expression `{...}` (also in ternaries, `&&`, `+`, templates),
//   - a string with a word in a visible attribute (aria-label, title, placeholder, alt, label, name).
// Calls such as `t("key")` are not descended into, so keys and `t()` arguments pass. Words in ALLOWED are identifiers
// that must stay in their original form (spec Q135: property keys, API fields, units).
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { parseSync } from "rolldown/utils";
import { describe, expect, it } from "vitest";

const here = dirname(fileURLToPath(import.meta.url));
const ROOTS = [here, join(here, "../../perf/report/src")];
const VISIBLE_ATTRIBUTES = new Set(["aria-label", "aria-description", "title", "placeholder", "alt", "label", "name"]);
const ALLOWED = new Set(["receivedAt", "installationId", "eventId", "eventName", "planStatus", "sdkPlatform", "ms", "Hikari"]);

// A field that holds a count, size, or duration (by its name) must be shown through n() / f1() (ADR-0015).
const NUMERIC_FIELD = /(_s|_ms|_kib|_size|size|count|counts|repetition|started|scheduled|dropped|requests|samples|rows|days|clients|calls)$/i;
const rawNumber = (e: Node): string | null =>
  e.type === "Literal" && typeof e.value === "number" ? String(e.value)
  : e.type === "MemberExpression" && !e.computed && NUMERIC_FIELD.test((e.property as { name: string }).name) ? (e.property as { name: string }).name
  : null;

const hasWord = (text: string) => text.split(/[^\p{L}\p{N}_]+/u).some((w) => /\p{L}{2,}/u.test(w) && !ALLOWED.has(w));

type Node = { type: string; [key: string]: unknown };

/** Strings that reach the screen from an expression, without going through a call. */
function literals(node: Node | null | undefined): string[] {
  if (!node) return [];
  switch (node.type) {
    case "Literal": return typeof node.value === "string" ? [node.value] : [];
    case "TemplateLiteral": return [...(node.quasis as { value: { cooked: string } }[]).map((q) => q.value.cooked), ...(node.expressions as Node[]).flatMap(literals)];
    case "ConditionalExpression": return [...literals(node.consequent as Node), ...literals(node.alternate as Node)];
    // `a && b` shows only b; comparisons such as `status === "ok"` show nothing.
    case "LogicalExpression": return node.operator === "&&" ? literals(node.right as Node) : [...literals(node.left as Node), ...literals(node.right as Node)];
    case "BinaryExpression": return node.operator === "+" ? [...literals(node.left as Node), ...literals(node.right as Node)] : [];
    case "JSXExpressionContainer": return literals(node.expression as Node);
    case "ParenthesizedExpression": return literals(node.expression as Node);
    default: return [];
  }
}

function findings(file: string, code = readFileSync(file, "utf8")): string[] {
  const parsed = parseSync(file, code, { lang: "tsx" });
  expect(parsed.errors, file).toEqual([]);
  const found: string[] = [];
  const visit = (node: unknown): void => {
    if (Array.isArray(node)) return node.forEach(visit);
    if (!node || typeof node !== "object") return;
    const n = node as Node;
    if (n.type === "JSXText" && hasWord(n.value as string)) found.push(`JSX text ${JSON.stringify((n.value as string).trim())}`);
    if (n.type === "JSXExpressionContainer" && (n.expression as Node).type !== "JSXEmptyExpression") {
      for (const s of literals(n.expression as Node)) if (hasWord(s)) found.push(`string in JSX expression ${JSON.stringify(s)}`);
      const raw = rawNumber(n.expression as Node);
      if (raw) found.push(`unformatted number in JSX {${raw}}`);
    }
    if (n.type === "JSXAttribute") {
      const name = (n.name as { name?: string }).name ?? "";
      if (VISIBLE_ATTRIBUTES.has(name)) for (const s of literals(n.value as Node)) if (hasWord(s)) found.push(`attribute ${name}=${JSON.stringify(s)}`);
      return; // the value is not on screen unless the attribute is visible; render props are not used
    }
    for (const value of Object.values(n)) visit(value);
  };
  visit(parsed.program);
  return found;
}

const componentFiles = ROOTS.flatMap((root) =>
  readdirSync(root, { recursive: true, withFileTypes: true })
    .filter((e) => e.isFile() && e.name.endsWith(".tsx") && !e.name.includes(".test."))
    .map((e) => join(e.parentPath, e.name)),
);

describe("hard-coded visible strings", () => {
  it("the scan sees the component files of both pages", () => {
    const names = componentFiles.map((f) => relative(join(here, "../.."), f));
    expect(names).toContain("dashboard/src/App.tsx");
    expect(names).toContain("perf/report/src/App.tsx");
  });

  it("the scan detects each kind of hard-coded text and accepts t() calls", () => {
    const bad = 'export const A = () => <p title="Hello there" aria-label={"Close it"}>Plain words {ok ? "yes sir" : t("k")}{`xy ${t("k")} tail`}</p>;';
    expect(findings("bad.tsx", bad)).toEqual([
      'attribute title="Hello there"',
      'attribute aria-label="Close it"',
      'JSX text "Plain words"',
      'string in JSX expression "yes sir"',
      'string in JSX expression "xy "',
      'string in JSX expression " tail"',
    ]);
    expect(findings("num.tsx", "export const A = () => <td>{r.pool_size}{0}{n(r.pool_size)}{r.run_id}</td>;")).toEqual([
      "unformatted number in JSX {pool_size}",
      "unformatted number in JSX {0}",
    ]);
    const good = 'export const A = () => <p title={t("a")} className={ok ? "a b" : "c d"} data-k="some value">{t("b")} {" | "} {n} ms <code>receivedAt</code></p>;';
    expect(findings("good.tsx", good)).toEqual([]);
  });

  it.each(componentFiles.map((f) => [relative(join(here, "../.."), f), f]))("%s has no hard-coded visible text", (_name, file) => {
    expect(findings(file as string)).toEqual([]);
  });
});
