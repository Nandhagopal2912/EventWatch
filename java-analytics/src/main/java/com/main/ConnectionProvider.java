package com.main;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Hands out database connections so the repositories do not care whether they are
 * opening a local file or borrowing from a pool.
 */
public interface ConnectionProvider extends AutoCloseable {
    Connection getConnection() throws SQLException;

    @Override
    void close();
}
