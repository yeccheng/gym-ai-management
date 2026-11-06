package com.gym.web;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication(scanBasePackages = "com.gym")
// 所有 Mapper 必须集中在各自的 *.mapper 包里。
// 曾经把 @MapperScan 的范围放大到 com.gym.ai.task，
// 结果 MyBatis 把包里的业务接口 AiTaskHandler 也当成 Mapper 注册，
// 启动时报 Invalid bound statement (not found): AiTaskHandler.type。
// 按包扫是「扫到什么注册什么」，所以扫描范围必须收窄到只放 Mapper 的包。
@MapperScan({"com.gym.system.mapper", "com.gym.training.mapper", "com.gym.ai.mapper"})
@EnableTransactionManagement
@EnableScheduling
public class GymApplication {

    public static void main(String[] args) {
        SpringApplication.run(GymApplication.class, args);
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页插件必须最先注册，否则分页不生效且无任何报错
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        // 防止全表更新删除，线上误操作的最后一道闸
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }
}
