package com.firemanage;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SingleColumnRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.support.SqlLobValue;
import org.springframework.jdbc.support.lob.DefaultLobHandler;
import org.springframework.stereotype.Component;

/** Named-parameter queries backed by Spring JDBC on Java 8. */
@Component
class JdbcClient {
    private final NamedParameterJdbcTemplate jdbc;
    private final DefaultLobHandler lobs = new DefaultLobHandler();

    JdbcClient(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        lobs.setStreamAsLob(true);
    }

    StatementSpec sql(String sql) {
        return new StatementSpec(sql);
    }

    StatementSpec limit(String orderedSql, int limit) {
        return sql("SELECT limited_rows.* FROM (" + orderedSql + ") limited_rows WHERE ROWNUM <= :fmLimit")
                .param("fmLimit", limit);
    }

    StatementSpec page(String orderedSql, long offset, int size) {
        return sql("SELECT * FROM (SELECT page_rows.*, ROWNUM AS fm_row_number FROM (" + orderedSql
                + ") page_rows WHERE ROWNUM <= :fmEnd) WHERE fm_row_number > :fmOffset ORDER BY fm_row_number")
                .param("fmEnd", offset + size).param("fmOffset", offset);
    }

    static final class IdList {
        private static final int MAX_IN_VALUES = 1000;
        private final Map<String, List<Long>> parameters = new LinkedHashMap<>();

        IdList(List<Long> ids) {
            for (int start = 0; start < ids.size(); start += MAX_IN_VALUES) {
                int end = Math.min(start + MAX_IN_VALUES, ids.size());
                parameters.put("fmIds" + parameters.size(), ids.subList(start, end));
            }
        }

        String matches(String column) {
            if (parameters.isEmpty()) return "1 = 0";
            StringJoiner clauses = new StringJoiner(" OR ", "(", ")");
            parameters.keySet().forEach(name -> clauses.add(column + " IN (:" + name + ")"));
            return clauses.toString();
        }

        Map<String, List<Long>> parameters() {
            return parameters;
        }
    }

    final class StatementSpec {
        private final String sql;
        private final MapSqlParameterSource parameters = new MapSqlParameterSource();

        private StatementSpec(String sql) {
            this.sql = sql;
        }

        StatementSpec param(String name, Object value) {
            if (value == null) {
                // Bind an untyped SQL NULL without querying parameter metadata on the 11g driver.
                parameters.addValue(name, null, Types.VARCHAR);
            } else if (value instanceof Boolean) {
                parameters.addValue(name, (Boolean) value ? 1 : 0, Types.NUMERIC);
            } else if (value instanceof LocalDate) {
                parameters.addValue(name, Date.valueOf((LocalDate) value), Types.DATE);
            } else if (value instanceof LocalDateTime) {
                parameters.addValue(name, Timestamp.valueOf((LocalDateTime) value), Types.TIMESTAMP);
            } else {
                parameters.addValue(name, value);
            }
            return this;
        }

        StatementSpec params(Map<String, ?> values) {
            values.forEach(this::param);
            return this;
        }

        StatementSpec clob(String name, String value) {
            parameters.addValue(name, value == null ? null : new SqlLobValue(value, lobs), Types.CLOB);
            return this;
        }

        StatementSpec blob(String name, byte[] value) {
            parameters.addValue(name, value == null ? null : new SqlLobValue(value, lobs), Types.BLOB);
            return this;
        }

        <T> Query<T> query(Class<T> type) {
            RowMapper<T> mapper = type == String.class || Number.class.isAssignableFrom(type)
                    ? new SingleColumnRowMapper<>(type) : new DataClassRowMapper<T>(type) {
                @Override
                protected Object getColumnValue(ResultSet rs, int index, Class<?> columnType) throws SQLException {
                    // ojdbc6 does not implement the JDBC 4.2 java.time getters.
                    if (columnType == LocalDate.class) {
                        Date date = rs.getDate(index);
                        return date == null ? null : date.toLocalDate();
                    }
                    if (columnType == LocalDateTime.class) {
                        Timestamp timestamp = rs.getTimestamp(index);
                        return timestamp == null ? null : timestamp.toLocalDateTime();
                    }
                    return super.getColumnValue(rs, index, columnType);
                }
            };
            return query(mapper);
        }

        <T> Query<T> query(RowMapper<T> mapper) {
            return new Query<>(sql, parameters, mapper);
        }

        int update() {
            return jdbc.update(sql, parameters);
        }

        long insert() {
            long id = JdbcClient.this.sql("SELECT fm_id_seq.NEXTVAL FROM dual").query(Long.class).single();
            param("newId", id).update();
            return id;
        }
    }

    final class Query<T> {
        private final String sql;
        private final MapSqlParameterSource parameters;
        private final RowMapper<T> mapper;

        private Query(String sql, MapSqlParameterSource parameters, RowMapper<T> mapper) {
            this.sql = sql;
            this.parameters = parameters;
            this.mapper = mapper;
        }

        List<T> list() {
            return jdbc.query(sql, parameters, mapper);
        }

        T single() {
            return DataAccessUtils.requiredSingleResult(list());
        }

        Optional<T> optional() {
            return Optional.ofNullable(DataAccessUtils.singleResult(list()));
        }
    }
}
