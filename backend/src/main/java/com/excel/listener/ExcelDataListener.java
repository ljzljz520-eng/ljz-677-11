package com.excel.listener;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.util.ListUtils;
import com.excel.dto.ExcelDataDTO;
import com.excel.entity.ExcelData;
import com.excel.mapper.ExcelDataMapper;
import com.excel.task.ImportTaskProgress;
import com.excel.utils.ValidationUtils;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * EasyExcel 数据监听器
 * SAX模式逐行解析 + 分批入库，整表不进入内存
 */
public class ExcelDataListener implements ReadListener<ExcelDataDTO> {

    private static final Logger logger = LoggerFactory.getLogger(ExcelDataListener.class);

    /**
     * 每积累1000条批量入库，然后清空缓存便于内存回收
     */
    private static final int BATCH_COUNT = 1000;

    /**
     * 内存中最多保留的错误条数，防止大量失败数据堆积占用内存
     */
    private static final int MAX_ERROR_KEEP = 200;

    /**
     * 缓存的数据
     */
    private List<ExcelData> cachedDataList = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);

    /**
     * 错误数据列表（有界，最多MAX_ERROR_KEEP条）
     */
    @Getter
    private final List<ExcelDataDTO> errorList = new ArrayList<>();

    /**
     * 成功条数
     */
    @Getter
    private int successCount = 0;

    /**
     * 失败条数
     */
    @Getter
    private int failCount = 0;

    /**
     * 总条数
     */
    @Getter
    private int totalCount = 0;

    private final ExcelDataMapper excelDataMapper;
    private final String batchNo;

    /**
     * 实时进度（异步任务时非空）
     */
    private final ImportTaskProgress progress;

    /**
     * 每批入库后的回调（用于把进度持久化到数据库）
     */
    private final Consumer<ImportTaskProgress> batchPersistCallback;

    public ExcelDataListener(ExcelDataMapper excelDataMapper, String batchNo) {
        this(excelDataMapper, batchNo, null, null);
    }

    public ExcelDataListener(ExcelDataMapper excelDataMapper, String batchNo,
                             ImportTaskProgress progress,
                             Consumer<ImportTaskProgress> batchPersistCallback) {
        this.excelDataMapper = excelDataMapper;
        this.batchNo = batchNo;
        this.progress = progress;
        this.batchPersistCallback = batchPersistCallback;
    }

    @Override
    public void invoke(ExcelDataDTO data, AnalysisContext context) {
        totalCount++;
        Integer rowIndex = context.readRowHolder().getRowIndex() + 1;
        data.setRowIndex(rowIndex);

        if (progress != null) {
            progress.getReadCount().incrementAndGet();
            estimateTotalOnce(context);
        }

        // 数据校验
        String errorMsg = ValidationUtils.validate(data);
        if (progress != null) {
            progress.getValidatedCount().incrementAndGet();
        }
        if (errorMsg != null) {
            data.setErrorMsg(errorMsg);
            addError(data);
            failCount++;
            if (progress != null) {
                progress.getFailCount().incrementAndGet();
            }
            logger.warn("第{}行数据校验失败: {}", rowIndex, errorMsg);
            return;
        }

        // 转换为实体
        ExcelData entity = new ExcelData();
        BeanUtil.copyProperties(data, entity);
        entity.setBatchNo(batchNo);
        entity.setReportStatus(0);

        cachedDataList.add(entity);

        // 达到批量大小，进行存储
        if (cachedDataList.size() >= BATCH_COUNT) {
            saveData();
            cachedDataList = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // 保存最后一批数据
        if (!cachedDataList.isEmpty()) {
            saveData();
        }
        persistProgress();
        logger.info("Excel解析完成！总计：{}条，成功：{}条，失败：{}条", totalCount, successCount, failCount);
    }

    /**
     * 从Sheet的dimension信息读取预估总行数（仅xlsx支持），只设置一次
     */
    private void estimateTotalOnce(AnalysisContext context) {
        if (progress.getTotalEstimate() != null) {
            return;
        }
        try {
            Integer approximate = context.readSheetHolder().getApproximateTotalRowNumber();
            if (approximate != null && approximate > 1) {
                // 减去表头行
                progress.setTotalEstimate(approximate - 1);
            }
        } catch (Exception e) {
            // 部分文件没有dimension信息，忽略，前端显示"估算中"
            logger.debug("无法获取预估总行数: {}", e.getMessage());
        }
    }

    private void addError(ExcelDataDTO error) {
        if (errorList.size() < MAX_ERROR_KEEP) {
            errorList.add(error);
        }
        if (progress != null) {
            progress.addErrorPreview(error);
        }
    }

    /**
     * 批量保存数据
     */
    private void saveData() {
        logger.debug("开始批量保存数据，数量：{}", cachedDataList.size());
        try {
            excelDataMapper.insertBatch(cachedDataList);
            successCount += cachedDataList.size();
            if (progress != null) {
                progress.getSuccessCount().addAndGet(cachedDataList.size());
            }
            logger.debug("批量保存成功，数量：{}", cachedDataList.size());
        } catch (Exception e) {
            logger.error("批量保存失败，转为逐条重试", e);
            // 单条重试，定位坏数据
            for (ExcelData data : cachedDataList) {
                try {
                    excelDataMapper.insert(data);
                    successCount++;
                    if (progress != null) {
                        progress.getSuccessCount().incrementAndGet();
                    }
                } catch (Exception ex) {
                    failCount++;
                    if (progress != null) {
                        progress.getFailCount().incrementAndGet();
                    }
                    ExcelDataDTO errorDto = new ExcelDataDTO();
                    BeanUtil.copyProperties(data, errorDto);
                    errorDto.setErrorMsg("数据库保存失败: " + ex.getMessage());
                    addError(errorDto);
                }
            }
        }
        persistProgress();
    }

    /**
     * 每批入库后把进度持久化到数据库，页面刷新/服务重启后仍可查看
     */
    private void persistProgress() {
        if (batchPersistCallback != null && progress != null) {
            try {
                batchPersistCallback.accept(progress);
            } catch (Exception e) {
                logger.warn("进度持久化失败: {}", e.getMessage());
            }
        }
    }
}
