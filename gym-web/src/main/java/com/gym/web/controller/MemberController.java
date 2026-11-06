package com.gym.web.controller;

import com.gym.common.result.R;
import com.gym.system.entity.Member;
import com.gym.system.service.MemberService;
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
 * 会员管理：注册、信息维护、挂失、冻结、注销、恢复。
 */
@Tag(name = "会员管理")
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @Operation(summary = "会员列表")
    @GetMapping
    public R<List<Member>> list() {
        return R.ok(memberService.list());
    }

    @Operation(summary = "会员详情")
    @GetMapping("/{id}")
    public R<Member> get(@PathVariable Long id) {
        return R.ok(memberService.getById(id));
    }

    @Operation(summary = "注册会员")
    @RequireRole(Role.ADMIN)
    @PostMapping
    public R<Long> create(@RequestBody Member member) {
        memberService.save(member);
        return R.ok(member.getId());
    }

    @Operation(summary = "信息维护")
    @RequireRole(Role.ADMIN)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody Member member) {
        member.setId(id);
        memberService.updateById(member);
        return R.ok();
    }

    @Operation(summary = "挂失")
    @RequireRole(Role.ADMIN)
    @PostMapping("/{id}/loss")
    public R<Void> reportLoss(@PathVariable Long id) {
        memberService.reportLoss(id);
        return R.ok();
    }

    @Operation(summary = "冻结")
    @RequireRole(Role.ADMIN)
    @PostMapping("/{id}/freeze")
    public R<Void> freeze(@PathVariable Long id) {
        memberService.freeze(id);
        return R.ok();
    }

    @Operation(summary = "注销")
    @RequireRole(Role.ADMIN)
    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        memberService.cancel(id);
        return R.ok();
    }

    @Operation(summary = "恢复正常")
    @RequireRole(Role.ADMIN)
    @PostMapping("/{id}/restore")
    public R<Void> restore(@PathVariable Long id) {
        memberService.restore(id);
        return R.ok();
    }

    @Operation(summary = "删除会员")
    @RequireRole(Role.ADMIN)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        memberService.removeById(id);
        return R.ok();
    }
}
