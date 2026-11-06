package com.gym.ai.task;

import com.gym.ai.mapper.AiTaskMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重启恢复。
 *
 * <p>进程被 kill 时，数据库里会留下 status = 1（RUNNING）的任务——
 * 它们永远不会再有 worker 认领。所以启动时必须把它们重置回排队状态，
 * 否则这些任务对用户来说是永久卡死的。
 *
 * <p>这也是任务必须落库的核心价值：无状态的重试靠内存，一重启全丢；
 * 有状态的兜底靠数据库，重启后续跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiTaskRecovery implements CommandLineRunner {

    private final AiTaskMapper taskMapper;
    private final AiTaskExecutor executor;

    @Override
    public void run(String... args) {
        List<AiTask> unfinished = taskMapper.selectUnfinished();
        if (unfinished.isEmpty()) {
            return;
        }
        for (AiTask task : unfinished) {
            // RUNNING 的必须重置才可能被重新拉起；QUEUED 的重复执行无副作用
            taskMapper.resetToQueued(task.getTaskId());
        }
        log.info("重启恢复：{} 个未完成的 AI 任务已重新入队", unfinished.size());
        executor.dispatch();
    }
}
