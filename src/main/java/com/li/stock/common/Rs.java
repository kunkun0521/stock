package com.li.stock.common;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ResultSet 读取工具：把 JDBC 的基本类型空值处理收敛到一处。
 */
public final class Rs {

    private Rs() {
    }

    public static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public static Long lng(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public static BigDecimal dec(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column);
    }

    public static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    public static LocalDateTime dateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }

    public static String str(ResultSet rs, String column) throws SQLException {
        return rs.getString(column);
    }
}
