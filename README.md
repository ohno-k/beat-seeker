# beat-seeker

beatmania IIDX score management and skill visualization tool.

## Project Structure
- `backend/`: Spring Boot 3.3.0 (Java 17)
- `frontend/`: Vite + Vue 3 + TypeScript + Tailwind CSS

## How to Run

### 1. Backend
Navigate to the `backend` directory and run the setup script to download Maven (only needed once per session) and start the Spring Boot application:

```powershell
cd backend
.\setup_maven.ps1
```

*(Note: In subsequent uses in the same terminal, you can just run `mvn spring-boot:run`)*

- **Requirements**: Java 17
- **Database**: H2 In-memory (default). No setup required for development.
- **Port**: 8080 (default)

### 2. Frontend
Navigate to the `frontend` directory, install dependencies (if not already done), and start the development server:

```powershell
cd frontend
npm install
npm run dev
```

- **Requirements**: Node.js
- **Port**: 5173 (default)
- **Access**: [http://localhost:5173](http://localhost:5173)

## Tech Stack (Same as PoiSpo)
- **Frontend**: Vue 3, TS, Vite, Tailwind CSS
- **Backend**: Spring Boot 3.3.0, JPA, PostgreSQL/H2, Spring Security, OAuth2
