# DealShield AI

Phase 1 foundation for a quotation decision-support application. This is not yet a functional procurement product. No accounts, database, uploads, comparisons, AI, or deployment are implemented.

## Windows setup

Install Java 21 and Node.js (a current Vite-compatible release), then open PowerShell in this folder. Maven is only needed if the Maven wrapper has not yet been generated. Dependencies require internet access. No credentials are required for Phase 1.

In one terminal:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

In a second terminal:

```powershell
cd frontend
npm install
npm run dev
```

Open the local URL printed by Vite. The page checks `/api/v1/health` through the Vite proxy to port 8080. Stop either server with Ctrl+C. For tests, run `.\mvnw.cmd test` from `backend` and `npm run build` from `frontend`.

If port 8080 is occupied, set `$env:PORT = '18080'` in the backend terminal before starting it. In the frontend terminal set `$env:DEALSHIELD_API_URL = 'http://localhost:18080'` before starting Vite. You can add `-- --port 15173` to the Vite command if its default port is also occupied.

See [progress](docs/PROGRESS.md), [architecture](docs/ARCHITECTURE.md), and [deployment](docs/DEPLOYMENT.md). Never commit real credentials; `.env.example` contains placeholders only.
