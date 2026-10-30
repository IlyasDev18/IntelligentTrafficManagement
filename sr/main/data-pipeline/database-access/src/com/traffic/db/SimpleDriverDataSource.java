package com.traffic.db;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * Minimal DataSource implementation that delegates to DriverManager.
 * Used for simple setups where a full connection-pool is not needed.
 */
public class SimpleDriverDataSource implements DataSource {

    private final String url;
    private final String user;
    private final String password;

    public SimpleDriverDataSource(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    public Connection getConnection(String username, String pwd) throws SQLException {
        return DriverManager.getConnection(url, username, pwd);
    }

    // The remaining DataSource methods are optional for this minimal wrapper.
    @Override
    public PrintWriter getLogWriter() throws SQLException { throw new SQLFeatureNotSupportedException(); }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException { throw new SQLFeatureNotSupportedException(); }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException { DriverManager.setLoginTimeout(seconds); }

    @Override
    public int getLoginTimeout() throws SQLException { return DriverManager.getLoginTimeout(); }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLFeatureNotSupportedException(); }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException { return false; }
}
