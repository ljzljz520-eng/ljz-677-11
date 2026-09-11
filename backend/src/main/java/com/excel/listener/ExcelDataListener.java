package com.excel.listener;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.util.ListUtils;
import com.excel.dto.ExcelDataDTO;
import com.excel.entity.ExcelData;
import com.excel.entity.ImportRowError;
import com.excel.mapper.ExcelDataMapper;
import com.excel.mapper.ImportRowErrorMapper;
import com.excel.utils.ValidationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

/**
 * EasyExcel 数据监听器（SAX 逐行回调，不构建整表对象图）。
 * <p>
 * 内存约束：成功缓存、错误缓存都只保留 {@link #BATCH_COUNT} 行，
 * 攒满即批量入库并清空，5 万行场景常驻堆内存与行数无关。
 * <p>
 * 进度：每处理一行调用 progressCallback，由 Service 侧节流后落库。
 */
public class ExcelDataListener implements ReadListener<ExcelDataDTO> {

    private static final Logger logger = LoggerFactory.getLogger(ExcelDataListener.class);

    /**
     * 每 1000 行落一次库（成功行 / 错误行共用）
     */
    private static final int BATCH_COUNT = 1000;

    private List<ExcelData> successBuffer = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);
    private List<ImportRowError> errorBuffer = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);

    private int totalCount = 0;
    private int successCount = 0;
    private int failCount = 0;

    private final ExcelDataMapper excelDataMapper;
    private final ImportRowErrorMapper rowErrorMapper;
    private final String batchNo;
    /**
     * 每处理完一行回调一次（readCount/validatedCount 已更新）
     */
    private final Consumer<Integer> progressCallback;

    public ExcelDataListener(ExcelDataMapper excelDataMapper,
                             ImportRowErrorMapper rowErrorMapper,
                             String batchNo,
                             Consumer<Integer> progressCallback) {
        this.excelDataMapper = excelDataMapper;
        this.rowErrorMapper = rowErrorMapper;
        this.batchNo = batchNo;
        this.progressCallback = progressCallback;
    }

    @Override
    public void invoke(ExcelDataDTO data, AnalysisContext context) {
        totalCount++;
        Integer rowIndex = context.readRowHolder().getRowIndex() + 1;
        data.setRowIndex(rowIndex);

        String errorMsg = ValidationUtils.validate(data);
        if (errorMsg != null) {
            errorBuffer.add(toError(data, errorMsg));
            failCount++;
            if (errorBuffer.size() >= BATCH_COUNT) {
                flushErrors();
            }
            progressCallback.accept(totalCount);
            return;
        }

        ExcelData entity = new ExcelData();
        BeanUtil.copyProperties(data, entity);
        entity.setBatchNo(batchNo);
        entity.setReportStatus(0);
        successBuffer.add(entity);

        if (successBuffer.size() >= BATCH_COUNT) {
            flushSuccess();
        }
        progressCallback.accept(totalCount);
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        flushSuccess();
        flushErrors();
        logger.info("Excel解析完成：读取{}行，成功{}行，失败{}行", totalCount, successCount, failCount);
    }

    public int getTotalCount() {
        return totalCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailCount() {
        return failCount;
    }

    /**
     * 成功行批量入库；整批失败则降级为逐行插入，坏行计入失败并写错误表
     */
    private void flushSuccess() {
        if (successBuffer.isEmpty()) {
            return;
        }
        int size = successBuffer.size();
        try {
            excelDataMapper.batchInsert(successBuffer);
            successCount += size;
        } catch (Exception e) {
            logger.warn("批量插入{}行失败，降级逐行写入: {}", size, e.getMessage());
            int batchFail = 0;
            for (ExcelData data : successBuffer) {
                try {
                    excelDataMapper.insert(data);
                    successCount++;
                } catch (Exception ex) {
                    batchFail++;
                    failCount++;
                    ImportRowError error = new ImportRowError();
                    error.setBatchNo(batchNo);
                    error.setDataCode(data.getDataCode());
                    error.setName(data.getName());
                    error.setIdCard(data.getIdCard());
                    error.setPhone(data.getPhone());
                    error.setAmount(data.getAmount() == null ? null : data.getAmount().toPlainString());
                    error.setAddress(data.getAddress());
                    error.setRemark(data.getRemark());
                    error.setErrorMsg("入库失败: " + trim(ex.getMessage()));
                    errorBuffer.add(error);
                }
            }
            // 原整批成功计数纠正：整批先没算 success，逐行已累计
            logger.warn("逐行降级完成，成功{}行，失败{}行", size - batchFail, batchFail);
            if (!errorBuffer.isEmpty()) {
                flushErrors();
            }
        } finally {
            successBuffer = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);
        }
    }

    private void flushErrors() {
        if (errorBuffer.isEmpty()) {
            return;
        }
        try {
            rowErrorMapper.batchInsert(errorBuffer);
        } catch (Exception e) {
            // 极端情况下逐行兜底，错误明细不丢
            logger.error("错误行批量入库失败，降级逐行写入", e);
            for (ImportRowError error : errorBuffer) {
                try {
                    rowErrorMapper.insert(error);
                } catch (Exception ignore) {
                    // 单行异常字段过长等情况，截断后重试
                    error.setErrorMsg(trim(error.getErrorMsg()));
                    rowErrorMapper.insert(error);
                }
            }
        } finally {
            errorBuffer = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);
        }
    }

    private ImportRowError toError(ExcelDataDTO data, String errorMsg) {
        ImportRowError error = new ImportRowError();
        error.setBatchNo(batchNo);
        error.setRowIndex(data.getRowIndex());
        error.setDataCode(data.getDataCode());
        error.setName(data.getName());
        error.setIdCard(data.getIdCard());
        error.setPhone(data.getPhone());
        BigDecimal amount = data.getAmount();
        error.setAmount(amount == null ? null : amount.toPlainString());
        error.setAddress(data.getAddress());
        error.setRemark(data.getRemark());
        error.setErrorMsg(errorMsg);
        return error;
    }

    private String trim(String s) {
        if (s == null) {
            return "未知错误";
        }
        return s.length() > 900 ? s.substring(0, 900) : s;
    }
}
