package ru.mipt.java2016.homework.g597.bogdanov.task4.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.mipt.java2016.homework.base.task1.ParsingException;
import ru.mipt.java2016.homework.g597.bogdanov.task4.REST.functions.CalculatorFunctionObject;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.*;

/**
 * Created by Semyo_000 on 20.12.2016.
 */
@Repository
public class CalculatorDao {
    private static final Logger LOG = LoggerFactory.getLogger(CalculatorDao.class);

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void postConstruct() {
        jdbcTemplate = new JdbcTemplate(dataSource, false);
        initSchema();
    }

    public void initSchema() {
        LOG.trace("Initializing schema");
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS billing");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS billing.users " +
                "(username VARCHAR PRIMARY KEY, password VARCHAR, enabled BOOLEAN)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS billing.variables " +
                "(username VARCHAR, name VARCHAR, value DOUBLE)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS billing.functions " +
                "(username VARCHAR, name VARCHAR, arguments VARCHAR, expression VARCHAR)");
        addUserIfNotExists("username", "password", true);
    }

    boolean addUserIfNotExists(String username, String password, boolean enabled) {
        try {
            loadUser(username);
            return false;
        } catch (EmptyResultDataAccessException e) {
            jdbcTemplate.update("INSERT INTO billing.users VALUES (?, ?, ?)",
                    new Object[]{username, password, enabled});
            return true;
        }
    }

    public Double getVariable(String username, String variable) {
        return jdbcTemplate.queryForObject(
                "SELECT username, name, value FROM billing.variables WHERE username = ? AND name = ?",
                new Object[]{username, variable},
                new RowMapper<Double>() {
                    @Override
                    public Double mapRow(ResultSet rs, int rowNum) throws SQLException {
                        return rs.getDouble("value");
                    }
                }
        );
    }

    Map<String, Double> getVariables(String username) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT username, name, value FROM billing.variables WHERE username = ?",
                    new Object[]{username},
                    new RowMapper<HashMap<String, Double>>() {
                        @Override
                        public HashMap<String, Double> mapRow(ResultSet rs, int rowNum) throws SQLException {
                            HashMap<String, Double> result = new HashMap<>();
                            while (true) {
                                result.put(rs.getString("name"), rs.getDouble("value"));
                                if (!rs.next()) {
                                    break;
                                }
                            }
                            return result;
                        }
                    }
            );
        } catch (EmptyResultDataAccessException e) {
            HashMap<String, Double> result = new HashMap<>();
            return result;
        }
    }

    boolean deleteVariable(String username, String name) throws ParsingException {
        try {
            getVariable(username, name);
            jdbcTemplate.update("DELETE FROM billing.variables WHERE username = ? AND name = ?",
                    new Object[]{username, name});
            return true;
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }

    void addVariable(String username, String name, Double value) throws ParsingException {
        try {
            getVariable(username, name);
            jdbcTemplate.update("DELETE FROM billing.variables WHERE username = ? AND name = ?",
                    new Object[]{username, name});
            jdbcTemplate.update("INSERT INTO billing.variables VALUES (?, ?, ?)",
                    new Object[]{username, name, value});
        } catch (EmptyResultDataAccessException e) {
            jdbcTemplate.update("INSERT INTO billing.variables VALUES (?, ?, ?)",
                    new Object[]{username, name, value});
        }
    }

    public CalculatorFunctionObject getFunction(String username, String function) {
        return jdbcTemplate.queryForObject(
                "SELECT username, name, arguments, expression FROM billing.functions WHERE username = ? AND name = ?",
                new Object[]{username, function},
                new RowMapper<CalculatorFunctionObject>() {
                    @Override
                    public CalculatorFunctionObject mapRow(ResultSet rs, int rowNum) throws SQLException {
                        List<String> arguments = Arrays.asList(rs.getString("arguments").split(" "));
                        String expression = rs.getString("expression");
                        return new CalculatorFunctionObject(expression, arguments);
                    }
                }
        );
    }

    Map<String, CalculatorFunctionObject> getFunctions(String username) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT username, name, arguments, expression FROM billing.functions WHERE username = ?",
                    new Object[]{username},
                    new RowMapper<HashMap<String, CalculatorFunctionObject>>() {
                        @Override
                        public HashMap<String, CalculatorFunctionObject> mapRow(ResultSet rs,
                                                                                int rowNum) throws SQLException {
                            HashMap<String, CalculatorFunctionObject> result = new HashMap<>();
                            while (true) {
                                String name = rs.getString("name");
                                List<String> arguments = Arrays.asList(rs.getString("arguments").split(" "));
                                String expression = rs.getString("expression");
                                result.put(name, new CalculatorFunctionObject(expression, arguments));
                                if (!rs.next()) {
                                    break;
                                }
                            }
                            return result;
                        }
                    }
            );
        } catch (EmptyResultDataAccessException e) {
            HashMap<String, CalculatorFunctionObject> result = new HashMap<>();
            return result;
        }
    }

    boolean deleteFunction(String username, String function) throws ParsingException {
        try {
            getFunction(username, function);
            jdbcTemplate.update("DELETE FROM billing.variables WHERE username = ? AND name = ?",
                    new Object[]{username, function});
            return true;
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }

    void addFunction(String username, String function, List<String> arguments,
                     String expression) throws ParsingException {
        try {
            getFunction(username, function);
            jdbcTemplate.update("DELETE FROM billing.functions WHERE username = ? AND name = ?",
                    new Object[]{username, function});
            String stringArguments = String.join(" ", arguments);
            jdbcTemplate.update("INSERT INTO billing.functions VALUES (?, ?, ?, ?)",
                    new Object[]{username, function, stringArguments, expression});
        } catch (EmptyResultDataAccessException e) {
            String stringArguments = String.join(" ", arguments);
            jdbcTemplate.update("INSERT INTO billing.functions VALUES (?, ?, ?, ?)",
                    new Object[]{username, function, stringArguments, expression});
        }
    }

    public CalculatorUser loadUser(String username) throws EmptyResultDataAccessException {
        LOG.trace("Querying for user " + username);
        return jdbcTemplate.queryForObject(
                "SELECT username, password, enabled FROM billing.users WHERE username = ?",
                new Object[]{username},
                new RowMapper<CalculatorUser>() {
                    @Override
                    public CalculatorUser mapRow(ResultSet rs, int rowNum) throws SQLException {
                        return new CalculatorUser(
                                rs.getString("username"),
                                rs.getString("password"),
                                rs.getBoolean("enabled")
                        );
                    }
                }
        );
    }
}
