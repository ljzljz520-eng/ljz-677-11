package com.excel.service;

import cn.hutool.core.util.IdUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.excel.config.AsyncConfig;
import com.excel.dto.ExcelDataDTO;
import com.excel.dto.TaskProgressDTO;
import com.excel.entity.ExcelData;
import com.excel.entity.ImportRecord;
import com.excel.entity.ImportRowError;
import com.excel.entity.User;
import com.excel.listener.ExcelDataListener;
import com.excel.mapper.ExcelDataMapper;
import com.excel.mapper.ImportRecordMapper;
import com.excel.mapper.ImportRowErrorMapper;
import com.excel.mapper.UserMapper;
import com.excel.utils.XlsxRowCounter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;

@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private static final Logger logger = LoggerFactory.getLogger(ExcelImportService.class);

    /** 任务状态：0-处理中 1-完成 2-完成但有失败行 3-失败/中断 */
    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_DONE = 1;
    public static final int STATUS_DONE_WITH_ERRORS = 2;
    public static final int STATUS_FAILED = 3;

    /** 进度落库的最小间隔（毫秒），5 万行最多产生约几十次 update */
    private static final long PROGRESS_FLUSH_INTERVAL_MS = 500L;

    private final ExcelDataMapper excelDataMapper;
    private final ImportRecordMapper importRecordMapper;
    private final ImportRowErrorMapper importRowErrorMapper;
    private final UserMapper userMapper;
    private final XlsxRowCounter xlsxRowCounter;

    @Qualifier(AsyncConfig.IMPORT_EXECUTOR)
    private final ExecutorService importExecutor;

    @Value("${app.import.temp-dir:./import-temp}")
    private String tempDir;

    // ============================== 创建任务（上传） ==============================

    /**
     * 上传文件：落盘 + 创建任务记录，立即返回任务编号，不解析任何数据。
     */
    public String createTask(MultipartFile file, Long operatorId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null
                || (!fileName.toLowerCase().endsWith(".xlsx") && !fileName.toLowerCase().endsWith(".xls"))) {
            throw new IllegalArgumentException("仅支持Excel文件（.xlsx或.xls）");
        }

        String taskNo = IdUtil.fastSimpleUUID();

        // 先落盘到受控临时目录：解析由异步线程从磁盘流式读取，请求结束后文件仍可用
        Path dir = Paths.get(tempDir).toAbsolutePath();
        Files.createDirectories(dir);
        String suffix = fileName.toLowerCase().endsWith(".xlsx") ? ".xlsx" : ".xls";
        Path localFile = dir.resolve(taskNo + suffix);
        file.transferTo(localFile.toFile());

        User operator = operatorId == null ? null : userMapper.selectById(operatorId);
        ImportRecord record = new ImportRecord();
        record.setBatchNo(taskNo);
        record.setFileName(StringUtils.hasText(fileName) ? fileName : localFile.getFileName().toString());
        record.setFileSize(file.getSize());
        record.setStatus(STATUS_RUNNING);
        record.setStage("PENDING");
        record.setPercent(0);
        record.setTotalCount(0);
        record.setReadCount(0);
        record.setValidatedCount(0);
        record.setSuccessCount(0);
        record.setFailCount(0);
        record.setFilePath(localFile.toString());
        record.setOperatorId(operatorId);
        record.setOperatorName(operator != null ? operator.getRealName() : "系统");
        importRecordMapper.insert(record);

        logger.info("导入任务已创建: taskNo={}, file={}, size={}", taskNo, fileName, file.getSize());
        return taskNo;
    }

    // ============================== 异步执行 ==============================

    /**
     * 执行任务（由 Controller 或启动恢复提交到导入线程池）。
     * 无大事务：每批独立提交，任务中断时已入库的数据仍然可见。
     */
    public void runTask(String taskNo) {
        ImportRecord record = importRecordMapper.selectOne(
                new LambdaQueryWrapper<ImportRecord>().eq(ImportRecord::getBatchNo, taskNo));
        if (record == null) {
            logger.warn("任务不存在: {}", taskNo);
            return;
        }

        Path localFile = Paths.get(record.getFilePath());
        record.setStartedAt(LocalDateTime.now());
        importRecordMapper.updateById(record);

        try {
            if (!Files.exists(localFile)) {
                throw new IllegalStateException("服务器上的上传文件已不存在，任务无法继续");
            }

            // 1) 预扫描总行数（仅 xlsx 支持）；总行数已知才能给百分比和 ETA
            String fileName = record.getFileName();
            boolean xlsx = fileName != null && fileName.toLowerCase().endsWith(".xlsx");
            if (xlsx) {
                updateStage(record, "COUNTING", 1, null, null, null, null, null, null, null, null);
                Integer estimated = xlsxRowCounter.countDataRows(localFile);
                if (estimated != null) {
                    record.setTotalCount(estimated);
                    persistProgress(record);
                }
            }

            // 恢复场景：清掉上一次残留数据，保证幂等
            excelDataMapper.delete(new LambdaQueryWrapper<ExcelData>()
                    .eq(ExcelData::getBatchNo, taskNo));
            importRowErrorMapper.deleteByBatch(taskNo);
            record.setReadCount(0);
            record.setValidatedCount(0);
            record.setSuccessCount(0);
            record.setFailCount(0);
            updateStage(record, "PARSING", record.getPercent(), null, null, null, null, null, null, null, null);

            ExcelTypeEnum excelType = xlsx ? ExcelTypeEnum.XLSX : ExcelTypeEnum.XLS;

            // 进度游标：计数 + 时间窗口节流，避免 5 万次 update
            final int total = record.getTotalCount() == null ? 0 : record.getTotalCount();
            final long startMs = System.currentTimeMillis();
            final int[] lastFlushed = {0};
            final long[] lastFlushMs = {0L};

            ExcelDataListener listener = new ExcelDataListener(
                    excelDataMapper, importRowErrorMapper, taskNo,
                    readCount -> {
                        long now = System.currentTimeMillis();
                        // 首批立即刷一次，之后按时间窗口（约500ms）刷
                        if (readCount - lastFlushed[0] >= 1
                                && (readCount <= 100 || now - lastFlushMs[0] >= PROGRESS_FLUSH_INTERVAL_MS)) {
                            lastFlushed[0] = readCount;
                            lastFlushMs[0] = now;
                            applyProgress(record, readCount, total, startMs, "PARSING");
                            persistProgress(record);
                        }
                    });

            // SAX 流式读取：监听器逐行回调，内存中始终只有一批（1000行）
            EasyExcel.read(Files.newInputStream(localFile), ExcelDataDTO.class, listener)
                    .excelType(excelType)
                    .charset(StandardCharsets.UTF_8)
                    .sheet()
                    .headRowNumber(1)
                    .doRead();

            // 2) 收尾
            record.setTotalCount(listener.getTotalCount());
            record.setReadCount(listener.getTotalCount());
            record.setValidatedCount(listener.getTotalCount());
            record.setSuccessCount(listener.getSuccessCount());
            record.setFailCount(listener.getFailCount());
            boolean hasErrors = listener.getFailCount() > 0;
            record.setStatus(hasErrors ? STATUS_DONE_WITH_ERRORS : STATUS_DONE);
            record.setStage("DONE");
            record.setPercent(100);
            record.setEtaSeconds(0L);
            record.setFinishedAt(LocalDateTime.now());
            importRecordMapper.updateById(record);

            logger.info("导入任务完成: taskNo={}, 成功={}, 失败={}", taskNo,
                    listener.getSuccessCount(), listener.getFailCount());

        } catch (Exception e) {
            logger.error("导入任务失败: taskNo={}", taskNo, e);
            record.setStatus(STATUS_FAILED);
            record.setStage("FAILED");
            record.setFinishedAt(LocalDateTime.now());
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            record.setErrorDetails(msg.length() > 2000 ? msg.substring(0, 2000) : msg);
            importRecordMapper.updateById(record);
        } finally {
            // 无论成功失败都删除临时文件，避免磁盘堆积（结果数据/错误行均已入库）
            try {
                if (Files.deleteIfExists(localFile)) {
                    // 文件已落库处理完，路径清空，重启恢复时不会再误判为可续跑
                    record.setFilePath(null);
                    importRecordMapper.updateById(record);
                }
            } catch (IOException e) {
                logger.warn("临时文件删除失败: {}", localFile);
            }
        }
    }

    /**
     * 用当前已读行数计算百分比、速率、ETA 并写入游标记录（不落库）
     */
    private void applyProgress(ImportRecord record, int read, int total,
                               long startMs, String stage) {
        record.setReadCount(read);
        record.setValidatedCount(read);
        record.setStage(stage);

        double elapsedSec = Math.max((System.currentTimeMillis() - startMs) / 1000.0, 0.001);
        double rps = read / elapsedSec;
        record.setRowsPerSecond(Math.round(rps * 10) / 10.0d);

        if (total > 0) {
            int percent = (int) Math.min(99, (long) read * 100 / total);
            record.setPercent(percent);
            int remaining = total - read;
            Long eta = rps <= 0 ? null : (long) Math.round(remaining / rps);
            record.setEtaSeconds(eta);
        } else {
            // 总行数未知（如 xls）：进度不封顶，ETA 无法估算
            record.setPercent(0);
            record.setEtaSeconds(null);
        }
    }

    private void persistProgress(ImportRecord record) {
        importRecordMapper.updateById(record);
    }

    @SuppressWarnings("SameParameterValue")
    private void updateStage(ImportRecord record, String stage, Integer percent,
                             Integer total, Integer read, Integer validated,
                             Integer success, Integer fail, Double rps,
                             Long eta, String error) {
        record.setStage(stage);
        if (percent != null) record.setPercent(percent);
        if (total != null) record.setTotalCount(total);
        if (read != null) record.setReadCount(read);
        if (validated != null) record.setValidatedCount(validated);
        if (success != null) record.setSuccessCount(success);
        if (fail != null) record.setFailCount(fail);
        if (rps != null) record.setRowsPerSecond(rps);
        if (eta != null) record.setEtaSeconds(eta);
        if (error != null) record.setErrorDetails(error);
        importRecordMapper.updateById(record);
    }

    // ============================== 查询 ==============================

    /**
     * 查询任务进度（页面重开后凭任务编号继续查看）
     */
    public TaskProgressDTO getProgress(String taskNo) {
        ImportRecord r = importRecordMapper.selectOne(
                new LambdaQueryWrapper<ImportRecord>().eq(ImportRecord::getBatchNo, taskNo));
        if (r == null) {
            return null;
        }
        return TaskProgressDTO.builder()
                .taskNo(r.getBatchNo())
                .fileName(r.getFileName())
                .fileSize(r.getFileSize())
                .status(r.getStatus())
                .stage(r.getStage())
                .percent(r.getPercent())
                .totalCount(nz(r.getTotalCount()))
                .readCount(nz(r.getReadCount()))
                .validatedCount(nz(r.getValidatedCount()))
                .successCount(nz(r.getSuccessCount()))
                .failCount(nz(r.getFailCount()))
                .rowsPerSecond(r.getRowsPerSecond())
                .etaSeconds(r.getEtaSeconds())
                .message(r.getStatus() != null && r.getStatus() == STATUS_FAILED ? r.getErrorDetails() : null)
                .build();
    }

    /**
     * 分页查询失败行
     */
    public Page<ImportRowError> getErrors(String taskNo, int pageNum, int pageSize) {
        return importRowErrorMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<ImportRowError>()
                        .eq(ImportRowError::getBatchNo, taskNo)
                        .orderByAsc(ImportRowError::getRowIndex));
    }

    /**
     * 查询全部失败行（导出用，分批读出，单次导出上限 5 万）
     */
    public List<ImportRowError> getAllErrors(String taskNo) {
        return importRowErrorMapper.selectList(new LambdaQueryWrapper<ImportRowError>()
                .eq(ImportRowError::getBatchNo, taskNo)
                .orderByAsc(ImportRowError::getRowIndex)
                .last("limit 50000"));
    }

    public long countErrors(String taskNo) {
        return importRowErrorMapper.selectCount(new LambdaQueryWrapper<ImportRowError>()
                .eq(ImportRowError::getBatchNo, taskNo));
    }

    private Integer nz(Integer v) {
        return v == null ? 0 : v;
    }

    // ============================== 历史记录 / 批次数据（保留原有能力） ==============================

    public Page<ImportRecord> getImportRecords(Integer pageNum, Integer pageSize) {
        Page<ImportRecord> page = new Page<>(pageNum, pageSize);
        return importRecordMapper.selectPage(page,
                new LambdaQueryWrapper<ImportRecord>()
                        .orderByDesc(ImportRecord::getCreateTime));
    }

    public Page<ExcelData> getDataByBatch(String batchNo, Integer pageNum, Integer pageSize) {
        Page<ExcelData> page = new Page<>(pageNum, pageSize);
        return excelDataMapper.selectPage(page,
                new LambdaQueryWrapper<ExcelData>()
                        .eq(ExcelData::getBatchNo, batchNo)
                        .orderByAsc(ExcelData::getId));
    }

    public List<ExcelData> getPendingReportData(String batchNo) {
        return excelDataMapper.selectByBatchAndStatus(batchNo, 0);
    }

    /**
     * 服务启动时恢复：处理中但临时文件还在的任务重新入队；文件已丢失的标记为中断失败。
     */
    public int recoverInterruptedTasks() {
        List<ImportRecord> running = importRecordMapper.selectList(
                new LambdaQueryWrapper<ImportRecord>().eq(ImportRecord::getStatus, STATUS_RUNNING));
        int recovered = 0;
        for (ImportRecord r : running) {
            boolean fileExists = r.getFilePath() != null && Files.exists(Paths.get(r.getFilePath()));
            if (fileExists) {
                logger.info("恢复未完成的导入任务: {}", r.getBatchNo());
                recovered++;
                // 提交到导入线程池，不阻塞启动流程
                importExecutor.execute(() -> runTask(r.getBatchNo()));
            } else {
                r.setStatus(STATUS_FAILED);
                r.setStage("FAILED");
                r.setErrorDetails("服务重启导致任务中断，且临时文件已失效，请重新上传");
                r.setFinishedAt(LocalDateTime.now());
                importRecordMapper.updateById(r);
            }
        }
        return recovered;
    }
}
