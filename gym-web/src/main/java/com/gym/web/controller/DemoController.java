package com.gym.web.controller;

import com.gym.common.result.R;
import com.gym.common.result.ResultCode;
import com.gym.common.security.UserContext;
import com.gym.training.demo.DemoDataGenerator;
import com.gym.training.demo.DemoDataGenerator.DemoResult;
import com.gym.training.demo.DemoDataPlanner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 演示数据接口。
 *
 * <p>存在的唯一目的是<b>让答辩现场可重放</b>：
 * 演示前一天点一下「重建」，智能体拿到的数据状态就是确定的那一份，
 * 不会因为昨天多练了一次而改变它给出的结论。
 *
 * <p>重建的是当前登录用户的数据——演示时先登录 demo 账号，再点重建，
 * 之后智能体在这个登录态下查到的就是这份数据。
 */
@Slf4j
@Tag(name = "演示数据")
@RestController
@RequestMapping("/demo")
@RequiredArgsConstructor
public class DemoController {

    private final DemoDataGenerator generator;

    @Operation(summary = "重建半年演示数据",
            description = "清样板 + 清会冲垮卧推/肩推平台期剧本的近期真实组，再按固定种子生成；答辩可重放")
    @PostMapping("/regenerate")
    public R<Map<String, Object>> regenerate() {
        long userId = UserContext.requireUserId();
        generator.clear(userId);
        int stripped = generator.clearStoryBreakingRealSets(userId);
        DemoResult result = generator.generate(DemoDataPlanner.Config.of(userId));
        log.info("用户 {} 重建演示数据：{} 次训练 / {} 组（清冲突真实组 {}）",
                userId, result.sessionCount(), result.setCount(), stripped);
        return R.ok(Map.of(
                "sessionCount", result.sessionCount(),
                "setCount", result.setCount(),
                "bodyRecordCount", result.bodyRecordCount(),
                "strippedConflictingSets", stripped));
    }

    @Operation(summary = "查看这份数据里埋了什么结论",
            description = "答辩前先记住这些，问完智能体后逐条对照")
    @GetMapping("/highlights")
    public R<List<String>> highlights() {
        return R.ok(DemoDataPlanner.plan(
                DemoDataPlanner.Config.of(UserContext.requireUserId())).highlights());
    }

    @Operation(summary = "清空当前用户的训练数据")
    @PostMapping("/clear")
    public R<Map<String, Object>> clear() {
        long userId = UserContext.requireUserId();
        if (!generator.hasData(userId)) {
            return R.fail(ResultCode.PARAM_ERROR, "当前用户没有训练数据");
        }
        generator.clear(userId);
        return R.ok(Map.of("cleared", true));
    }
}
