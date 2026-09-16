import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

/**
 * The console's tests run in jsdom against real components.
 *
 * Separate from vite.config.ts so the test run does not carry the Tailwind plugin or the dev
 * proxy — neither means anything here, and the CSS plugin in particular makes every test pay
 * for a stylesheet nothing asserts on.
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
