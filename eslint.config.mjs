// ESLint for dashboard/, perf/report/, and web-shared/ (ADR-0049 W1).
// The config sits at the repository root because typescript-eslint needs TypeScript 6.0.x for its type-aware rules
// while the two apps compile with TypeScript 7 (no JS compiler API); see the ADR-0049 amendment of ticket 02.
import comments from "@eslint-community/eslint-plugin-eslint-comments/configs";
import js from "@eslint/js";
import jsxA11y from "eslint-plugin-jsx-a11y";
import reactHooks from "eslint-plugin-react-hooks";
import globals from "globals";
import tseslint from "typescript-eslint";

const TS = ["**/*.{ts,tsx,mts}"];

export default tseslint.config(
  { linterOptions: { reportUnusedDisableDirectives: "error" } },
  {
    // Only the web packages. Kotlin, Swift, generated code, and the k6 scripts in perf/ have their own gates.
    ignores: ["*", "!dashboard", "!perf", "!web-shared", "perf/*", "!perf/report", "**/dist/", "**/node_modules/", "**/.tmp-contract-check/"],
  },
  js.configs.recommended,
  {
    files: TS,
    extends: [tseslint.configs.strictTypeChecked, tseslint.configs.stylisticTypeChecked],
  },
  {
    // A number in a template literal is safe to print; the strict preset forbids it only for other types.
    files: TS,
    rules: {
      "@typescript-eslint/restrict-template-expressions": ["error", { allowNumber: true }],
      // The code marks a parameter that it must accept but does not read with a leading underscore.
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],
    },
  },
  {
    // The app and the shared code compile under dashboard/tsconfig.json (it includes ../web-shared); the report has its own.
    files: ["dashboard/**/*.{ts,tsx,mts}", "web-shared/**/*.{ts,tsx,mts}"],
    languageOptions: { parserOptions: { project: "dashboard/tsconfig.json", tsconfigRootDir: import.meta.dirname } },
  },
  {
    files: ["perf/report/**/*.{ts,tsx,mts}"],
    languageOptions: { parserOptions: { project: "perf/report/tsconfig.json", tsconfigRootDir: import.meta.dirname } },
  },
  {
    // Tests: a fixture lookup that finds nothing fails the assertion on the next line, mocks return promises without awaiting,
    // `expect(mock.method)` reads a method without calling it, and a no-op callback is a legitimate stub.
    files: ["**/*.test.{ts,tsx}"],
    rules: {
      "@typescript-eslint/no-non-null-assertion": "off",
      "@typescript-eslint/require-await": "off",
      "@typescript-eslint/unbound-method": "off",
      "@typescript-eslint/no-empty-function": "off",
    },
  },
  {
    // Node scripts and configs run outside the browser, and are not part of a tsconfig project.
    files: ["**/*.mjs", "**/vite.config.mts", "**/vitest.config.mts"],
    languageOptions: { globals: globals.node },
  },
  {
    files: ["**/*.mjs"],
    extends: [tseslint.configs.disableTypeChecked],
  },
  {
    files: ["**/*.{tsx,ts}"],
    languageOptions: { globals: globals.browser },
  },
  {
    files: ["**/*.tsx"],
    ...jsxA11y.flatConfigs.strict,
  },
  {
    files: ["**/*.tsx"],
    ...reactHooks.configs.flat["recommended-latest"],
  },
  {
    // A disable comment states why (ADR-0049 W1), covers one rule, and has to suppress something.
    ...comments.recommended,
    rules: {
      ...comments.recommended.rules,
      "@eslint-community/eslint-comments/require-description": ["error", { ignore: [] }],
      "@eslint-community/eslint-comments/disable-enable-pair": "error",
      "@eslint-community/eslint-comments/no-unlimited-disable": "error",
    },
  },
);
