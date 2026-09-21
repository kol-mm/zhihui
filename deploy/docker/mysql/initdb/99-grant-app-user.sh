#!/bin/bash
set -eu

case "${MYSQL_USER:-}" in
  ''|*[!A-Za-z0-9_]* ) echo "MYSQL_USER must contain only letters, numbers and underscores" >&2; exit 1 ;;
esac

mysql_escape="${MYSQL_PASSWORD//\\/\\\\}"
mysql_escape="${mysql_escape//\'/\'\'}"
mysql --protocol=socket -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'%' IDENTIFIED BY '${mysql_escape}';
ALTER USER '${MYSQL_USER}'@'%' IDENTIFIED BY '${mysql_escape}';
GRANT ALL PRIVILEGES ON user_db.* TO '${MYSQL_USER}'@'%';
GRANT ALL PRIVILEGES ON knowledge_db.* TO '${MYSQL_USER}'@'%';
GRANT ALL PRIVILEGES ON community_db.* TO '${MYSQL_USER}'@'%';
GRANT ALL PRIVILEGES ON message_db.* TO '${MYSQL_USER}'@'%';
-- ai_db, audit_db and statistics_db were granted here but no service ever connected to them.
-- An existing deployment keeps the old grants until they are revoked by hand; see docs/server-deployment.md.
FLUSH PRIVILEGES;
SQL

