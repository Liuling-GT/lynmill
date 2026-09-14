package com.tinyquery.api;

import com.tinyquery.executor.ColumnStore;
import com.tinyquery.executor.QueryEngine;
import com.tinyquery.executor.QueryResult;
import com.tinyquery.parser.QueryStatement;
import com.tinyquery.parser.SQLParser;
import com.tinyquery.datasource.DataSourceManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Query API", description = "Execute SQL Query")
@RestController
@RequestMapping("/api")
public class QueryController {

    private final DataSourceManager manager;
    private final SQLParser parser = new SQLParser();

    public QueryController(DataSourceManager manager) {
        this.manager = manager;
    }

    @Operation(summary = "Execute SQL Query", description = "Pass in a SQL statement and return the query resul")
    @PostMapping("/query")
    public QueryResponse query(@RequestBody QueryRequest request) {
        try {
            long start = System.currentTimeMillis();

            QueryStatement stmt = parser.parse(request.sql());

            // 表名到数据源的映射交给 Manager（原先在此处硬编码 .replace(".csv", "")），
            // 现在 "huge" 与 "huge.csv" 都能命中同一数据源
            ColumnStore store = manager.getByTableName(stmt.tableName());
            QueryEngine engine = new QueryEngine(store);

            QueryResult result = engine.execute(stmt);

            long elapsed = System.currentTimeMillis() - start;

            return new QueryResponse(0, "success", result.rows(),
                    result.truncated(), result.totalRows(), elapsed);
        } catch (Exception e) {
            return new QueryResponse(1, e.getMessage(), null, false, 0, 0);
        }
    }
}