package com.excel.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导入任务注册表：保存进行中及刚完成的任务进度。
 * 已完成任务在内存中保留30分钟供前端查询，超时清理由数据库记录兜底。
 */
@Component
public class ImportTaskManager {

    private static final Logger logger = LoggerFactory.getLogger(ImportTaskManager.class);

    /** 已完成任务在内存中的保留时长 */
    private static final long FINISHED_TTL_MS = 30 * 60 * 1000L;

    private final Map<String, ImportTaskProgress> tasks = new ConcurrentHashMap<>();

    public void register(ImportTaskProgress progress) {
        tasks.put(progress.getTaskId(), progress);
    }

    public ImportTaskProgress get(String taskId) {
        return tasks.get(taskId);
    }

    @Scheduled(fixedDelay = 60_000L)
    public void evictFinishedTasks() {
        long now = System.currentTimeMillis();
        int before = tasks.size();
        tasks.values().removeIf(p -> p.isFinished()
                && p.getFinishTimeMillis() != null
                && now - p.getFinishTimeMillis() > FINISHED_TTL_MS);
        int removed = before - tasks.size();
        if (removed > 0) {
            logger.debug("清理已完成导入任务 {} 个，当前内存任务数 {}", removed, tasks.size());
        }
    }
}
