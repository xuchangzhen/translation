import { defineConfig } from "vite";
import { resolve } from "node:path";

export default defineConfig({
  root: ".",
  base: "./",
  build: {
    outDir: "dist",
    emptyOutDir: true,
    rollupOptions: {
      input: {
        main: resolve(process.cwd(), "index.html"),
        overlay: resolve(process.cwd(), "overlay.html"),
        popup: resolve(process.cwd(), "popup.html")
      }
    }
  }
});
