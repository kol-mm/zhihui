package com.aiknowledge.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiResponseTest {
    @Test
    void localizesKnownAndDynamicMessages() {
        assertEquals("成功", ApiResponse.ok("data").message());
        assertEquals("请先登录后再操作", ApiResponse.fail("valid user authorization is required").message());
        assertEquals("文件大小不能超过 25 MB", ApiResponse.fail("file size must not exceed 25 MB").message());
    }
}
