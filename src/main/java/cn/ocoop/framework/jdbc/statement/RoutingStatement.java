package cn.ocoop.framework.jdbc.statement;

import cn.ocoop.framework.jdbc.AbstractSpayStatement;
import cn.ocoop.framework.jdbc.connection.ConnectionWrapper;
import cn.ocoop.framework.jdbc.connection.RoutingConnection;
import cn.ocoop.framework.jdbc.exception.MergedSQLException;
import cn.ocoop.framework.jdbc.execute.MethodInvocation;
import cn.ocoop.framework.jdbc.execute.MethodInvocationRecorder;
import cn.ocoop.framework.jdbc.resultset.ResultSetWrapper;
import cn.ocoop.framework.parse.OrderByParser;
import cn.ocoop.framework.parse.ShardColumnParser;
import cn.ocoop.framework.parse.shard.value.ShardValue;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Created by liolay on 2017/12/5.
 */
@Slf4j
public class RoutingStatement extends AbstractSpayStatement {
    protected RoutingConnection routingConnection;
    protected List<Statement> statements = Lists.newArrayList();
    protected MethodInvocation createMethodInvocation;
    protected MethodInvocationRecorder invocationRecorder = new MethodInvocationRecorder();
    protected boolean closed = false;
    protected String sql;
    private int maxRows = -1;
    private int queryTimeout = 0;
    private int fetchSize = 0;
    private int resultSetType = 0;
    private int resultSetConcurrency = 0;
    private int maxFieldSize = 65535;
    private boolean poolable = true;
    private int resultSetHoldability = java.sql.ResultSet.HOLD_CURSORS_OVER_COMMIT;

    public RoutingStatement(RoutingConnection routingConnection, MethodInvocation methodInvocation) {
        this.routingConnection = routingConnection;
        this.createMethodInvocation = methodInvocation;
    }

    public RoutingStatement(RoutingConnection routingConnection, MethodInvocation methodInvocation, int resultSetType, int resultSetConcurrency) {
        this(routingConnection, methodInvocation);
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
    }

    public RoutingStatement(RoutingConnection routingConnection, MethodInvocation methodInvocation, int resultSetType, int resultSetConcurrency, int resultSetHoldability) {
        this(routingConnection, methodInvocation, resultSetType, resultSetConcurrency);
        this.resultSetHoldability = resultSetHoldability;
    }

    /**
     * @return key:shardKey,value:shardValue
     */
    protected Map<String, Object> analyzeShardValue() {
        Map<String, Object> shardColumn_value = Maps.newHashMap();
        Map<String, ShardValue> name_value = ShardColumnParser.parse(sql);
        for (Map.Entry<String, ShardValue> shardItem : name_value.entrySet()) {
            shardColumn_value.put(shardItem.getKey(), resolveShardValue(shardItem.getValue()));
        }
        return shardColumn_value;
    }

    protected Object resolveShardValue(ShardValue value) {
        return value.getValue();
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        this.sql = sql;

        List<ResultSet> resultSets = Lists.newArrayList();
        List<ConnectionWrapper> connections = routingConnection.route(analyzeShardValue());
        for (ConnectionWrapper connection : connections) {
            Statement statement = (Statement) createMethodInvocation.invoke(connection);
            invocationRecorder.replay(statement);
            statements.add(statement);
            log.debug("数据源:{},执行sql:{}", connection.getDataSource().getName(), StringUtils.substringAfter(statement.toString(), ":"));
            ResultSet resultSet = statement.executeQuery(sql);
            if (resultSet != null) {
                resultSets.add(resultSet);
            }
        }
        if (resultSets.size() <= 0) return null;
        if (resultSets.size() == 1) return resultSets.get(0);
        return new ResultSetWrapper(OrderByParser.parse(sql), resultSets).proxy();
    }

    private int executeUpdate(String sql, Function<Statement, Integer> update) {
        this.sql = sql;

        Long result = 0L;
        List<ConnectionWrapper> connections = routingConnection.route(analyzeShardValue());
        for (ConnectionWrapper connection : connections) {
            Statement statement = (Statement) createMethodInvocation.invoke(connection);
            invocationRecorder.replay(statement);
            statements.add(statement);
            log.debug("数据源:{},执行sql:{}", connection.getDataSource().getName(), StringUtils.substringAfter(statement.toString(), ":"));
            result += update.apply(statement);
        }
        return result.intValue();
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        return executeUpdate(sql, statement -> {
            try {
                return statement.executeUpdate(sql);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeUpdate(sql, statement -> {
            try {
                return statement.executeUpdate(sql, autoGeneratedKeys);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return executeUpdate(sql, statement -> {
            try {
                return statement.executeUpdate(sql, columnIndexes);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return executeUpdate(sql, statement -> {
            try {
                return statement.executeUpdate(sql, columnNames);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private boolean execute(String sql, Function<Statement, Boolean> execute) {
        this.sql = sql;

        boolean isQuerySql = true;
        List<ConnectionWrapper> connections = routingConnection.route(analyzeShardValue());
        for (ConnectionWrapper connection : connections) {
            Statement statement = (Statement) createMethodInvocation.invoke(connection);
            invocationRecorder.replay(statement);
            statements.add(statement);
            log.debug("数据源:{},执行sql:{}", connection.getDataSource().getName(), sql);
            isQuerySql = execute.apply(statement) && isQuerySql;
        }
        return isQuerySql;
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return execute(sql, statement -> {
            try {
                return statement.execute(sql, autoGeneratedKeys);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

        });
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return execute(sql, statement -> {
            try {
                return statement.execute(sql, columnIndexes);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return execute(sql, statement -> {
            try {
                return statement.execute(sql, columnNames);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        return execute(sql, statement -> {
            try {
                return statement.execute(sql);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void close() throws SQLException {
        this.closed = true;
        for (Statement statement : statements) {
            statement.close();
        }
        statements.clear();
        invocationRecorder.clear();
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return this.maxFieldSize;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        this.maxFieldSize = max;
        record(new Class[]{int.class}, max);
        for (Statement statement : statements) {
            statement.setMaxFieldSize(max);
        }
    }

    @Override
    public int getMaxRows() throws SQLException {
        return this.maxRows;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        this.maxRows = max;
        record(new Class[]{int.class}, max);
        for (Statement statement : statements) {
            statement.setMaxRows(max);
        }
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        record(new Class[]{boolean.class}, enable);
        for (Statement statement : statements) {
            statement.setEscapeProcessing(enable);
        }
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return queryTimeout;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        this.queryTimeout = seconds;
        record(new Class[]{int.class}, seconds);
        for (Statement statement : statements) {
            statement.setQueryTimeout(seconds);
        }
    }

    @Override
    public void cancel() throws SQLException {
        MergedSQLException sqlException = new MergedSQLException();
        for (Statement statement : statements) {
            try {
                statement.cancel();
            } catch (SQLException e) {
                sqlException.stack(e);
            }
        }
        if (sqlException.notEmpty()) throw sqlException;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        SQLWarning sqlWarning = null;
        for (Statement statement : statements) {
            SQLWarning warnings = statement.getWarnings();
            if (warnings != null) {
                if (sqlWarning == null) {
                    sqlWarning = new SQLWarning();
                }
                sqlWarning.setNextWarning(warnings);
            }
        }
        return sqlWarning;
    }

    @Override
    public void clearWarnings() throws SQLException {
        for (Statement statement : statements) {
            statement.clearWarnings();
        }
    }


    @Override
    public ResultSet getResultSet() throws SQLException {
        List<ResultSet> resultSets = Lists.newArrayList();
        for (Statement statement : statements) {
            ResultSet resultSet = statement.getResultSet();
            if (resultSet != null) {
                resultSets.add(resultSet);
            }

        }
        if (resultSets.size() <= 0) return null;
        if (resultSets.size() == 1) return resultSets.get(0);
        return new ResultSetWrapper(OrderByParser.parse(sql), resultSets).proxy();
    }

    @Override
    public int getUpdateCount() throws SQLException {
        Long result = 0L;
        boolean hasResult = false;
        for (Statement each : statements) {
            if (each.getUpdateCount() > -1) {
                hasResult = true;
            }
            result += each.getUpdateCount();
        }
        return hasResult ? result.intValue() : -1;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return false;
    }

    @Override
    public int getFetchSize() throws SQLException {
        return this.fetchSize;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        this.fetchSize = rows;
        record(new Class[]{int.class}, rows);
        for (Statement statement : statements) {
            statement.setFetchSize(rows);
        }
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return this.resultSetConcurrency;
    }

    @Override
    public int getResultSetType() throws SQLException {
        return this.resultSetType;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return this.routingConnection;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return false;
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        return null;
    }


    @Override
    public int getResultSetHoldability() throws SQLException {
        return this.resultSetHoldability;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return this.closed;
    }

    @Override
    public boolean isPoolable() throws SQLException {
        return this.poolable;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        this.poolable = poolable;
        record(new Class[]{boolean.class}, poolable);
        for (Statement statement : statements) {
            statement.setPoolable(poolable);
        }
    }


    private void record(Class[] paramsType, Object... params) {
        String methodName = Thread.currentThread().getStackTrace()[2].getMethodName();
        invocationRecorder.record(Statement.class, methodName, paramsType, params);
    }


}
