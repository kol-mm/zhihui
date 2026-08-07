package com.aiknowledge.common;

public record ApiResponse<T>(int code, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "成功", data);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(500, localizeMessage(message), null);
    }

    private static String localizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "请求处理失败";
        }

        String exact = switch (message) {
            case "valid user authorization is required" -> "请先登录后再操作";
            case "admin authorization is required" -> "需要管理员权限";
            case "admin authorization is required to clear all messages" -> "只有管理员可以清空全部消息";
            case "internal authorization is required" -> "内部服务认证失败";
            case "captcha is required or invalid; please obtain a new captcha" -> "验证码错误或已失效，请重新获取";
            case "user not found" -> "用户不存在";
            case "user account is disabled" -> "该用户账号已被禁用";
            case "username already exists" -> "用户名已存在";
            case "this username is reserved" -> "该用户名为系统保留名称";
            case "username must contain 3-32 letters, numbers, underscores or hyphens" -> "用户名须由 3-32 个字母、数字、下划线或连字符组成";
            case "invalid username or password" -> "用户名或密码错误";
            case "password must contain between 8 and 128 characters" -> "密码长度须为 8-128 个字符";
            case "new password must contain between 8 and 128 characters" -> "新密码长度须为 8-128 个字符";
            case "current password is incorrect" -> "当前密码不正确";
            case "profile fields exceed the allowed length" -> "个人资料内容超过允许长度";
            case "user ids must be positive numbers" -> "用户编号必须为正整数";
            case "cannot follow yourself" -> "不能关注自己";
            case "interaction with this user is blocked" -> "由于屏蔽关系，无法与该用户互动";
            case "access to this user is denied" -> "无权访问该用户的数据";
            case "report not found" -> "举报记录不存在";
            case "file is required" -> "请选择文件";
            case "knowledge file not found" -> "知识文件不存在";
            case "access to this knowledge file is denied" -> "无权访问该知识文件";
            case "category name is required" -> "请输入分类名称";
            case "select between 1 and 12 images" -> "请选择 1-12 张图片";
            case "select between 1 and 9 images" -> "请选择 1-9 张图片";
            case "image file is empty" -> "图片文件为空";
            case "each image must not exceed 10 MB" -> "每张图片不能超过 10 MB";
            case "only JPEG, PNG, GIF and WebP images are supported" -> "仅支持 JPEG、PNG、GIF 和 WebP 图片";
            case "avatar file is empty" -> "头像文件为空";
            case "avatar must not exceed 5 MB" -> "头像文件不能超过 5 MB";
            case "only JPEG, PNG, GIF and WebP avatars are supported" -> "头像仅支持 JPEG、PNG、GIF 和 WebP 格式";
            case "community feature is disabled" -> "社区功能已关闭";
            case "post not found" -> "帖子不存在或暂不可查看";
            case "comment not found" -> "评论不存在";
            case "draft not found" -> "草稿不存在";
            case "access to this post is denied" -> "无权操作该帖子";
            case "access to this comment is denied" -> "无权操作该评论";
            case "access to this draft is denied" -> "无权操作该草稿";
            case "comment content is required" -> "请输入评论内容";
            case "post and comment content are required" -> "帖子和评论内容不能为空";
            case "parent comment does not belong to this post" -> "被回复的评论不属于该帖子";
            case "invalid post status" -> "帖子状态不正确";
            case "message content is required" -> "请输入消息内容";
            case "both users are required" -> "请选择私信双方用户";
            case "cannot create a private chat with yourself" -> "不能和自己创建私信会话";
            case "chat session not found" -> "私信会话不存在";
            case "user is not a participant of this session" -> "你不是该私信会话的参与者";
            case "only the sender can delete this message" -> "只有发送者可以删除该消息";
            case "notifications feature is disabled" -> "通知功能已关闭";
            case "notification not found" -> "通知不存在";
            case "valid notification data is required" -> "通知数据不完整";
            case "ticket not found" -> "反馈工单不存在";
            case "invalid storage path", "invalid media path", "invalid avatar path" -> "文件存储路径无效";
            case "invalid media reference", "invalid avatar reference" -> "文件引用无效";
            case "file has no storage reference" -> "文件缺少存储信息";
            case "unsupported storage reference" -> "不支持的文件存储引用";
            case "unsupported activity type" -> "不支持的操作类型";
            default -> null;
        };
        if (exact != null) {
            return exact;
        }
        if (message.startsWith("file size must not exceed ")) {
            return "文件大小不能超过 " + message.substring("file size must not exceed ".length());
        }
        if (message.startsWith("unsupported file type: ")) {
            return "不支持的文件类型：" + message.substring("unsupported file type: ".length());
        }
        if (message.startsWith("avatar upload failed: ")) {
            return "头像上传失败：" + localizeMessage(message.substring("avatar upload failed: ".length()));
        }
        if (message.startsWith("file upload failed: ")) {
            return "文件上传失败：" + localizeMessage(message.substring("file upload failed: ".length()));
        }
        if (message.startsWith("image upload failed: ")) {
            return "图片上传失败：" + localizeMessage(message.substring("image upload failed: ".length()));
        }
        if (message.startsWith("failed to ")) {
            return "文件处理失败，请稍后重试";
        }
        return message;
    }
}
