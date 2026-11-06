package com.gym.web.demo;

import com.gym.system.entity.SysUser;
import com.gym.system.mapper.SysUserMapper;
import com.gym.training.demo.DemoDataGenerator;
import com.gym.training.demo.DemoDataGenerator.DemoResult;
import com.gym.training.demo.DemoDataPlanner;
import com.gym.web.security.Role;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.gym.web.security.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 演示数据初始化。
 *
 * <p>默认关闭。开着它启动会往库里灌一千多条记录，
 * 本地开发每次重启都做一遍纯属浪费时间——所以它是显式开关，不是默认行为。
 *
 * <p>演示账号与演示数据在同一个地方创建：
 * 智能体的 userId 来自登录态，账号 ID 和数据 userId 必须一致，
 * 否则登录后问「我卧推卡住了」，工具查到的是另一个人的数据，一条记录都查不到。
 * 把两件事放在两个初始化器里，迟早会错开。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "gym.demo.enabled", havingValue = "true")
public class DemoDataRunner implements CommandLineRunner {

    private static final String DEMO_USERNAME = "demo";
    private static final String DEMO_PASSWORD = "demo123";

    private final DemoDataGenerator generator;
    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final boolean force;

    public DemoDataRunner(DemoDataGenerator generator,
                          SysUserMapper userMapper,
                          PasswordEncoder passwordEncoder,
                          @Value("${gym.demo.force:false}") boolean force) {
        this.generator = generator;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.force = force;
    }

    @Override
    public void run(String... args) {
        SysUser demo = ensureDemoUser();

        // 幂等看「有没有样板历史」，不是「有没有任意会话」——
        // 否则用户今天打了卡，半年剧本永远灌不进去，曲线只剩一个点、AI 说八周没练。
        if (force) {
            generator.clear(demo.getId());
            log.info("gym.demo.force=true，已清空样板历史，准备重建");
        } else if (generator.hasDemoData(demo.getId())) {
            log.info("演示用户已有样板训练数据，跳过生成（重建请 gym.demo.force=true 或 POST /demo/regenerate）");
            return;
        }

        DemoResult result = generator.generate(DemoDataPlanner.Config.of(demo.getId()));
        List<String> highlights = result.highlights();

        log.info("演示数据生成完毕：{} 次训练 / {} 组记录 / {} 条体测（样板可清；曲线/AI 会计入）",
                result.sessionCount(), result.setCount(), result.bodyRecordCount());
        log.info("这份数据刻意埋了四个结论，答辩时可以先问智能体，再对照：");
        for (String h : highlights) {
            log.info("  · {}", h);
        }
    }

    private SysUser ensureDemoUser() {
        SysUser existing = userMapper.selectByUsername(DEMO_USERNAME);
        if (existing != null) {
            // 招呼不要叫 demo / 演示会员：登录页已去演示号，顶栏也不要再贴演示味
            String rn = existing.getRealName();
            if (rn == null || rn.isBlank()
                    || "demo".equalsIgnoreCase(rn)
                    || "演示会员".equals(rn)) {
                existing.setRealName("铁友");
                userMapper.updateById(existing);
            }
            return existing;
        }
        SysUser demo = new SysUser();
        demo.setUsername(DEMO_USERNAME);
        // 明文密码只在演示环境可接受，且这是唯一一处写死的口令
        demo.setPassword(passwordEncoder.encode(DEMO_PASSWORD));
        demo.setRealName("铁友");
        demo.setRole(Role.MEMBER.code());
        demo.setStatus(1);
        userMapper.insert(demo);
        return demo;
    }
}
