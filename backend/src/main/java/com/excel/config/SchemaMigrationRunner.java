package com.excel.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 启动时幂等表结构迁移：
 * docker-compose 的 schema.sql 仅在 MySQL 数据卷为空时执行；
 * 对已存在的旧库，这里通过 information_schema 判断后补列/建表，避免 ALTER 报错。
 */
@Component
@Order(0)
@ConditionalOnProperty(name = "app.schema.migrate", havingValue = "true", matchIfMissing = true)
public class SchemaMigrationRunner {

    private static final Logger logger = LoggerFactory.getLogger(SchemaMigrationRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        addColumnIfMissing("import_record", "read_count",
                "ALTER TABLE import_record ADD COLUMN read_count INT DEFAULT 0 COMMENT '已读取行数' AFTER total_count");
        addColumnIfMissing("import_record", "validated_count",
                "ALTER TABLE import_record ADD COLUMN validated_count INT DEFAULT 0 COMMENT '已校验行数' AFTER read_count");
        addColumnIfMissing("import_record", "stage",
                "ALTER TABLE import_record ADD COLUMN stage VARCHAR(20) DEFAULT 'PENDING' COMMENT '阶段' AFTER status");
        addColumnIfMissing("import_record", "percent",
                "ALTER TABLE import_record ADD COLUMN percent INT DEFAULT 0 COMMENT '进度百分比' AFTER stage");
        addColumnIfMissing("import_record", "eta_seconds",
                "ALTER TABLE import_record ADD COLUMN eta_seconds BIGINT COMMENT '预计剩余秒数' AFTER percent");
        addColumnIfMissing("import_record", "rows_per_second",
                "ALTER TABLE import_record ADD COLUMN rows_per_second DOUBLE COMMENT '速率行/秒' AFTER eta_seconds");
        addColumnIfMissing("import_record", "file_path",
                "ALTER TABLE import_record ADD COLUMN file_path VARCHAR(500) COMMENT '临时文件路径' AFTER rows_per_second");
        addColumnIfMissing("import_record", "started_at",
                "ALTER TABLE import_record ADD COLUMN started_at DATETIME COMMENT '开始时间' AFTER file_path");
        addColumnIfMissing("import_record", "finished_at",
                "ALTER TABLE import_record ADD COLUMN finished_at DATETIME COMMENT '结束时间' AFTER started_at");

        createRowErrorTableIfMissing();
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT 1 FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    table, column);
            if (rows.isEmpty()) {
                logger.info("[schema迁移] {}.{} 不存在，执行: {}", table, column, ddl);
                jdbcTemplate.execute(ddl);
            }
        } catch (Exception e) {
            logger.error("[schema迁移] 检查/添加列 {}.{} 失败", table, column, e);
            throw e;
        }
    }

    private void createRowErrorTableIfMissing() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'import_row_error'");
            if (rows.isEmpty()) {
                logger.info("[schema迁移] 创建 import_row_error 表");
                jdbcTemplate.execute("""
                        CREATE TABLE import_row_error (
                            id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                            batch_no VARCHAR(50) NOT NULL COMMENT '任务编号',
                            row_index INT COMMENT 'Excel行号',
                            data_code VARCHAR(50) COMMENT '数据编号',
                            name VARCHAR(50) COMMENT '姓名',
                            id_card VARCHAR(20) COMMENT '身份证号',
                            phone VARCHAR(20) COMMENT '手机号',
                            amount VARCHAR(50) COMMENT '金额原始值',
                            address VARCHAR(200) COMMENT '地址',
                            remark VARCHAR(500) COMMENT '备注',
                            error_msg VARCHAR(1000) COMMENT '错误原因',
                            create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                            PRIMARY KEY (id),
                            KEY idx_batch_row (batch_no, row_index)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导入校验错误行表'
                        """);
            }
        } catch (Exception e) {
            logger.error("[schema迁移] 创建 import_row_error 失败", e);
            throw e;
        }
    }
}
