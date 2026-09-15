#!/bin/bash
set -eu

# docs/sql is mounted as a directory; the schema stays in one place for docs and container bootstrap.
mysql --protocol=socket -uroot -p"$MYSQL_ROOT_PASSWORD" < /opt/zhihui/sql/schema.sql
