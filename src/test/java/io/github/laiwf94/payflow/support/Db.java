package io.github.laiwf94.payflow.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Test helpers for resetting state between tests that share one database. */
public final class Db {

  private Db() {}

  public static void truncateAll(JdbcClient jdbc) {
    jdbc.sql(
            """
            DO $$
            DECLARE t text;
            BEGIN
              FOR t IN SELECT tablename FROM pg_tables
                       WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
              LOOP
                EXECUTE format('TRUNCATE %I CASCADE', t);
              END LOOP;
            END $$
            """)
        .update();
  }
}
