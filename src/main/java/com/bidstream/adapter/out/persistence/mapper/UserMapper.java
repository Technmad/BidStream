package com.bidstream.adapter.out.persistence.mapper;

import com.bidstream.adapter.out.persistence.entity.UserJpaEntity;
import com.bidstream.domain.model.User;

public final class UserMapper {

    private UserMapper() {
    }

    public static User toDomain(UserJpaEntity entity) {
        return new User(entity.getId(), entity.getUsername(), entity.getEmail(),
                entity.getPasswordHash(), entity.getRoles(), entity.getCreatedAt());
    }

    public static UserJpaEntity toEntity(User user) {
        return new UserJpaEntity(user.id(), user.username(), user.email(), user.passwordHash(),
                user.roles(), user.createdAt());
    }
}
