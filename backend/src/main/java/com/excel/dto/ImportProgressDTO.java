package com.excel.dto;

import com.excel.entity.ImportRecord;
import com.excel.task.ImportTaskProgress;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 导入任务进度
 */
@Data
@Builder
public class ImportProgressDTO {

    /** 任务编号（即导入批次号） */
    private String taskId;

    /** PENDING / PROCESSING / COMPLETED / COMPLETED_WITH_ERRORS / FAILED */
    private String status;

    private String fileName;

    private Long fileSize;

    /** 已读取行数 */
    private Long readCount;

    /** 已校验行数 */
    private Long validatedCount;

    /** 校验通过并已入库行数 */
    private Long successCount;

    /** 失败行数 */
    private Long failCount;

    /** 预估总行数（可能为null） */
    private Integer totalEstimate;

    /** 完成百分比（总行数未知时为null） */
    private Integer percent;

    /** 预计剩余秒数（无法估算时为null） */
    private Long etaSeconds;

    /** 已耗时秒数 */
    private Long elapsedSeconds;

    private String message;

    /** 错误预览（最多200条，仅内存态任务提供） */
    private List<ExcelDataDTO> errorPreview;

    private Long startTime;

    private Long finishTime;

    public static ImportProgressDTO from(ImportTaskProgress p) {
        return ImportProgressDTO.builder()
                .taskId(p.getTaskId())
                .status(p.getStatus().name())
                .fileName(p.getFileName())
                .fileSize(p.getFileSize())
                .readCount(p.getReadCount().get())
                .validatedCount(p.getValidatedCount().get())
                .successCount(p.getSuccessCount().get())
                .failCount(p.getFailCount().get())
                .totalEstimate(p.getTotalEstimate())
                .percent(p.getPercent())
                .etaSeconds(p.getEtaSeconds())
                .elapsedSeconds(p.getElapsedSeconds())
                .message(p.getMessage())
                .errorPreview(p.getErrorPreview())
                .startTime(p.getStartTimeMillis())
                .finishTime(p.getFinishTimeMillis())
                .build();
    }

    /**
     * 内存中不存在时（服务重启或任务已清理），用数据库记录构建兜底进度
     */
    public static ImportProgressDTO fromRecord(ImportRecord record) {
        int recordStatus = record.getStatus() == null ? 0 : record.getStatus();
        String status = switch (recordStatus) {
            case 1 -> "COMPLETED";
            case 2 -> "COMPLETED_WITH_ERRORS";
            case 3 -> "FAILED";
            default -> "PROCESSING";
        };
        long success = record.getSuccessCount() == null ? 0 : record.getSuccessCount();
        long fail = record.getFailCount() == null ? 0 : record.getFailCount();
        long read = record.getTotalCount() == null ? 0 : record.getTotalCount();
        boolean finished = recordStatus != 0;

        return ImportProgressDTO.builder()
                .taskId(record.getBatchNo())
                .status(status)
                .fileName(record.getFileName())
                .fileSize(record.getFileSize())
                .readCount(read)
                .validatedCount(success + fail)
                .successCount(success)
                .failCount(fail)
                .totalEstimate(finished ? (int) read : null)
                .percent(finished ? 100 : null)
                .etaSeconds(finished ? 0L : null)
                .message(finished ? "导入已结束" : "任务处理中（进度按批次刷新）")
                .build();
    }
}
