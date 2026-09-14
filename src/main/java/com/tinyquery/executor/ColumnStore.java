package com.tinyquery.executor;

import java.util.*;

public class ColumnStore {

    private final Map<String, List<Object>> columns = new LinkedHashMap<>();
    private final List<String> columnNames = new ArrayList<>();
    private final Set<String> columnNameSet = new HashSet<>();

    public void addRow(Map<String, Object> row) {
        int rowIndex = columns.isEmpty() ? 0 : columns.values().iterator().next().size();

        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String colName = entry.getKey();
            Object value = entry.getValue();

            List<Object> col = columns.computeIfAbsent(colName, k -> {
                List<Object> created = new ArrayList<>();
                for (int i = 0; i < rowIndex; i++) {
                    created.add(null);
                }
                return created;
            });
            col.add(value);

            if (columnNameSet.add(colName)) {
                columnNames.add(colName);
            }
        }

        for (List<Object> col : columns.values()) {
            while (col.size() <= rowIndex) {
                col.add(null);
            }
        }
    }

    public List<Object> getColumns(String columnName) {
        List<Object> col = columns.get(columnName);
        if (col == null) {
            throw new IllegalArgumentException("error:Unknown column:" + columnName);
        }
        return col;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public int getColumnIndex() {
        if (columnNames.isEmpty()) return 0;
        return columns.get(columnNames.get(0)).size();
    }
}
