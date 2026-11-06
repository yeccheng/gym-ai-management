package com.gym.web.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 浏览器会自动请求 /favicon.ico；未提供文件时 Spring 会抛 NoResourceFoundException 刷 ERROR。
 */
@RestController
public class FaviconController {

    @GetMapping("/favicon.ico")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void favicon() {
        // no-op
    }
}
