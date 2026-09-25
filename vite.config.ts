import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  base: process.env.VITE_BASE_PATH || '/Kaffeapp/',
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
