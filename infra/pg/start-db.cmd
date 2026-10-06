@echo off
rem Start PostgreSQL container arh-postgres on port 5433.
rem Creates the container when it does not exist. Data stays in volume arh_pgdata.
setlocal
docker inspect arh-postgres >nul 2>&1
if errorlevel 1 goto create
docker start arh-postgres >nul
if errorlevel 1 (
  echo Failed to start existing container arh-postgres
  exit /b 1
)
goto wait

:create
echo Creating container arh-postgres
docker run -d --name arh-postgres --restart unless-stopped ^
  -e POSTGRES_USER=aihub ^
  -e POSTGRES_PASSWORD=aihub ^
  -e POSTGRES_DB=ai_research_hub ^
  -p 5433:5432 ^
  -v arh_pgdata:/var/lib/postgresql/data ^
  -v "%~dp0..\..\backend\src\main\resources\db\schema.sql:/docker-entrypoint-initdb.d/schema.sql:ro" ^
  pgvector/pgvector:pg17
if errorlevel 1 (
  echo Failed to create PostgreSQL container. Is Docker Desktop running?
  exit /b 1
)

:wait
set /a n=0
:loop
docker exec arh-postgres pg_isready -U aihub -d ai_research_hub >nul 2>&1
if not errorlevel 1 goto ready
set /a n+=1
if %n% geq 30 (
  echo PostgreSQL was not ready within 30 seconds
  exit /b 1
)
ping -n 2 127.0.0.1 >nul
goto loop

:ready
echo PostgreSQL is ready on localhost:5433
exit /b 0
