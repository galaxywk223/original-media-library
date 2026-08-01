import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: './frontend/src/test-setup.ts',
    include: ['frontend/src/**/*.test.tsx', 'tests-ts/**/*.test.ts'],
  },
})
