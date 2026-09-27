package com.excel.listener;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;

import java.util.Map;

/**
 * 只读不存的轻量监听器，用于预扫描总行数（SAX，常量级内存）。
 * 用 Map 承载，避免反射建实体对象的开销。
 */
public class CountingListener implements ReadListener<Map<Integer, Object>> {

    private int total = 0;

    @Override
    public void invoke(Map<Integer, Object> data, AnalysisContext context) {
        // 跳过完全空白的行
        if (data != null && !data.isEmpty()) {
            boolean allBlank = data.values().stream()
                    .allMatch(v -> v == null || v.toString().trim().isEmpty());
            if (!allBlank) {
                total++;
            }
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // no-op
    }

    public int getTotal() {
        return total;
    }
}
