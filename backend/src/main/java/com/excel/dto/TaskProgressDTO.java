package com.excel.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 导入任务进度（前端轮询展示）
 */
@Data
@Builder
public class TaskProgressDTO {

    /**
     * 任务编号
     */
    private String taskNo;

    private String fileName;

    private Long fileSize;

    /**
     * 状态码：0-处理中 1-完成 2-完成但有失败 3-失败/中断
     */
    private Integer status;

    /**
     * 阶段：PENDING / COUNTING / PARSING / SAVING / DONE / FAILED
     */
    private String stage;

    /**
     * 进度百分比
     */
    private Integer percent;

    /**
     * 预估总行数（xls 可能为 0，表示未知）
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
     * 成功入库行数
     */
    private Integer successCount;

    /**
     * 失败行数
     */
    private Integer failCount;

    /**
     * 处理速率（行/秒）
     */
    private Double rowsPerSecond;

    /**
     * 预计剩余秒数
     */
    private Long etaSeconds;

    /**
     * 任务级错误信息（失败/中断时）
     */
    private String message;
}
