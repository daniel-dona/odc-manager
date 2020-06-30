package de.fraunhofer.fokus.ids.persistence.service;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;

import java.lang.reflect.Array;
import java.util.*;

/**
 * @author Vincent Bohlen, vincent.bohlen@fokus.fraunhofer.de
 */
public class DatabaseServiceImpl implements DatabaseService {
    private Logger LOGGER = LoggerFactory.getLogger(DatabaseServiceImpl.class.getName());
    private SQLClient jdbc;

    public enum ConnectionType {
        QUERY,
        UPDATE
    }

    public DatabaseServiceImpl(SQLClient dbClient, Handler<AsyncResult<DatabaseService>> readyHandler) {
        this.jdbc = dbClient;
        readyHandler.handle(Future.succeededFuture(this));
    }

    @Override
    public DatabaseService query(String query, JsonArray params, Handler<AsyncResult<List<JsonObject>>> resultHandler) {
        createResult(query, params, ConnectionType.QUERY, resultHandler);
        return this;
    }

    @Override
    public DatabaseService update(String query, JsonArray params, Handler<AsyncResult<List<JsonObject>>> resultHandler) {
        createResult(query, params, ConnectionType.UPDATE, resultHandler);
        return this;
    }

    @Override
    public DatabaseService initTable(JsonObject query, String tablename, Handler<AsyncResult<List<JsonObject>>> resultHandler) {
        createConnection(connection -> handleInitTable(connection,
                query,
                tablename,
                resultHandler));
        return this;
    }

    /**
     * processing pipeline to create the intended result
     *
     * @param queryString    SQL Query to perform
     * @param params         Query parameters for the SQL query
     * @param connectionType UPDATE or QUERY depending on the type of database manipulation to be performed
     */
    private void createResult(String queryString, JsonArray params, ConnectionType connectionType, Handler<AsyncResult<List<JsonObject>>> resultHandler) {

        createConnection(connection -> handleConnection(connection,
                connectionType,
                queryString,
                params,
                result -> handleResult(result,
                        resultHandler
                ),
                resultHandler));
    }

    /**
     * Method to retrieve the connection from the (postgre) SQL client
     *
     * @param next Handler to perform the query (handleQuery or handleQueryWithParams)
     */
    private void createConnection(Handler<AsyncResult<SQLConnection>> next) {

        jdbc.getConnection(res -> {
            if (res.succeeded()) {
                next.handle(Future.succeededFuture(res.result()));
            } else {
                LOGGER.error("Connection could not be established.", res.cause());
                next.handle(Future.failedFuture(res.cause()));
            }
        });
    }

    /**
     * Method to call the correct method specified by the connectionType enum.
     *
     * @param result         Connection future produced by createConnection
     * @param connectionType UPDATE or QUERY
     * @param queryString    SQL String to query
     * @param params         params for the SQL query
     * @param next           final step of pipeline: handleResult function
     */
    private void handleConnection(AsyncResult<SQLConnection> result,
                                  ConnectionType connectionType,
                                  String queryString,
                                  JsonArray params,
                                  Handler<AsyncResult<List<JsonObject>>> next,
                                  Handler<AsyncResult<List<JsonObject>>> resultHandler) {

        switch (connectionType) {
            case QUERY:
                handleQuery(result, queryString, params, next, resultHandler);
                break;
            case UPDATE:
                handleUpdate(result, queryString, params, resultHandler);
                break;
            default:
                resultHandler.handle(Future.failedFuture("Unknown Connection type specified."));
        }
    }

    /**
     * Method to perform the SQL query on the connection retrieved via createConnection
     *
     * @param result      Connection future produced by createConnection
     * @param queryString SQL String to query
     * @param params      params for the SQL query
     * @param next        final step of pipeline: handleResult function
     */
    private void handleQuery(AsyncResult<SQLConnection> result,
                             String queryString,
                             JsonArray params,
                             Handler<AsyncResult<List<JsonObject>>> next,
                             Handler<AsyncResult<List<JsonObject>>> resultHandler) {

        if (result.failed()) {
            LOGGER.error("Connection Future failed.", result.cause());
            resultHandler.handle(Future.failedFuture(result.cause().toString()));
        } else {
            SQLConnection connection = result.result();
            connection.queryWithParams(queryString, params, query -> {
                if (query.succeeded()) {
                    ResultSet rs = query.result();
                    next.handle(Future.succeededFuture(rs.getRows()));
                    connection.close();
                } else {
                    LOGGER.error("Query failed.", query.cause());
                    resultHandler.handle(Future.failedFuture(query.cause()));
                    connection.close();
                }
            });
        }
    }

    private void handleInitTable(AsyncResult<SQLConnection> result,
                                 JsonObject queryString,
                                 String tableName,
                                 Handler<AsyncResult<List<JsonObject>>> resultHandler) {
        List<String> keys = new ArrayList<>();
        queryString.forEach(e -> keys.add(e.getKey()));
        if (result.failed()) {
            LOGGER.error("Connection Future failed.", result.cause());
            resultHandler.handle(Future.failedFuture(result.cause().toString()));
        } else {
            SQLConnection connection = result.result();
            connection.query("select * from " + tableName, resultSetAsyncResult -> {
                if (resultSetAsyncResult.succeeded()) {
                    ResultSet resultSet = resultSetAsyncResult.result();
                    List<String> columns = resultSet.getColumnNames();
                    List<String> strings = new ArrayList<>();
                    String dropColumns = "";
                    for (String set : resultSet.getColumnNames()) {
                        if (!keys.contains(set)) {
                            dropColumns += "DROP COLUMN " + set + ",";
                            strings.add(set);
                        }
                    }
                    columns.removeAll(strings);
                    if (!dropColumns.isEmpty()) {
                        String dropQuery = "ALTER TABLE " + tableName + " " + dropColumns;
                        System.out.println("dropQuery " + dropQuery);
                        connection.query(dropQuery.substring(0, dropQuery.length() - 1), delete -> {
                            if (delete.succeeded()) {
                                LOGGER.info("Deleted columns " + strings.toString());
                                addColumns(connection,tableName,keys,queryString,resultHandler);
                            } else {
                                LOGGER.info("Delete columns " + strings.toString() + " failed!");
                            }
                        });
                    }
                    else {
                        addColumns(connection,tableName,keys,queryString,resultHandler);
                    }

                } else {
                    String query = "CREATE TABLE IF NOT EXISTS "+tableName+ " (";
                    String columns  ="";

                    for (String key:keys){
                        columns = columns+key+" "+queryString.getString(key)+",";
                    }
                    columns = columns.substring(0,columns.length()-1);
                    query = query+columns+")";
                    connection.updateWithParams(query, new JsonArray(), resultAsyncResult -> {
                        if (resultAsyncResult.succeeded()) {
                            LOGGER.info("No. of rows updated: " + resultAsyncResult.result().getUpdated());
                            resultHandler.handle(Future.succeededFuture(new ArrayList<>()));
                            connection.close();
                        } else {
                            LOGGER.error("Update failed.", resultAsyncResult.cause());
                            resultHandler.handle(Future.failedFuture(resultAsyncResult.cause()));
                            connection.close();
                        }
                    });

                }
            });

        }
    }

    private void addColumns(SQLConnection connection,String tableName,List<String> columns,JsonObject query,Handler<AsyncResult<List<JsonObject>>> resultHandler){
        String updateQuery = "ALTER TABLE " + tableName + " ";
        for (String key : columns) {
            updateQuery += "ADD COLUMN  IF NOT EXISTS " + key + " " + query.getString(key) + ",";
        }
        connection.query(updateQuery.substring(0, updateQuery.length() - 1), add -> {
            if (add.succeeded()) {
                LOGGER.info("Columns added");
                resultHandler.handle(Future.succeededFuture(new ArrayList<>()));
                connection.close();
            } else {
                LOGGER.info("Add failed");
                resultHandler.handle(Future.failedFuture(add.cause()));
                connection.close();
            }
        });
    }

    /**
     * Method to perform the SQL update on the connection retrieved via createConnection
     *
     * @param result      Connection future produced by createConnection
     * @param queryString SQL String to query
     * @param params      params for the SQL query
     */
    private void handleUpdate(AsyncResult<SQLConnection> result,
                              String queryString,
                              JsonArray params,
                              Handler<AsyncResult<List<JsonObject>>> resultHandler) {

        if (result.failed()) {
            LOGGER.error("Connection Future failed.", result.cause());
            resultHandler.handle(Future.failedFuture(result.cause().toString()));
        } else {
            SQLConnection connection = result.result();
            connection.updateWithParams(queryString, params, query -> {
                if (query.succeeded()) {
                    LOGGER.info("No. of rows updated: " + query.result().getUpdated());
                    resultHandler.handle(Future.succeededFuture(new ArrayList<>()));
                    connection.close();
                } else {
                    LOGGER.error("Update failed.", query.cause());
                    resultHandler.handle(Future.failedFuture(query.cause()));
                    connection.close();
                }
            });
        }
    }

    /**
     * Process the SQL ResultSet (as List<JSONObject>) and reply the results via receivedMessage
     *
     * @param result SQL ResultSet as List<JsonObject>
     */
    private void handleResult(AsyncResult<List<JsonObject>> result, Handler<AsyncResult<List<JsonObject>>> resultHandler) {

        if (result.failed()) {
            LOGGER.error("List<JsonObject> Future failed.", result.cause());
            resultHandler.handle(Future.failedFuture(result.cause().toString()));
        } else {
            resultHandler.handle(Future.succeededFuture(result.result()));
        }
    }


}
