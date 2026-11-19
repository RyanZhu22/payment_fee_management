package com.omsweb.fees.operations;

import java.util.List;

public record ImportResult(int total, int success, int failed, List<Row> rows) {
    static ImportResult from(List<Row> rows) {
        int successful = (int) rows.stream().filter(Row::success).count();
        return new ImportResult(rows.size(), successful, rows.size() - successful, List.copyOf(rows));
    }

    public record Row(int row, String reference, boolean success, String message) {
    }
}
