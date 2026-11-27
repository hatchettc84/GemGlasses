#!/usr/bin/env node
// Replaces the user-visible product name "Plane" with "BobAI" in a Plane checkout.
//
//  * i18n locale JSON + web manifests: only string VALUES are touched (line based, so the
//    files keep their exact formatting; keys are never changed).
//  * app source (.ts/.tsx): only whole-word "Plane" on non-comment, non-import lines, so
//    identifiers (PlaneLogo, planeName), package names (@plane/...), URLs (plane.so) and
//    licence/copyright headers are left alone.
//  * Phrases that name Plane Software's own products/services (paid plans, Plane AI,
//    Plane's telemetry/marketing, the docs) are kept, because calling them "BobAI"
//    would be misleading.
//
// Idempotent: a second run finds nothing to change.
// Usage: node rebrand-text.mjs <plane-src-dir>

import fs from "node:fs";
import path from "node:path";

const root = process.argv[2];
if (!root || !fs.existsSync(path.join(root, "apps/web"))) {
  console.error("usage: rebrand-text.mjs <plane-src-dir>");
  process.exit(2);
}

const NAME = "BobAI";

// Exact phrase rewrites applied first (titles / taglines that would read oddly after a plain swap).
const PHRASES = [
  ["Plane | Simple, extensible, open-source project management tool.", "BobAI"],
  ["Plane Publish | Make your Plane boards public with one-click", "BobAI Publish"],
  ["Plane Publish | Make your Plane boards and roadmaps pubic with just one-click. ", "BobAI Publish"],
  ["Plane Publish is a customer feedback management tool built on top of plane.so", "Public BobAI boards and pages"],
  ["Plane | Accelerate software development with peace.", "BobAI"],
  ["Plane - Modern project management", "BobAI"],
];

// Kept verbatim (they refer to Plane Software's products, services, docs or the licence notice).
const PROTECTED = [
  "based on Plane",
  "Plane Software",
  "Plane Pro",
  "Plane One",
  "Plane Business",
  "Plane Enterprise",
  "Plane Cloud",
  "Plane AI",
  "Plane's AI",
  "Plane documentation",
  "Plane marketing",
  "Let Plane collect",
  "Allow Plane to anonymously",
];

const WORD = /(?<![\w$@./-])Plane(?![\w$])/g;

function rebrandString(s) {
  for (const [from, to] of PHRASES) s = s.split(from).join(to);
  const masks = [];
  for (const p of PROTECTED) {
    s = s.split(p).join(`\u0000${masks.length}\u0000`);
    masks.push(p);
  }
  s = s.replace(WORD, NAME);
  return s.replace(/\u0000(\d+)\u0000/g, (_, i) => masks[Number(i)]);
}

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (["node_modules", "build", "dist", ".react-router", ".turbo"].includes(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

let changedFiles = 0;
let changedLines = 0;
const unhandled = [];

function processFile(file, lineFn) {
  const src = fs.readFileSync(file, "utf8");
  const lines = src.split("\n");
  let changed = 0;
  const out = lines.map((line, i) => {
    if (!line.includes("Plane")) return line;
    const next = lineFn(line, i, file);
    if (next !== line) changed++;
    return next;
  });
  if (changed) {
    fs.writeFileSync(file, out.join("\n"));
    changedFiles++;
    changedLines += changed;
  }
}

// ---- JSON (locale values, manifests) ---------------------------------------------------
const STR = String.raw`"((?:[^"\\]|\\.)*)"`;
const KV = new RegExp(String.raw`^(\s*${STR}\s*:\s*)${STR}(.*)$`);
const ITEM = new RegExp(String.raw`^(\s*)${STR}(\s*,?\s*)$`);

function jsonLine(line, i, file) {
  let m = line.match(KV);
  if (m) return `${m[1]}"${rebrandString(m[3])}"${m[4]}`;
  m = line.match(ITEM);
  if (m) return `${m[1]}"${rebrandString(m[2])}"${m[3]}`;
  if (WORD.test(line)) unhandled.push(`${file}:${i + 1}: ${line.trim()}`);
  WORD.lastIndex = 0;
  return line;
}

const jsonFiles = [
  ...walk(path.join(root, "packages/i18n/src/locales")).filter((f) => f.endsWith(".json")),
  ...["web", "admin", "space"].flatMap((a) =>
    walk(path.join(root, "apps", a, "public")).filter((f) => /\.(json|webmanifest)$/.test(f))
  ),
  path.join(root, "apps/web/manifest.json"),
  path.join(root, "apps/space/app/assets/favicon/site.webmanifest"),
].filter((f) => fs.existsSync(f));

for (const f of jsonFiles) {
  processFile(f, jsonLine);
  JSON.parse(fs.readFileSync(f, "utf8")); // fail loudly if we ever broke a file
}

// ---- TS/TSX source -----------------------------------------------------------------------
const EXCLUDE = [
  /\.test\.tsx?$/,
  /\.stories\.tsx?$/,
  /\/billing\//, // Plane's paid-plan comparison tables
  /\/license\//, // Plane's paid-plan upgrade cards
  /\/integration\//, // Plane's cloud integrations (GitHub/Slack apps run by Plane)
  /\/components\/bobai\//, // our own files
];

function codeLine(line) {
  const t = line.trim();
  if (/Copyright|SPDX|License/i.test(line)) return line;
  if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return line;
  if (/^\s*import\s|^\s*export\s+\*\s+from|\bfrom\s+["']/.test(line)) return line;
  return rebrandString(line);
}

const codeFiles = [
  ...["web", "admin", "space"].flatMap((a) =>
    walk(path.join(root, "apps", a)).filter((f) => /\.(ts|tsx)$/.test(f) && !f.endsWith(".d.ts"))
  ),
  path.join(root, "packages/constants/src/metadata.ts"),
].filter((f) => !EXCLUDE.some((re) => re.test(f.split(path.sep).join("/"))));

for (const f of codeFiles) processFile(f, codeLine);

console.log(`rebrand-text: ${changedLines} line(s) changed in ${changedFiles} file(s)`);
if (unhandled.length) {
  console.log(`rebrand-text: ${unhandled.length} JSON line(s) with "Plane" left untouched (not a simple string line):`);
  for (const u of unhandled) console.log("  " + u);
}
