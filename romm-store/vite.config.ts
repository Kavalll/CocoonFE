import { defineConfig } from "vite";

export default defineConfig({
  root: ".",
  base: "./",
  publicDir: "public",
  server: {
    host: true,
    port: 5173,
  },
  preview: {
    host: true,
    port: 4173,
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
});
