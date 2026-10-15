-- Disposable Testcontainers credentials only. Applications never connect as the container admin.
REVOKE ALL ON DATABASE inventory_test FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE ROLE inventory_app LOGIN PASSWORD 'inventory_test_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
CREATE ROLE inventory_alt_app LOGIN PASSWORD 'inventory_alt_test_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
GRANT CONNECT ON DATABASE inventory_test TO inventory_app, inventory_alt_app;
CREATE SCHEMA inventory AUTHORIZATION inventory_app;
CREATE SCHEMA inventory_alt AUTHORIZATION inventory_alt_app;
CREATE SCHEMA orders;
CREATE SCHEMA payments;
CREATE TABLE orders.denied_probe (id INTEGER);
CREATE TABLE payments.denied_probe (id INTEGER);
CREATE TABLE public.denied_probe (id INTEGER);
