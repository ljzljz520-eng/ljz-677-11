package com.excel.listener;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.util.ListUtils;
import com.excel.dto.ExcelDataDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * 分块 SAX 监听器：
 * 每凑满 BATCH 行就放入有界队列，由消费线程做校验和入库。
 * 队列满时 parse 线程阻塞（背压），因此内存占用始终有界，不会把整表读进内存。
 */
public class ChunkingExcelListener implements ReadListener<ExcelDataDTO> {

    private static final Logger logger = LoggerFactory.getLogger(ChunkingExcelListener.class);

    public static final int BATCH_COUNT = 1000;

    /** 放入队列的毒丸对象，表示解析结束 */
    public static final List<ExcelDataDTO> POISON = ListUtils.newArrayListWithExpectedSize(0);

    private final BlockingQueue<List<ExcelDataDTO>> queue;
    private final long offerTimeoutMs;
    /** 每凑满一块入队后回调，参数为本次入队行数 */
    private final IntConsumer onChunkFlushed;

    private List<ExcelDataDTO> buffer = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);
    private int readCount = 0;

    public ChunkingExcelListener(BlockingQueue<List<ExcelDataDTO>> queue,
                                 long offerTimeoutMs,
                                 IntConsumer onChunkFlushed) {
        this.queue = queue;
        this.offerTimeoutMs = offerTimeoutMs;
        this.onChunkFlushed = onChunkFlushed;
    }

    @Override
    public void invoke(ExcelDataDTO data, AnalysisContext context) {
        // EasyExcel的rowIndex从0开始且含表头，数据行+1即Excel中真实行号
        data.setRowIndex(context.readRowHolder().getRowIndex() + 1);
        buffer.add(data);
        readCount++;
        if (buffer.size() >= BATCH_COUNT) {
            List<ExcelDataDTO> chunk = buffer;
            offer(chunk);
            onChunkFlushed.accept(chunk.size());
            buffer = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        if (!buffer.isEmpty()) {
            List<ExcelDataDTO> chunk = buffer;
            offer(chunk);
            onChunkFlushed.accept(chunk.size());
            buffer = ListUtils.newArrayListWithExpectedSize(0);
        }
        offer(POISON);
        logger.info("Excel解析结束，共读取 {} 行", readCount);
    }

    private void offer(List<ExcelDataDTO> chunk) {
        try {
            boolean ok = queue.offer(chunk, offerTimeoutMs, TimeUnit.MILLISECONDS);
            if (!ok) {
                throw new RuntimeException("任务队列等待超时，解析中止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("解析线程被中断", e);
        }
    }

    public int getReadCount() {
        return readCount;
    }
}
