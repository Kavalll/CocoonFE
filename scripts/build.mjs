import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

export function escapeScript(code) {
  return String(code).replace(/<\/(script|style|body)/gi, "<\\/$1");
}

export function buildScript(shelfSource, yearsSource, mapJson) {
  const yearsExpr = String(yearsSource).replace(/^\s*module\.exports\s*=\s*/, "");
  return (
    "(function(){\n" +
    "window.__SHELF_YEARS__ = " + yearsExpr + "\n" +
    "window.__SHELF_MAP__ = " + mapJson + ";\n" +
    shelfSource +
    "\n})();\n"
  );
}

export function inlineHtml(template, script) {
  const marker = "</body>";
  const index = String(template).lastIndexOf(marker);
  if (index < 0) throw new Error("HTML template has no </body>");
  const injection = "<script>\n" + escapeScript(script) + "\n</script>\n";
  return template.slice(0, index) + injection + template.slice(index);
}

export function extractScript(html) {
  const source = String(html);
  const open = source.lastIndexOf("<script>");
  if (open < 0) throw new Error("HTML has no script");
  const start = open + "<script>".length;
  const close = source.indexOf("</script>", start);
  if (close < 0) throw new Error("HTML script is not closed");
  return source.slice(start, close).replace(/^\n/, "").replace(/\n$/, "");
}

function main() {
  const template = fs.readFileSync(path.join(root, "web", "index.html"), "utf8");
  const shelfSource = fs.readFileSync(path.join(root, "src", "shelf.js"), "utf8");
  const yearsSource = fs.readFileSync(path.join(root, "src", "years.js"), "utf8");
  const mapJson = JSON.stringify(JSON.parse(fs.readFileSync(path.join(root, "platform-map.json"), "utf8")));
  const script = buildScript(shelfSource, yearsSource, mapJson);
  const html = inlineHtml(template, script);
  const out = path.join(root, "android", "app", "src", "main", "res", "raw", "shelf.html");
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, html);
  const parsed = extractScript(html);
  new Function(parsed);
  if (/<\/(?:script|style|body)/i.test(parsed)) {
    throw new Error("Inlined script still contains a raw closing HTML tag");
  }
  process.stdout.write("Wrote " + out + " (" + html.length + " bytes)\n");
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
