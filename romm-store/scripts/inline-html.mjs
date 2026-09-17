import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const dist = "dist";
const assetsDir = join(dist, "assets");
if (!existsSync(assetsDir)) {
  throw new Error("Vite build did not produce dist/assets.");
}

const assets = readdirSync(assetsDir);
const jsFile = assets.find((name) => name.endsWith(".js"));
const cssFile = assets.find((name) => name.endsWith(".css"));
if (!jsFile) {
  throw new Error("Vite build did not produce a JS bundle to inline.");
}

let html = readFileSync(join(dist, "index.html"), "utf8");
const js = readFileSync(join(assetsDir, jsFile), "utf8").replaceAll("</script>", "<\\/script>");
const css = cssFile ? readFileSync(join(assetsDir, cssFile), "utf8") : "";

if (js.includes("await fetch(\"./platform-map.json\")") || js.includes("await fetch('./platform-map.json')")) {
  throw new Error("Bundle still fetches platform-map.json at startup; Android WebView will stay blank.");
}

html = html.replace(/<script type="module"[^>]*><\/script>/g, "");
html = html.replace(/<script[^>]*src="[^"]*assets\/[^"]+\.js"[^>]*><\/script>/g, "");
html = html.replace(/<link rel="stylesheet"[^>]*>/g, css ? `<style>${css}</style>` : "");
html = html.replace(/<link rel="modulepreload"[^>]*>/g, "");
html = html.replace(/<link rel="manifest"[^>]*>/g, "");
html = html.replace(/\s+crossorigin(="[^"]*")?/g, "");
if (!html.includes("</body>")) {
  throw new Error("Vite index.html is missing </body>; cannot place the inlined script.");
}
html = html.replace("</body>", `    <script>${js}</script>\n  </body>`);

if (/type=["']module["']/.test(html)) {
  throw new Error("index.html still contains type=module after inlining.");
}
if (!html.includes("<script>")) {
  throw new Error("Failed to inline the store JavaScript into index.html.");
}
if (html.lastIndexOf("<script>") < html.lastIndexOf('<div id="app">')) {
  throw new Error("Inlined script is still before #app; WebView will throw on boot.");
}

writeFileSync(join(dist, "index.html"), html);
console.log(`Inlined ${jsFile}${cssFile ? ` and ${cssFile}` : ""} into dist/index.html for Android WebView.`);
