import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The built portal is served by the Spring Boot app (modules/app) from its static resources.
export default defineConfig({
  plugins: [react()],
  build: { outDir: "dist", sourcemap: false },
  server: { proxy: { "/api": "http://localhost:8080", "/portal": "http://localhost:8080" } },
});
