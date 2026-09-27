package com.excel.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 启动时幂等补齐异步导入所需的列与表。
 * 全新库由 schema.sql 初始化；历史库（MySQL卷已存在、init脚本不再执行）由此处平滑升级。
 */
@Component
@Order(0)
@RequiredArgsConstructor
public class SchemaMigrationRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(SchemaMigrationRunner.class);

    private final JdbcTemplate jdbcTemplate;

    /** 列名 -> 列定义 */
    private static final Map<String, String> IMPORT_RECORD_COLUMNS = Map.of(
            "file_path", "VARCHAR(500) COMMENT '服务器临时存储路径'",
            "read_count", "INT DEFAULT 0 COMMENT '已读取行数'",
            "processed_count", "INT DEFAULT 0 COMMENT '已校验行数'",
            "phase", "VARCHAR(20) DEFAULT 'PARSING' COMMENT '处理阶段'",
            "started_at", "DATETIME COMMENT '开始处理时间'"
    );

    @Override
    public void run(ApplicationArguments args) {
        migrateImportRecord();
        ensureErrorRowTable();
    }

    private void migrateImportRecord() {
        try {
            List<String> existing = jdbcTemplate.queryForList(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'import_record'",
                    String.class);
            IMPORT_RECORD_COLUMNS.forEach((column, definition) -> {
                if (!existing.contains(column)) {
                    jdbcTemplate.execute("ALTER TABLE import_record ADD COLUMN " + column + " " + definition);
                    logger.info("import_record 已补齐列: {}", column);
                }
            });
        } catch (Exception e) {
            logger.warn("import_record 迁移检查失败: {}", e.getMessage());
        }
    }

    private void ensureErrorRowTable() {
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS `import_error_row` (" +
                            "`id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID'," +
                            "`batch_no` VARCHAR(50) NOT NULL COMMENT '任务/批次号'," +
                            "`row_index` INT COMMENT 'Excel行号'," +
                            "`data_code` VARCHAR(50) COMMENT '数据编号'," +
                            "`name` VARCHAR(50) COMMENT '姓名'," +
                            "`id_card` VARCHAR(20) COMMENT '身份证号'," +
                            "`phone` VARCHAR(20) COMMENT '手机号'," +
                            "`amount` VARCHAR(50) COMMENT '金额原文'," +
                            "`address` VARCHAR(200) COMMENT '地址'," +
                            "`remark` VARCHAR(500) COMMENT '备注'," +
                            "`error_msg` VARCHAR(1000) COMMENT '错误原因'," +
                            "`create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'," +
                            "`deleted` TINYINT DEFAULT 0 COMMENT '是否删除：0-否 1-是'," +
                            "PRIMARY KEY (`id`)," +
                            "KEY `idx_batch_no` (`batch_no`)" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导入失败行表'");
        } catch (Exception e) {
            logger.warn("import_error_row 建表失败: {}", e.getMessage());
        }
    }
}
