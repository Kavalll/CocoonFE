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
const js = escapeInlineJs(readFileSync(join(assetsDir, jsFile), "utf8"));
const css = cssFile ? readFileSync(join(assetsDir, cssFile), "utf8") : "";

if (js.includes("await fetch(\"./platform-map.json\")") || js.includes("await fetch('./platform-map.json')")) {
  throw new Error("Bundle still fetches platform-map.json at startup; Android WebView will stay blank.");
}

html = html.replace(/<script type="module"[^>]*><\/script>/g, "");
html = html.replace(/<script[^>]*src="[^"]*assets\/[^"]+\.js"[^>]*><\/script>/g, "");
html = html.replace(/<link rel="stylesheet"[^>]*>/g, () => (css ? `<style>${css}</style>` : ""));
html = html.replace(/<link rel="modulepreload"[^>]*>/g, "");
html = html.replace(/<link rel="manifest"[^>]*>/g, "");
html = html.replace(/\s+crossorigin(="[^"]*")?/g, "");

const closeBody = html.lastIndexOf("</body>");
if (closeBody === -1) {
  throw new Error("Vite index.html is missing </body>; cannot place the inlined script.");
}

html = `${html.slice(0, closeBody)}    <script>${js}</script>\n  ${html.slice(closeBody)}`;

if (/type=["']module["']/.test(html)) {
  throw new Error("index.html still contains type=module after inlining.");
}
if (!html.includes("<script>")) {
  throw new Error("Failed to inline the store JavaScript into index.html.");
}
if (html.lastIndexOf("<script>") < html.lastIndexOf('<div id="app">')) {
  throw new Error("Inlined script is still before #app; WebView will throw on boot.");
}

const scriptStart = html.lastIndexOf("<script>");
const scriptEnd = html.indexOf("</script>", scriptStart);
if (scriptStart === -1 || scriptEnd === -1) {
  throw new Error("Failed to find the inlined store <script> after insertion.");
}
const script = html.slice(scriptStart + "<script>".length, scriptEnd);
if (/<\/(?:body|style|script)/i.test(script)) {
  throw new Error("Inlined JS contains a closing HTML tag; WebView will throw Unexpected token '<'.");
}

writeFileSync(join(dist, "index.html"), html);
console.log(`Inlined ${jsFile}${cssFile ? ` and ${cssFile}` : ""} into dist/index.html for Android WebView.`);

function escapeInlineJs(source) {
  return source.replace(/<\/(script|body|style)/gi, "<\\/$1");
}
