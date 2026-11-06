package com.gym.web.controller;

import com.gym.common.result.R;
import com.gym.system.entity.Course;
import com.gym.system.service.CourseService;
import com.gym.web.security.RequireRole;
import com.gym.web.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程管理：基础 CRUD。
 * 预约/取消/完成在 BookingController，因为一次预约同时改课程名额与预约记录。
 */
@Tag(name = "课程管理")
@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    @Operation(summary = "课程列表")
    @GetMapping
    public R<List<Course>> list() {
        return R.ok(courseService.list());
    }

    @Operation(summary = "课程详情")
    @GetMapping("/{id}")
    public R<Course> get(@PathVariable Long id) {
        return R.ok(courseService.getById(id));
    }

    @Operation(summary = "新增课程")
    @RequireRole(Role.ADMIN)
    @PostMapping
    public R<Long> create(@RequestBody Course course) {
        courseService.save(course);
        return R.ok(course.getId());
    }

    @Operation(summary = "修改课程")
    @RequireRole(Role.ADMIN)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody Course course) {
        course.setId(id);
        courseService.updateById(course);
        return R.ok();
    }

    @Operation(summary = "删除课程")
    @RequireRole(Role.ADMIN)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        courseService.removeById(id);
        return R.ok();
    }
}
