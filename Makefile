
db-up:
	./run_with_env goose -dir migrations up
db-status:
	./run_with_env goose -dir migrations status
clear-db:
	docker exec -it postgress psql -U app -c "DROP DATABASE audio;" -c "CREATE DATABASE audio;"