@echo off
rem Stop the PostgreSQL container. The data volume arh_pgdata is kept.
docker stop arh-postgres
