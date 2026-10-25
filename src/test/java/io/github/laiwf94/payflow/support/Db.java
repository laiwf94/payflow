package io.github.laiwf94.payflow.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Test helpers for resetting state between tests that share one database. */
public final class Db {

  private Db() {}

  /** Empties every table in one statement, so the locks are taken in one go. */
  public static void truncateAll(JdbcClient jdbc) {
    String tables =
        String.join(
            ", ",
            jdbc.sql(
                    """
                    SELECT quote_ident(tablename) FROM pg_tables
                    WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                    """)
                .query(String.class)
                .list());
    jdbc.sql("TRUNCATE " + tables + " CASCADE").update();
  }
}
