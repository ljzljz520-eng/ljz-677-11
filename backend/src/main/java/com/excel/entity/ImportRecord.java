package com.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("import_record")
public class ImportRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 批次号
     */
    private String batchNo;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 预估总记录数（xlsx可预扫描，xls可能为0）
     */
    private Integer totalCount;

    /**
     * 已读取行数
     */
    private Integer readCount;

    /**
     * 已校验行数
     */
    private Integer validatedCount;

    /**
     * 成功数量
     */
    private Integer successCount;

    /**
     * 失败数量
     */
    private Integer failCount;

    /**
     * 导入状态：0-排队/处理中 1-完成 2-完成但有失败行 3-失败/中断
     */
    private Integer status;

    /**
     * 处理阶段：PENDING/COUNTING/PARSING/SAVING/DONE/FAILED
     */
    private String stage;

    /**
     * 进度百分比 0-100
     */
    private Integer percent;

    /**
     * 预计剩余秒数
     */
    private Long etaSeconds;

    /**
     * 处理速率（行/秒）
     */
    private Double rowsPerSecond;

    /**
     * 服务端临时文件路径
     */
    private String filePath;

    /**
     * 开始处理时间
     */
    private LocalDateTime startedAt;

    /**
     * 结束处理时间
     */
    private LocalDateTime finishedAt;

    /**
     * 错误信息（任务级 / 错误行明细在 import_row_error 表）
     */
    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private String errorDetails;

    /**
     * 操作人ID
     */
    private Long operatorId;

    /**
     * 操作人姓名
     */
    private String operatorName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
