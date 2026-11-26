import { defineConfig } from 'vitest/config';

// Coverage gate for the React admin (spec A2): ratchet-up-only, a few
// tenths under measured. main.tsx is the two-line entry that calls mount().
export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['test/admin/**/*.test.{ts,tsx}'],
    setupFiles: ['test/admin/setup.ts'],
    coverage: {
      provider: 'v8',
      skipFull: false,
      include: ['src/admin/**/*.{ts,tsx}'],
      exclude: ['src/admin/main.tsx', 'src/admin/api/schema.d.ts'],
      thresholds: { lines: 90, branches: 80 }
    }
  }
});
