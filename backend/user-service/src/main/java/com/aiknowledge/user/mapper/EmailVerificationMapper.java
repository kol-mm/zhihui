package com.aiknowledge.user.mapper;

import com.aiknowledge.user.entity.EmailVerificationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface EmailVerificationMapper extends BaseMapper<EmailVerificationEntity> {
    @Update("UPDATE email_verification SET failed_attempts = failed_attempts + 1 WHERE user_id = #{userId}")
    int recordFailure(@Param("userId") Long userId);
}
