package com.peak885.peakybrowser4jv2.browser.cookiemonster;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.tinylog.Logger;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class SQLiteBiscuitJar implements BiscuitJar, AutoCloseable {

    private final Connection connection;

    public SQLiteBiscuitJar(File databaseFile) throws SQLException {
        File parent = databaseFile.getParentFile();

        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException(
                    "Failed to create cookie database directory: " + parent
            );
        }

        String url = "jdbc:sqlite:" + databaseFile.getAbsolutePath();

        this.connection = DriverManager.getConnection(url);

        initializeDatabase();

        Logger.info(
                "[Cookies] SQLite cookie store: {}",
                databaseFile.getAbsolutePath()
        );
    }

    private void initializeDatabase() throws SQLException {
        try (Statement statement = connection.createStatement()) {

            statement.execute("""
                PRAGMA journal_mode=WAL
                """);

            statement.execute("""
                PRAGMA foreign_keys=ON
                """);

            statement.execute("""
                CREATE TABLE IF NOT EXISTS cookies (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,

                    name TEXT NOT NULL,
                    value TEXT NOT NULL,

                    domain TEXT NOT NULL,
                    path TEXT NOT NULL,

                    expires_at INTEGER NOT NULL,

                    secure INTEGER NOT NULL,
                    http_only INTEGER NOT NULL,

                    host_only INTEGER NOT NULL,

                    creation_time INTEGER NOT NULL,

                    UNIQUE(name, domain, path)
                )
                """);

            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_cookies_domain
                ON cookies(domain)
                """);

            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_cookies_expiry
                ON cookies(expires_at)
                """);
        }
    }

    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        List<Cookie> result = new ArrayList<>();

        try {
            removeExpired();

            String host = url.host();

            /*
             * We intentionally fetch cookies whose domain could potentially
             * apply to this host, then let OkHttp's Cookie.matches() perform
             * the actual RFC domain/path/secure matching.
             */
            String sql = """
                SELECT
                    name,
                    value,
                    domain,
                    path,
                    expires_at,
                    secure,
                    http_only,
                    host_only
                FROM cookies
                WHERE domain = ?
                   OR ? LIKE '%.' || domain
                """;

            try (PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setString(1, host);
                statement.setString(2, host);

                try (ResultSet rs = statement.executeQuery()) {

                    while (rs.next()) {
                        Cookie cookie = buildCookie(rs);

                        if (cookie != null && cookie.matches(url)) {
                            result.add(cookie);
                        }
                    }
                }
            }

            if (!result.isEmpty()) {
                Logger.debug(
                        "[Cookies] Loaded {} biscuit(s) for {}",
                        result.size(),
                        url
                );
            }

        } catch (SQLException e) {
            Logger.error(e, "[Cookies] Failed to load cookies for {}", url);
        }

        return result;
    }

    @Override
    public synchronized void saveFromResponse(
            HttpUrl url,
            List<Cookie> cookies
    ) {
        if (cookies.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO cookies (
                name,
                value,
                domain,
                path,
                expires_at,
                secure,
                http_only,
                host_only,
                creation_time
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(name, domain, path)
            DO UPDATE SET
                value = excluded.value,
                expires_at = excluded.expires_at,
                secure = excluded.secure,
                http_only = excluded.http_only,
                host_only = excluded.host_only
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            for (Cookie cookie : cookies) {

                /*
                 * A cookie with max-age=0 / an expiration in the past means
                 * "delete this cookie".
                 */
                if (cookie.expiresAt() <= System.currentTimeMillis()) {
                    deleteCookie(cookie);
                    continue;
                }

                statement.setString(1, cookie.name());
                statement.setString(2, cookie.value());
                statement.setString(3, cookie.domain());
                statement.setString(4, cookie.path());
                statement.setLong(5, cookie.expiresAt());
                statement.setBoolean(6, cookie.secure());
                statement.setBoolean(7, cookie.httpOnly());
                statement.setBoolean(8, cookie.hostOnly());
                statement.setLong(9, System.currentTimeMillis());

                statement.addBatch();
            }

            statement.executeBatch();

            Logger.debug(
                    "[Cookies] Saved {} biscuit(s) from {}",
                    cookies.size(),
                    url
            );

        } catch (SQLException e) {
            Logger.error(
                    e,
                    "[Cookies] Failed to save cookies from {}",
                    url
            );
        }
    }

    private Cookie buildCookie(ResultSet rs) throws SQLException {
        Cookie.Builder builder = new Cookie.Builder()
                .name(rs.getString("name"))
                .value(rs.getString("value"))
                .path(rs.getString("path"));

        String domain = rs.getString("domain");

        if (rs.getBoolean("host_only")) {
            builder.hostOnlyDomain(domain);
        } else {
            builder.domain(domain);
        }

        long expiresAt = rs.getLong("expires_at");

        if (expiresAt != Long.MAX_VALUE) {
            builder.expiresAt(expiresAt);
        }

        if (rs.getBoolean("secure")) {
            builder.secure();
        }

        if (rs.getBoolean("http_only")) {
            builder.httpOnly();
        }

        return builder.build();
    }

    private void deleteCookie(Cookie cookie) throws SQLException {
        String sql = """
            DELETE FROM cookies
            WHERE name = ?
              AND domain = ?
              AND path = ?
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, cookie.name());
            statement.setString(2, cookie.domain());
            statement.setString(3, cookie.path());

            statement.executeUpdate();
        }
    }

    private void removeExpired() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM cookies
            WHERE expires_at <= ?
            """)) {

            statement.setLong(1, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    public synchronized void clear() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM cookies");
        }

        Logger.info("[Cookies] Cookie jar cleared");
    }

    public synchronized int size() throws SQLException {
        try (
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM cookies"
                )
        ) {
            return rs.getInt(1);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();

        Logger.info("[Cookies] SQLite cookie store closed");
    }
}