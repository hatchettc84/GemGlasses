#!/usr/bin/env node
// Small, anchored code insertions into a Plane checkout. Each edit is idempotent (skipped if
// its marker is already present) and fails loudly if its anchor is missing, so an upstream
// change is noticed at apply time instead of silently producing an unbranded image.
// Usage: node patch-code.mjs <plane-src-dir>

import fs from "node:fs";
import path from "node:path";

const root = process.argv[2];
if (!root || !fs.existsSync(path.join(root, "apps/web"))) {
  console.error("usage: patch-code.mjs <plane-src-dir>");
  process.exit(2);
}

let failures = 0;

function edit(rel, marker, fn) {
  const file = path.join(root, rel);
  if (!fs.existsSync(file)) {
    console.error(`patch-code: MISSING FILE ${rel}`);
    failures++;
    return;
  }
  const src = fs.readFileSync(file, "utf8");
  if (src.includes(marker)) {
    console.log(`patch-code: already applied  ${rel}`);
    return;
  }
  const out = fn(src);
  if (out == null || out === src) {
    console.error(`patch-code: ANCHOR NOT FOUND ${rel}`);
    failures++;
    return;
  }
  fs.writeFileSync(file, out);
  console.log(`patch-code: patched          ${rel}`);
}

// Insert `text` before the first match of `anchor` (string or RegExp).
function before(src, anchor, text) {
  const idx = typeof anchor === "string" ? src.indexOf(anchor) : src.search(anchor);
  if (idx < 0) return null;
  return src.slice(0, idx) + text + src.slice(idx);
}
// Insert an import line before the first top-level import.
function addImport(src, line) {
  return before(src, /^import /m, line + "\n");
}

// 1. Sidebar: "Phones & manuals" link (just above the collapsible "Workspace" section).
edit("apps/web/components/workspace/sidebar/sidebar-menu-items.tsx", "BobAIAdminSidebarLink", (src) => {
  let s = before(
    src,
    /^\s*<Collapsible\b/m,
    `      <div className="flex w-full flex-col gap-px">\n        <BobAIAdminSidebarLink />\n      </div>\n`
  );
  return s && addImport(s, `import { BobAIAdminSidebarLink } from "@/components/bobai/admin-link";`);
});

// 2. Help menu: AGPL-3.0 attribution entries at the bottom of the menu.
edit("apps/web/components/workspace/sidebar/help-section/root.tsx", "BobAIAboutMenuItems", (src) => {
  let s = before(src, /^\s*<\/MenuContent>/m, `          <BobAIAboutMenuItems />\n`);
  return s && addImport(s, `import { BobAIAboutMenuItems } from "@/components/bobai/about-menu-items";`);
});

// 3. Dockerfile.web: build args for the BobAI links (Vite inlines VITE_* at build time).
edit("apps/web/Dockerfile.web", "VITE_BOBAI_ADMIN_URL", (src) =>
  before(
    src,
    /^RUN pnpm turbo run build --filter=web/m,
    `# BobAI (plane-branding): URL of the BobAI admin page and of BobAI's modified source\n` +
      `ARG VITE_BOBAI_ADMIN_URL="http://localhost:5173"\nENV VITE_BOBAI_ADMIN_URL=$VITE_BOBAI_ADMIN_URL\n` +
      `ARG VITE_BOBAI_SOURCE_URL=""\nENV VITE_BOBAI_SOURCE_URL=$VITE_BOBAI_SOURCE_URL\n\n`
  )
);

// 4. turbo.json: turbo runs tasks in strict env mode, so the new vars must be declared
//    or they never reach the Vite build.
edit("turbo.json", "VITE_BOBAI_ADMIN_URL", (src) =>
  src.replace(/("globalEnv"\s*:\s*\[)/, `$1\n    "VITE_BOBAI_ADMIN_URL",\n    "VITE_BOBAI_SOURCE_URL",`)
);

if (failures) {
  console.error(`patch-code: ${failures} edit(s) failed -- Plane's source layout changed; update scripts/patch-code.mjs`);
  process.exit(1);
}
