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

const destDir = join(root, "android", "app", "src", "main", "assets", "www");
rmSync(destDir, { recursive: true, force: true });
mkdirSync(destDir, { recursive: true });
writeFileSync(join(destDir, "index.html"), html);
console.log(`Copied inlined index.html (${html.length} bytes) to ${destDir}`);
