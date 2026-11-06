package com.gym.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gym.common.result.R;
import com.gym.common.result.ResultCode;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.shiro.web.filter.authz.RolesAuthorizationFilter;
import org.apache.shiro.web.util.WebUtils;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 角色不足时返回统一 JSON 403，而不是 Shiro 默认跳转页面。
 */
public class JsonRolesAuthorizationFilter extends RolesAuthorizationFilter {

    private final ObjectMapper objectMapper;

    public JsonRolesAuthorizationFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws IOException {
        HttpServletResponse http = WebUtils.toHttp(response);
        if (!http.isCommitted()) {
            http.setStatus(HttpServletResponse.SC_FORBIDDEN);
            http.setContentType(MediaType.APPLICATION_JSON_VALUE);
            http.setCharacterEncoding(StandardCharsets.UTF_8.name());
            http.getWriter().write(objectMapper.writeValueAsString(R.fail(ResultCode.FORBIDDEN)));
        }
        return false;
    }
}
