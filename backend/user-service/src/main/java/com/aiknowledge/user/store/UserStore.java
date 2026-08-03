package com.aiknowledge.user.store;

import com.aiknowledge.user.entity.UserEntity;

import java.util.Optional;

public interface UserStore {
    Optional<UserEntity> findByUsername(String username);

    UserEntity save(UserEntity user);
}
