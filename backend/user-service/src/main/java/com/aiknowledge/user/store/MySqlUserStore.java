package com.aiknowledge.user.store;

import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("mysql")
public class MySqlUserStore implements UserStore {
    private final UserMapper userMapper;

    public MySqlUserStore(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public Optional<UserEntity> findByUsername(String username) {
        return Optional.ofNullable(userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, username)
        ));
    }

    @Override
    public UserEntity save(UserEntity user) {
        userMapper.insert(user);
        return user;
    }

    @Override
    public List<UserEntity> listUsers() {
        return userMapper.selectList(Wrappers.emptyWrapper());
    }

    @Override
    public Optional<UserEntity> updateStatus(Long userId, String status) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            return Optional.empty();
        }
        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return Optional.of(user);
    }
}
