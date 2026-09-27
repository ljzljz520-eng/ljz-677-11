package com.excel.service;

import cn.hutool.core.util.IdUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.excel.dto.ImportProgressDTO;
import com.excel.dto.ErrorRowExportDTO;
import com.excel.dto.ExcelDataDTO;
import com.excel.entity.ExcelData;
import com.excel.entity.ImportErrorRow;
import com.excel.entity.ImportRecord;
import com.excel.entity.User;
import com.excel.listener.ChunkingExcelListener;
import com.excel.listener.CountingListener;
import com.excel.mapper.ExcelDataMapper;
import com.excel.mapper.ImportErrorRowMapper;
import com.excel.mapper.ImportRecordMapper;
import com.excel.mapper.UserMapper;
import com.excel.utils.ValidationUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import cn.hutool.core.bean.BeanUtil;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步分批导入服务
 *
 * 内存模型（5万行不整表入内存）：
 *   SAX解析线程 --每1000行--> 有界队列(容量4，约4000行) --> 校验入库线程
 *   队列满时解析线程阻塞（背压）；失败行每1000行批量落库，不在内存累积。
 *
 * 进度模型：
 *   每个批次处理完即更新 import_record，前端凭 batchNo 轮询；
 *   进度全部来自数据库，页面重新打开、后端重启后均可继续查看/恢复。
 */
@Service
@RequiredArgsConstructor
public class AsyncImportService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncImportService.class);

    /** 解析/入库之间的队列容量（块数），每块1000行 */
    private static final int QUEUE_CAPACITY = 4;
    /** 队列投递/拉取超时，防止异常情况下永久挂死 */
    private static final long QUEUE_TIMEOUT_MS = 300_000L;
    private static final int ERROR_BATCH = 1000;

    private final ExcelDataMapper excelDataMapper;
    private final ImportRecordMapper importRecordMapper;
    private final ImportErrorRowMapper importErrorRowMapper;
    private final UserMapper userMapper;

    @Qualifier("importJobExecutor")
    private final ThreadPoolTaskExecutor jobExecutor;
    @Qualifier("importWorkerExecutor")
    private final ThreadPoolTaskExecutor workerExecutor;

    @Value("${import.storage-dir:./import-files}")
    private String storageDir;

    private Path storagePath;

    @PostConstruct
    public void init() throws Exception {
        storagePath = Paths.get(storageDir).toAbsolutePath();
        Files.createDirectories(storagePath);
        logger.info("导入临时文件目录: {}", storagePath);
    }

    // ============================ 任务创建 ============================

    /**
     * 创建导入任务：落盘文件 + 建任务记录 + 提交异步解析，立即返回任务编号
     */
    public String createTask(MultipartFile file, Long operatorId) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || (!fileName.toLowerCase().endsWith(".xlsx") && !fileName.toLowerCase().endsWith(".xls"))) {
            throw new IllegalArgumentException("仅支持Excel文件（.xlsx或.xls）");
        }

        String batchNo = IdUtil.fastSimpleUUID();
        String suffix = fileName.toLowerCase().endsWith(".xlsx") ? ".xlsx" : ".xls";
        File saved = storagePath.resolve(batchNo + suffix).toFile();
        // MultipartFile 请求结束后临时文件会被清理，必须先拷贝到自有目录
        file.transferTo(saved);

        User operator = userMapper.selectById(operatorId);
        String operatorName = operator != null ? operator.getRealName() : "系统";

        ImportRecord record = new ImportRecord();
        record.setBatchNo(batchNo);
        record.setFileName(fileName);
        record.setFileSize(file.getSize());
        record.setFilePath(saved.getAbsolutePath());
        record.setTotalCount(0);
        record.setReadCount(0);
        record.setProcessedCount(0);
        record.setSuccessCount(0);
        record.setFailCount(0);
        record.setStatus(0);
        record.setPhase("COUNTING");
        record.setOperatorId(operatorId);
        record.setOperatorName(operatorName);
        record.setStartedAt(LocalDateTime.now());
        importRecordMapper.insert(record);

        submitJob(batchNo);
        logger.info("导入任务已创建: batchNo={}, file={}", batchNo, fileName);
        return batchNo;
    }

    private void submitJob(String batchNo) {
        try {
            jobExecutor.submit(() -> runImport(batchNo));
        } catch (Exception e) {
            markFatal(batchNo, "任务提交失败（导入队列繁忙，请稍后重试）: " + e.getMessage());
            throw new RuntimeException("当前导入任务较多，请稍后再试", e);
        }
    }

    // ============================ 异步主流程 ============================

    public void runImport(String batchNo) {
        ImportRecord record = importRecordMapper.selectOne(
                new LambdaQueryWrapper<ImportRecord>().eq(ImportRecord::getBatchNo, batchNo));
        if (record == null) {
            logger.warn("任务不存在: {}", batchNo);
            return;
        }
        File file = new File(record.getFilePath());
        Future<?> workerFuture = null;
        try {
            // ---------- 阶段1：预扫描总行数（SAX，常量内存），用于百分比与ETA ----------
            updatePhase(batchNo, "COUNTING", null);
            int totalRows = countRows(file);
            importRecordMapper.update(null, new LambdaUpdateWrapper<ImportRecord>()
                    .eq(ImportRecord::getBatchNo, batchNo)
                    .set(ImportRecord::getTotalCount, totalRows));
            logger.info("任务{} 预扫描完成，共{}行", batchNo, totalRows);

            if (totalRows == 0) {
                finish(batchNo, 0, 0, 0, 0, 0, 1);
                safeDelete(file);
                return;
            }

            // ---------- 阶段2：分块解析 + 校验入库（有界队列背压） ----------
            updatePhase(batchNo, "PARSING", null);
            BlockingQueue<List<ExcelDataDTO>> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
            AtomicInteger readCounter = new AtomicInteger(0);
            WorkerResult result = new WorkerResult();

            workerFuture = workerExecutor.submit(() -> consume(batchNo, queue, result));

            ChunkingExcelListener listener =
                    new ChunkingExcelListener(queue, QUEUE_TIMEOUT_MS, flushed -> {
                        int read = readCounter.addAndGet(flushed);
                        // 已读取行数落库（每1000行更新一次）
                        importRecordMapper.update(null, new LambdaUpdateWrapper<ImportRecord>()
                                .eq(ImportRecord::getBatchNo, batchNo)
                                .set(ImportRecord::getReadCount, read));
                    });

            try {
                EasyExcel.read(file, ExcelDataDTO.class, listener)
                        .excelType(resolveExcelType(file.getName()))
                        .sheet()
                        .headRowNumber(1)
                        .doRead();
            } catch (Exception parseEx) {
                // 解析失败：取消消费端并标记任务失败
                if (workerFuture != null) {
                    workerFuture.cancel(true);
                }
                throw parseEx;
            }

            // 等待校验入库线程把剩余块消费完
            workerFuture.get();

            int finalRead = listener.getReadCount();
            finish(batchNo, totalRows, finalRead,
                    result.processed.get(), result.success.get(), result.fail.get(),
                    result.fail.get() > 0 ? 2 : 1);
            safeDelete(file);
            logger.info("导入任务完成 {}: 总行{} 已校验{} 成功{} 失败{}",
                    batchNo, totalRows, result.processed.get(), result.success.get(), result.fail.get());
        } catch (Exception e) {
            logger.error("导入任务失败: {}", batchNo, e);
            if (workerFuture != null) {
                workerFuture.cancel(true);
            }
            markFatal(batchNo, "导入失败: " + rootMessage(e));
        }
    }

    /**
     * 消费线程：从队列取块 → 校验 → 批量入库/失败行落库 → 更新进度
     */
    private void consume(String batchNo,
                         BlockingQueue<List<ExcelDataDTO>> queue,
                         WorkerResult result) {
        List<ExcelData> validBuffer = new ArrayList<>(ChunkingExcelListener.BATCH_COUNT);
        List<ImportErrorRow> errorBuffer = new ArrayList<>(ERROR_BATCH);
        try {
            while (true) {
                List<ExcelDataDTO> chunk = queue.poll(QUEUE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    throw new RuntimeException("等待解析数据超时，任务中止");
                }
                if (chunk == ChunkingExcelListener.POISON) {
                    flushValid(batchNo, validBuffer, result);
                    flushErrors(batchNo, errorBuffer);
                    persistProgress(batchNo, result);
                    return;
                }

                for (ExcelDataDTO dto : chunk) {
                    result.processed.incrementAndGet();
                    String errorMsg = ValidationUtils.validate(dto);
                    if (errorMsg != null) {
                        result.fail.incrementAndGet();
                        errorBuffer.add(toErrorRow(batchNo, dto, errorMsg));
                        if (errorBuffer.size() >= ERROR_BATCH) {
                            flushErrors(batchNo, errorBuffer);
                        }
                    } else {
                        result.success.incrementAndGet();
                        ExcelData entity = new ExcelData();
                        BeanUtil.copyProperties(dto, entity, "amount");
                        entity.setAmount(dto.getAmountAsDecimal());
                        entity.setBatchNo(batchNo);
                        entity.setReportStatus(0);
                        validBuffer.add(entity);
                        if (validBuffer.size() >= ChunkingExcelListener.BATCH_COUNT) {
                            flushValid(batchNo, validBuffer, result);
                        }
                    }
                }
                // 每个数据块消费完落一次进度（前端看到的"已校验"持续前进）
                persistProgress(batchNo, result);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("消费线程被中断: {}", batchNo);
        } catch (Exception e) {
            throw new RuntimeException("校验入库失败: " + e.getMessage(), e);
        }
    }

    // ============================ 批量落库 ============================

    /**
     * 批量插入有效数据；整批失败时退化为逐条插入，隔离个别坏数据，
     * 坏数据计入失败行而不是让整个任务崩掉。
     */
    private void flushValid(String batchNo, List<ExcelData> buffer, WorkerResult result) {
        if (buffer.isEmpty()) {
            return;
        }
        List<ExcelData> batch = new ArrayList<>(buffer);
        buffer.clear();
        try {
            excelDataMapper.batchInsert(batch);
        } catch (Exception bulkEx) {
            logger.warn("批量插入失败，退化为逐条插入, batchNo={}, size={}", batchNo, batch.size());
            for (ExcelData data : batch) {
                try {
                    excelDataMapper.insert(data);
                } catch (Exception rowEx) {
                    // 校验通过但落库失败：从成功挪到失败
                    result.success.decrementAndGet();
                    result.fail.incrementAndGet();
                    ImportErrorRow errorRow = new ImportErrorRow();
                    errorRow.setBatchNo(batchNo);
                    errorRow.setDataCode(truncate(data.getDataCode(), 50));
                    errorRow.setName(truncate(data.getName(), 50));
                    errorRow.setIdCard(truncate(data.getIdCard(), 20));
                    errorRow.setPhone(truncate(data.getPhone(), 20));
                    errorRow.setAmount(data.getAmount() == null ? null : data.getAmount().toPlainString());
                    errorRow.setAddress(truncate(data.getAddress(), 200));
                    errorRow.setRemark(truncate(data.getRemark(), 500));
                    errorRow.setErrorMsg(truncate("数据库保存失败: " + rootMessage(rowEx), 900));
                    saveErrorRowSafely(errorRow);
                }
            }
        }
    }

    private void flushErrors(String batchNo, List<ImportErrorRow> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        List<ImportErrorRow> batch = new ArrayList<>(buffer);
        buffer.clear();
        try {
            importErrorRowMapper.batchInsert(batch);
        } catch (Exception bulkEx) {
            logger.warn("失败行批量插入失败，退化为逐条, batchNo={}", batchNo);
            batch.forEach(this::saveErrorRowSafely);
        }
    }

    private void saveErrorRowSafely(ImportErrorRow row) {
        try {
            importErrorRowMapper.insert(row);
        } catch (Exception ex) {
            logger.error("失败行落库异常, batchNo={}, row={}", row.getBatchNo(), row.getRowIndex(), ex);
        }
    }

    // ============================ 进度与状态 ============================

    private void persistProgress(String batchNo, WorkerResult result) {
        importRecordMapper.update(null, new LambdaUpdateWrapper<ImportRecord>()
                .eq(ImportRecord::getBatchNo, batchNo)
                .set(ImportRecord::getProcessedCount, result.processed.get())
                .set(ImportRecord::getSuccessCount, result.success.get())
                .set(ImportRecord::getFailCount, result.fail.get()));
    }

    private void finish(String batchNo, int total, int read, int processed, int success, int fail, int status) {
        importRecordMapper.update(null, new LambdaUpdateWrapper<ImportRecord>()
                .eq(ImportRecord::getBatchNo, batchNo)
                .set(ImportRecord::getTotalCount, total)
                .set(ImportRecord::getReadCount, read)
                .set(ImportRecord::getProcessedCount, processed)
                .set(ImportRecord::getSuccessCount, success)
                .set(ImportRecord::getFailCount, fail)
                .set(ImportRecord::getStatus, status)
                .set(ImportRecord::getPhase, "DONE")
                .set(ImportRecord::getErrorDetails, null));
    }

    private void markFatal(String batchNo, String message) {
        importRecordMapper.update(null, new LambdaUpdateWrapper<ImportRecord>()
                .eq(ImportRecord::getBatchNo, batchNo)
                .set(ImportRecord::getStatus, 3)
                .set(ImportRecord::getPhase, "FAILED")
                .set(ImportRecord::getErrorDetails, truncate(message, 900)));
    }

    private void updatePhase(String batchNo, String phase, Integer status) {
        LambdaUpdateWrapper<ImportRecord> uw = new LambdaUpdateWrapper<ImportRecord>()
                .eq(ImportRecord::getBatchNo, batchNo)
                .set(ImportRecord::getPhase, phase);
        if (status != null) {
            uw.set(ImportRecord::getStatus, status);
        }
        importRecordMapper.update(null, uw);
    }

    /**
     * 查询进度：数据全部来自数据库
     */
    public ImportProgressDTO getProgress(String batchNo) {
        ImportRecord r = requireRecord(batchNo);
        int total = nz(r.getTotalCount());
        int processed = nz(r.getProcessedCount());

        Integer progress = -1;
        Long etaSeconds = null;
        Double rate = null;
        if (total > 0 && (r.getStatus() == 0 || r.getStatus() == 1 || r.getStatus() == 2)) {
            progress = (int) Math.min(100, Math.round(processed * 100.0 / total));
        }
        if (r.getStartedAt() != null && processed > 0 && r.getStatus() == 0) {
            long elapsedMs = Duration.between(r.getStartedAt(), LocalDateTime.now()).toMillis();
            if (elapsedMs > 500) {
                rate = processed * 1000.0 / elapsedMs;
                int remaining = Math.max(0, total - processed);
                etaSeconds = Math.round(remaining / rate);
            }
        }

        return ImportProgressDTO.builder()
                .batchNo(batchNo)
                .fileName(r.getFileName())
                .status(r.getStatus())
                .phase(r.getPhase())
                .totalCount(total)
                .readCount(nz(r.getReadCount()))
                .processedCount(processed)
                .successCount(nz(r.getSuccessCount()))
                .failCount(nz(r.getFailCount()))
                .progress(progress)
                .etaSeconds(etaSeconds)
                .rowsPerSecond(rate)
                .errorMessage(r.getErrorDetails())
                .build();
    }

    // ============================ 失败行查询/导出 ============================

    public Page<ImportErrorRow> getErrorRows(String batchNo, Integer pageNum, Integer pageSize) {
        requireRecord(batchNo);
        return importErrorRowMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<ImportErrorRow>()
                        .eq(ImportErrorRow::getBatchNo, batchNo)
                        .orderByAsc(ImportErrorRow::getRowIndex));
    }

    /**
     * 流式导出失败行：分页读取、分批写出，不把全部失败行加载进内存
     */
    public void writeErrorExcel(String batchNo, OutputStream out) {
        requireRecord(batchNo);
        int pageSize = 2000;
        int pageNum = 1;
        try (ExcelWriter writer = EasyExcel.write(out, ErrorRowExportDTO.class).build()) {
            WriteSheet sheet = EasyExcel.writerSheet(0, "校验失败数据").build();
            while (true) {
                Page<ImportErrorRow> page = importErrorRowMapper.selectPage(new Page<>(pageNum, pageSize),
                        new LambdaQueryWrapper<ImportErrorRow>()
                                .eq(ImportErrorRow::getBatchNo, batchNo)
                                .orderByAsc(ImportErrorRow::getRowIndex));
                if (page.getRecords().isEmpty()) {
                    break;
                }
                List<ErrorRowExportDTO> rows = new ArrayList<>(page.getRecords().size());
                for (ImportErrorRow e : page.getRecords()) {
                    ErrorRowExportDTO dto = new ErrorRowExportDTO();
                    BeanUtil.copyProperties(e, dto);
                    rows.add(dto);
                }
                writer.write(rows, sheet);
                if (pageNum * pageSize >= page.getTotal()) {
                    break;
                }
                pageNum++;
            }
        }
    }

    // ============================ 重启恢复 ============================

    /**
     * 应用启动后恢复未完成任务：
     * 临时文件还在 → 清理半成品数据后重跑；文件已丢失 → 标记失败。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        List<ImportRecord> pending = importRecordMapper.selectList(
                new LambdaQueryWrapper<ImportRecord>().eq(ImportRecord::getStatus, 0));
        for (ImportRecord r : pending) {
            File file = r.getFilePath() == null ? null : new File(r.getFilePath());
            if (file == null || !file.exists()) {
                markFatal(r.getBatchNo(), "服务重启后临时文件不存在，任务中断，请重新上传");
                logger.warn("任务{} 无法恢复：临时文件缺失", r.getBatchNo());
                continue;
            }
            // 清理可能已写入的半成品，计数归零
            excelDataMapper.physicalDeleteByBatch(r.getBatchNo());
            importErrorRowMapper.physicalDeleteByBatch(r.getBatchNo());
            importRecordMapper.update(null, new LambdaUpdateWrapper<ImportRecord>()
                    .eq(ImportRecord::getBatchNo, r.getBatchNo())
                    .set(ImportRecord::getReadCount, 0)
                    .set(ImportRecord::getProcessedCount, 0)
                    .set(ImportRecord::getSuccessCount, 0)
                    .set(ImportRecord::getFailCount, 0)
                    .set(ImportRecord::getTotalCount, 0)
                    .set(ImportRecord::getErrorDetails, null)
                    .set(ImportRecord::getStartedAt, LocalDateTime.now()));
            logger.info("恢复未完成导入任务: {}", r.getBatchNo());
            submitJob(r.getBatchNo());
        }
    }

    // ============================ 工具方法 ============================

    private int countRows(File file) {
        CountingListener countingListener = new CountingListener();
        EasyExcel.read(file, countingListener)
                .excelType(resolveExcelType(file.getName()))
                .sheet()
                .headRowNumber(1)
                .doRead();
        return countingListener.getTotal();
    }

    private ExcelTypeEnum resolveExcelType(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".xls")
                ? ExcelTypeEnum.XLS : ExcelTypeEnum.XLSX;
    }

    private ImportRecord requireRecord(String batchNo) {
        ImportRecord r = importRecordMapper.selectOne(
                new LambdaQueryWrapper<ImportRecord>().eq(ImportRecord::getBatchNo, batchNo));
        if (r == null) {
            throw new IllegalArgumentException("任务不存在: " + batchNo);
        }
        return r;
    }

    private ImportErrorRow toErrorRow(String batchNo, ExcelDataDTO dto, String errorMsg) {
        ImportErrorRow row = new ImportErrorRow();
        row.setBatchNo(batchNo);
        row.setRowIndex(dto.getRowIndex());
        row.setDataCode(truncate(dto.getDataCode(), 50));
        row.setName(truncate(dto.getName(), 50));
        row.setIdCard(truncate(dto.getIdCard(), 20));
        row.setPhone(truncate(dto.getPhone(), 20));
        row.setAmount(truncate(dto.getAmount(), 50));
        row.setAddress(truncate(dto.getAddress(), 200));
        row.setRemark(truncate(dto.getRemark(), 500));
        row.setErrorMsg(truncate(errorMsg, 900));
        return row;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg != null ? msg : cur.getClass().getSimpleName();
    }

    private void safeDelete(File file) {
        try {
            if (file != null && file.exists()) {
                Files.deleteIfExists(file.toPath());
            }
        } catch (Exception e) {
            logger.warn("临时文件删除失败: {}", file, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("导入服务关闭，进行中的任务将在下次启动时恢复");
    }
    /** 消费线程内聚的计数器，批量处理后统一回写数据库 */
    private static class WorkerResult {
        final AtomicInteger processed = new AtomicInteger(0);
        final AtomicInteger success = new AtomicInteger(0);
        final AtomicInteger fail = new AtomicInteger(0);
    }
}
