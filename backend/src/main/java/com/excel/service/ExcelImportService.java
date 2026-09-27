package com.excel.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.excel.dto.ExcelDataDTO;
import com.excel.dto.ImportProgressDTO;
import com.excel.dto.ImportResultDTO;
import com.excel.entity.ExcelData;
import com.excel.entity.ImportRecord;
import com.excel.entity.User;
import com.excel.listener.ExcelDataListener;
import com.excel.mapper.ExcelDataMapper;
import com.excel.mapper.ImportRecordMapper;
import com.excel.mapper.UserMapper;
import com.excel.task.ImportTaskManager;
import com.excel.task.ImportTaskProgress;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private static final Logger logger = LoggerFactory.getLogger(ExcelImportService.class);

    private final ExcelDataMapper excelDataMapper;
    private final ImportRecordMapper importRecordMapper;
    private final UserMapper userMapper;
    private final ImportTaskManager importTaskManager;
    private final ThreadPoolTaskExecutor importTaskExecutor;

    /**
     * 上传文件临时目录（后台异步解析用）
     */
    @Value("${excel.import.temp-dir:}")
    private String tempDir;

    /**
     * 创建异步导入任务：文件流式落盘后立即返回任务编号，解析在后台线程分批进行
     *
     * @return 任务编号（即导入批次号）
     */
    public String startAsyncImport(MultipartFile file, Long operatorId) throws IOException {
        String taskId = IdUtil.fastSimpleUUID();
        String fileName = file.getOriginalFilename();
        long fileSize = file.getSize();

        logger.info("创建异步导入任务: {}, 文件: {}, 大小: {} bytes", taskId, fileName, fileSize);

        // 1. 上传文件流式写入临时目录（不进内存），供后台线程分段读取
        Path tempFile = saveToTempFile(file, taskId);

        // 2. 创建导入记录（状态：处理中）
        User operator = userMapper.selectById(operatorId);
        ImportRecord record = new ImportRecord();
        record.setBatchNo(taskId);
        record.setFileName(fileName);
        record.setFileSize(fileSize);
        record.setStatus(0);
        record.setOperatorId(operatorId);
        record.setOperatorName(operator != null ? operator.getRealName() : "系统");
        importRecordMapper.insert(record);

        // 3. 注册内存进度
        ImportTaskProgress progress = new ImportTaskProgress(taskId, fileName, fileSize);
        importTaskManager.register(progress);

        // 4. 提交后台解析任务
        ExcelTypeEnum excelType = fileName != null && fileName.toLowerCase().endsWith(".xlsx")
                ? ExcelTypeEnum.XLSX : ExcelTypeEnum.XLS;
        try {
            importTaskExecutor.execute(() -> processImport(progress, record.getId(), tempFile, excelType));
        } catch (RejectedExecutionException e) {
            logger.error("导入任务提交被拒绝，线程池已满", e);
            progress.markFailed("当前导入任务过多，请稍后重试");
            markRecordFailed(record.getId(), "当前导入任务过多，请稍后重试");
            deleteQuietly(tempFile);
            throw new RuntimeException("当前导入任务过多，请稍后重试");
        }

        return taskId;
    }

    /**
     * 后台线程：流式解析 -> 逐行校验 -> 分批入库 -> 更新进度
     */
    private void processImport(ImportTaskProgress progress, Long recordId, Path tempFile, ExcelTypeEnum excelType) {
        progress.markProcessing();
        ExcelDataListener listener = new ExcelDataListener(
                excelDataMapper, progress.getTaskId(), progress,
                p -> persistProgress(recordId, p));

        try (InputStream in = Files.newInputStream(tempFile)) {
            EasyExcel.read(in, ExcelDataDTO.class, listener)
                    .excelType(excelType)
                    .charset(StandardCharsets.UTF_8)
                    .sheet()
                    .headRowNumber(1)
                    .doRead();

            boolean hasFailures = listener.getFailCount() > 0;
            progress.markCompleted(hasFailures);
            finalizeRecord(recordId, listener, hasFailures ? 2 : 1, null);

            logger.info("异步导入完成: taskId={}, 总计{}条，成功{}条，失败{}条",
                    progress.getTaskId(), listener.getTotalCount(),
                    listener.getSuccessCount(), listener.getFailCount());
        } catch (Exception e) {
            logger.error("异步导入失败: taskId={}", progress.getTaskId(), e);
            progress.markFailed(e.getMessage());
            finalizeRecord(recordId, listener, 3, "导入失败: " + e.getMessage());
        } finally {
            deleteQuietly(tempFile);
        }
    }

    /**
     * 每批入库后回调：把进度持久化到数据库，页面刷新/服务重启后仍可查看
     */
    private void persistProgress(Long recordId, ImportTaskProgress progress) {
        ImportRecord update = new ImportRecord();
        update.setId(recordId);
        update.setTotalCount((int) progress.getReadCount().get());
        update.setSuccessCount((int) progress.getSuccessCount().get());
        update.setFailCount((int) progress.getFailCount().get());
        importRecordMapper.updateById(update);
    }

    /**
     * 任务结束时落库最终状态。status: 1-完成 2-部分失败 3-失败
     */
    private void finalizeRecord(Long recordId, ExcelDataListener listener, int status, String errorDetails) {
        ImportRecord update = new ImportRecord();
        update.setId(recordId);
        update.setTotalCount(listener.getTotalCount());
        update.setSuccessCount(listener.getSuccessCount());
        update.setFailCount(listener.getFailCount());
        update.setStatus(status);

        if (errorDetails != null) {
            update.setErrorDetails(errorDetails);
        } else if (!listener.getErrorList().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (ExcelDataDTO error : listener.getErrorList()) {
                sb.append("第").append(error.getRowIndex()).append("行: ")
                        .append(error.getErrorMsg()).append("\n");
            }
            int omitted = listener.getFailCount() - listener.getErrorList().size();
            if (omitted > 0) {
                sb.append("... 其余 ").append(omitted).append(" 条错误从略");
            }
            update.setErrorDetails(sb.toString());
        }
        importRecordMapper.updateById(update);
    }

    /**
     * 查询导入进度：优先内存（实时），内存没有则查数据库（服务重启/任务清理后兜底）
     */
    public ImportProgressDTO getImportProgress(String taskId) {
        ImportTaskProgress progress = importTaskManager.get(taskId);
        if (progress != null) {
            return ImportProgressDTO.from(progress);
        }
        ImportRecord record = importRecordMapper.selectOne(
                new LambdaQueryWrapper<ImportRecord>().eq(ImportRecord::getBatchNo, taskId));
        if (record == null) {
            return null;
        }
        return ImportProgressDTO.fromRecord(record);
    }

    /**
     * 上传文件流式写入临时目录，文件名用任务编号避免路径注入
     */
    private Path saveToTempFile(MultipartFile file, String taskId) throws IOException {
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String suffix = fileName.toLowerCase().endsWith(".xlsx") ? ".xlsx" : ".xls";
        Path dir = StrUtil.isNotBlank(tempDir)
                ? Paths.get(tempDir)
                : Paths.get(System.getProperty("java.io.tmpdir"), "excel-import");
        Files.createDirectories(dir);
        Path tempFile = dir.resolve(taskId + suffix);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warn("临时文件删除失败: {}", path, e);
        }
    }

    private void markRecordFailed(Long recordId, String reason) {
        ImportRecord update = new ImportRecord();
        update.setId(recordId);
        update.setStatus(3);
        update.setErrorDetails(reason);
        importRecordMapper.updateById(update);
    }

    /**
     * 导入Excel文件（同步方式，兼容旧接口）
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportResultDTO importExcel(MultipartFile file, Long operatorId) throws IOException {
        String batchNo = IdUtil.fastSimpleUUID();
        String fileName = file.getOriginalFilename();
        long fileSize = file.getSize();

        logger.info("开始导入Excel文件: {}, 大小: {} bytes, 批次号: {}", fileName, fileSize, batchNo);

        // 获取操作人信息
        User operator = userMapper.selectById(operatorId);
        String operatorName = operator != null ? operator.getRealName() : "系统";

        // 创建导入记录
        ImportRecord record = new ImportRecord();
        record.setBatchNo(batchNo);
        record.setFileName(fileName);
        record.setFileSize(fileSize);
        record.setStatus(0);
        record.setOperatorId(operatorId);
        record.setOperatorName(operatorName);
        importRecordMapper.insert(record);

        // 使用EasyExcel SAX模式解析，避免OOM
        ExcelDataListener listener = new ExcelDataListener(excelDataMapper, batchNo);

        try {
            // 根据文件后缀判断Excel类型
            ExcelTypeEnum excelType = fileName != null && fileName.endsWith(".xlsx")
                    ? ExcelTypeEnum.XLSX : ExcelTypeEnum.XLS;

            EasyExcel.read(file.getInputStream(), ExcelDataDTO.class, listener)
                    .excelType(excelType)
                    .charset(StandardCharsets.UTF_8)
                    .sheet()
                    .headRowNumber(1)
                    .doRead();

            // 更新导入记录
            record.setTotalCount(listener.getTotalCount());
            record.setSuccessCount(listener.getSuccessCount());
            record.setFailCount(listener.getFailCount());
            record.setStatus(listener.getFailCount() > 0 ? 2 : 1);

            if (!listener.getErrorList().isEmpty()) {
                StringBuilder errorDetails = new StringBuilder();
                for (ExcelDataDTO error : listener.getErrorList()) {
                    errorDetails.append("第").append(error.getRowIndex()).append("行: ")
                            .append(error.getErrorMsg()).append("\n");
                }
                record.setErrorDetails(errorDetails.toString());
            }

            importRecordMapper.updateById(record);

            logger.info("Excel导入完成: 总计{}条，成功{}条，失败{}条",
                    listener.getTotalCount(), listener.getSuccessCount(), listener.getFailCount());

            return ImportResultDTO.builder()
                    .batchNo(batchNo)
                    .totalCount(listener.getTotalCount())
                    .successCount(listener.getSuccessCount())
                    .failCount(listener.getFailCount())
                    .errorList(listener.getErrorList())
                    .status(listener.getFailCount() > 0 ? "completed_with_errors" : "completed")
                    .message(String.format("导入完成，总计%d条，成功%d条，失败%d条",
                            listener.getTotalCount(), listener.getSuccessCount(), listener.getFailCount()))
                    .build();

        } catch (Exception e) {
            logger.error("Excel导入失败", e);
            record.setStatus(3);
            record.setErrorDetails("导入失败: " + e.getMessage());
            importRecordMapper.updateById(record);
            throw new RuntimeException("Excel导入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取导入记录列表
     */
    public Page<ImportRecord> getImportRecords(Integer pageNum, Integer pageSize) {
        Page<ImportRecord> page = new Page<>(pageNum, pageSize);
        return importRecordMapper.selectPage(page,
                new LambdaQueryWrapper<ImportRecord>()
                        .orderByDesc(ImportRecord::getCreateTime));
    }

    /**
     * 根据批次号获取数据
     */
    public Page<ExcelData> getDataByBatch(String batchNo, Integer pageNum, Integer pageSize) {
        Page<ExcelData> page = new Page<>(pageNum, pageSize);
        return excelDataMapper.selectPage(page,
                new LambdaQueryWrapper<ExcelData>()
                        .eq(ExcelData::getBatchNo, batchNo)
                        .orderByAsc(ExcelData::getId));
    }

    /**
     * 获取待上报数据
     */
    public List<ExcelData> getPendingReportData(String batchNo) {
        return excelDataMapper.selectByBatchAndStatus(batchNo, 0);
    }

    /**
     * 下载导入模板
     */
    public byte[] downloadTemplate() {
        // 返回模板的字节数组
        return null; // Controller中处理
    }
}
