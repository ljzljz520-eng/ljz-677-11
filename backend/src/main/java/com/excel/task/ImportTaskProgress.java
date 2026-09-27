package com.excel.task;

import com.excel.dto.ExcelDataDTO;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 导入任务实时进度（内存态）
 * 计数器使用原子类：解析线程写入，Web查询线程读取
 */
@Getter
public class ImportTaskProgress {

    public enum Status {
        /** 已创建，等待解析 */
        PENDING,
        /** 解析中 */
        PROCESSING,
        /** 全部完成 */
        COMPLETED,
        /** 完成，但存在失败行 */
        COMPLETED_WITH_ERRORS,
        /** 任务失败（文件解析异常等） */
        FAILED
    }

    /** 内存中最多保留的错误预览条数，防止大批量失败时占用过多内存 */
    private static final int MAX_ERROR_PREVIEW = 200;

    private final String taskId;
    private final String fileName;
    private final long fileSize;
    private final long startTimeMillis = System.currentTimeMillis();

    private volatile Status status = Status.PENDING;
    private volatile String message = "任务已创建，等待解析";
    private volatile Long finishTimeMillis;
    /** 预估总数据行数（来自xlsx的dimension信息，可能为null） */
    private volatile Integer totalEstimate;

    /** 已读取行数 */
    private final AtomicLong readCount = new AtomicLong();
    /** 已校验行数 */
    private final AtomicLong validatedCount = new AtomicLong();
    /** 校验通过并已入库行数 */
    private final AtomicLong successCount = new AtomicLong();
    /** 失败行数 */
    private final AtomicLong failCount = new AtomicLong();

    private final ConcurrentLinkedQueue<ExcelDataDTO> errorPreviewQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger errorPreviewSize = new AtomicInteger();

    public ImportTaskProgress(String taskId, String fileName, long fileSize) {
        this.taskId = taskId;
        this.fileName = fileName;
        this.fileSize = fileSize;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public void setTotalEstimate(Integer totalEstimate) {
        this.totalEstimate = totalEstimate;
    }

    /**
     * 添加错误预览，超过上限后丢弃（计数仍继续）
     */
    public void addErrorPreview(ExcelDataDTO error) {
        if (errorPreviewSize.get() >= MAX_ERROR_PREVIEW) {
            return;
        }
        errorPreviewQueue.offer(error);
        errorPreviewSize.incrementAndGet();
    }

    public List<ExcelDataDTO> getErrorPreview() {
        return new ArrayList<>(errorPreviewQueue);
    }

    public boolean isFinished() {
        return status == Status.COMPLETED
                || status == Status.COMPLETED_WITH_ERRORS
                || status == Status.FAILED;
    }

    public void markProcessing() {
        this.status = Status.PROCESSING;
        this.message = "正在解析并校验数据";
    }

    public void markCompleted(boolean hasFailures) {
        this.status = hasFailures ? Status.COMPLETED_WITH_ERRORS : Status.COMPLETED;
        this.finishTimeMillis = System.currentTimeMillis();
        this.message = hasFailures ? "导入完成，存在失败行" : "导入完成";
    }

    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.finishTimeMillis = System.currentTimeMillis();
        this.message = "导入失败: " + reason;
    }

    /**
     * 完成百分比；总行数未知时返回null（前端显示流动条）
     */
    public Integer getPercent() {
        if (isFinished()) {
            return 100;
        }
        Integer total = totalEstimate;
        if (total == null || total <= 0) {
            return null;
        }
        long read = readCount.get();
        return (int) Math.min(99, read * 100 / total);
    }

    /**
     * 预计剩余秒数；无法估算时返回null
     */
    public Long getEtaSeconds() {
        if (isFinished()) {
            return 0L;
        }
        Integer total = totalEstimate;
        long read = readCount.get();
        if (total == null || total <= 0 || read <= 0) {
            return null;
        }
        long elapsedMs = System.currentTimeMillis() - startTimeMillis;
        if (elapsedMs <= 0) {
            return null;
        }
        double rowsPerSecond = read * 1000.0 / elapsedMs;
        if (rowsPerSecond <= 0) {
            return null;
        }
        long remaining = Math.max(0, total - read);
        return Math.round(remaining / rowsPerSecond);
    }

    public long getElapsedSeconds() {
        long end = finishTimeMillis != null ? finishTimeMillis : System.currentTimeMillis();
        return (end - startTimeMillis) / 1000;
    }
}
