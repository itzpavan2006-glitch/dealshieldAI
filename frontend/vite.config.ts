import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath, URL } from 'node:url'
// The original local architecture is Vite on 5173 proxying API requests to
// Spring Boot on 18180. The browser therefore retains one local origin for
// HttpOnly sessions and CSRF cookies without exposing the backend directly.
const apiProxy = { '/api': { target: process.env.DEALSHIELD_API_URL || 'http://localhost:18180', changeOrigin: false } }
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  // Development can be viewed from a phone on the same private network. The browser
  // still sees one origin, so HttpOnly session cookies and CSRF remain protected.
  server: { host: '0.0.0.0', port: 5173, strictPort: true, ...(apiProxy ? { proxy: apiProxy } : {}) },
  // Preview uses a different default port and retains the same API proxy. This avoids
  // accidentally serving a static preview on the development port with no API access.
  preview: { host: '0.0.0.0', port: 4173, strictPort: true, ...(apiProxy ? { proxy: apiProxy } : {}) },
})
