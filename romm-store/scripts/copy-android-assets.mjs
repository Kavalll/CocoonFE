import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const html = readFileSync(join(root, "dist", "index.html"), "utf8");

if (/type=["']module["']/.test(html)) {
  throw new Error("dist/index.html still uses ES modules; WebView will stay blank.");
}
if (!html.includes("Cocoon RomM Store")) {
  throw new Error("dist/index.html is missing the store markup.");
}
if (!html.includes('name="romm-store-build"')) {
  throw new Error("dist/index.html is missing the romm-store-build marker.");
}
if (html.includes("index-dSIPXYpo") || html.includes("assets/index-")) {
  throw new Error("dist/index.html still references hashed Vite asset files.");
}
const scriptStart = html.lastIndexOf("<script>");
const scriptEnd = html.indexOf("</script>", scriptStart);
if (scriptStart === -1 || scriptEnd === -1) {
  throw new Error("dist/index.html is missing the inlined store <script>.");
}
const script = html.slice(scriptStart + "<script>".length, scriptEnd);
if (/<\/(?:body|style|script)/i.test(script)) {
  throw new Error("Inlined JS contains a closing HTML tag; WebView will throw Unexpected token '<'.");
}

const destDir = join(root, "android", "app", "src", "main", "assets", "www");
rmSync(destDir, { recursive: true, force: true });
mkdirSync(destDir, { recursive: true });
writeFileSync(join(destDir, "index.html"), html);

const rawDir = join(root, "android", "app", "src", "main", "res", "raw");
mkdirSync(rawDir, { recursive: true });
writeFileSync(join(rawDir, "store.html"), html);

console.log(`Copied inlined index.html (${html.length} bytes) to assets/www and res/raw/store.html`);
