package io.github.laiwf94.payflow.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Test helpers for resetting state between tests that share one database. */
public final class Db {

  private Db() {}

  /**
   * Empties every table in one statement, so the locks are taken in one go. The outbox comes first:
   * a relay running in the background locks it before the webhook tables it fans out to, and taking
   * the locks in that same order means the two wait for each other rather than deadlock.
   */
  public static void truncateAll(JdbcClient jdbc) {
    String tables =
        String.join(
            ", ",
            jdbc.sql(
                    """
                    SELECT quote_ident(tablename) FROM pg_tables
                    WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                    ORDER BY tablename <> 'outbox', tablename
                    """)
                .query(String.class)
                .list());
    jdbc.sql("TRUNCATE " + tables + " CASCADE").update();
  }
}
