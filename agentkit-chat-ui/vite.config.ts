import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwind from '@tailwindcss/vite'

/**
 * The console's build.
 *
 * `dist/` is the only output. Maven copies it onto agentkit-chat's classpath as `ui/`, so the
 * built page is never committed — it is generated, like every other build product in this
 * repository. Pointing Vite straight at `target/classes` would have coupled this config to
 * Maven's layout and made `npm run build` on its own produce nothing you could look at.
 */
export default defineConfig({
  plugins: [react(), tailwind()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // Hashed filenames, because the server caches assets for a year and the page not at all.
    // A build that reused a name would serve last week's JavaScript to a returning tab.
    assetsDir: 'assets',
    // Fail the build rather than quietly shipping something a browser has to fetch. A strict
    // CSP is coming in #356 and every external reference is a thing it would then block; more
    // immediately, this console has to work on a machine with a model endpoint and no
    // internet.
    rollupOptions: {
      output: {
        assetFileNames: 'assets/[name]-[hash][extname]',
        chunkFileNames: 'assets/[name]-[hash].js',
        entryFileNames: 'assets/[name]-[hash].js',
      },
    },
    // Off for the packaged build. Vite's dev server maps everything anyway, which is where
    // the console is actually debugged; what this would add is a 900 kB map inside every jar,
    // for a page small enough to read.
    sourcemap: false,
  },
  server: {
    port: 5173,
    // `npm run dev` against a running console: the page is served by Vite with hot reload and
    // everything under /api goes to the Java server. Without this the dev page and the API are
    // different origins and every request is a CORS failure — which is the point at which
    // people give up on hot reload and start rebuilding the jar to change a colour.
    proxy: {
      '/api': {
        target: process.env.CHAT_API ?? 'http://localhost:8080',
        changeOrigin: true,
        ws: false,
      },
    },
  },
})
