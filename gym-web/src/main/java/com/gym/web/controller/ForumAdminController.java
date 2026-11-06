package com.gym.web.controller;

import com.gym.common.result.R;
import com.gym.common.security.UserContext;
import com.gym.web.dto.ForumPostRequest;
import com.gym.web.forum.ForumFacade;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端审核。挂在 /api/forum，走现有 ADMIN 鉴权。
 */
@Tag(name = "社区审核")
@RestController
@RequestMapping("/api/forum")
@RequiredArgsConstructor
public class ForumAdminController {

    private final ForumFacade forumFacade;

    @Operation(summary = "审核列表（默认待审 status=0）")
    @GetMapping("/posts")
    public R<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "0") Integer status) {
        return R.ok(forumFacade.pageAdmin(page, size, status));
    }

    @Operation(summary = "通过")
    @PostMapping("/posts/{id}/pass")
    public R<Void> pass(@PathVariable Long id, @RequestParam(required = false) String msg) {
        forumFacade.audit(id, 1, msg == null ? "管理员通过" : msg);
        return R.ok();
    }

    @Operation(summary = "拒绝")
    @PostMapping("/posts/{id}/reject")
    public R<Void> reject(@PathVariable Long id, @RequestParam(required = false) String msg) {
        forumFacade.audit(id, 2, msg == null ? "管理员拒绝" : msg);
        return R.ok();
    }

    @Operation(summary = "管理员编辑")
    @PutMapping("/posts/{id}")
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody ForumPostRequest req) {
        return R.ok(forumFacade.update(UserContext.requireUserId(), id,
                req.getTitle(), req.getContent(), req.getCategory(), true));
    }

    @RequireRole(Role.ADMIN)
    @Operation(summary = "管理员删除")
    @DeleteMapping("/posts/{id}")
    public R<Void> delete(@PathVariable Long id) {
        forumFacade.delete(UserContext.requireUserId(), id, true);
        return R.ok();
    }
}
