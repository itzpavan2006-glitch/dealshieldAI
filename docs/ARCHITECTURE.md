# Architecture

The intended modular monolith has a React/TypeScript frontend and a Spring Boot API. The frontend communicates only with the backend. Future phases will add Supabase PostgreSQL, Auth and private Storage, document processing and backend-only Gemini integration. Ownership checks and secure sessions must be designed and tested before protected features are enabled. Currently Vite proxies `/api` to `localhost:8080`; this development proxy is not a production deployment configuration.
