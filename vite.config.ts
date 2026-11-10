import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  // GitHub Pages: https://baschek.github.io/kaffeapp/ (die Pages-Action setzt VITE_BASE_PATH aus dem Repo-Namen).
  base: process.env.VITE_BASE_PATH || '/kaffeapp/',
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
