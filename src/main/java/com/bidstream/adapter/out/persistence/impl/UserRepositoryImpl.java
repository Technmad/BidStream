package com.bidstream.adapter.out.persistence.impl;

import com.bidstream.adapter.out.persistence.mapper.UserMapper;
import com.bidstream.adapter.out.persistence.repository.UserJpaRepository;
import com.bidstream.domain.model.User;
import com.bidstream.domain.port.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository jpaRepository;

    public UserRepositoryImpl(UserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public User save(User user) {
        var saved = jpaRepository.save(UserMapper.toEntity(user));
        return UserMapper.toDomain(saved);
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jpaRepository.findById(id).map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return jpaRepository.findByUsername(username).map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jpaRepository.findByEmail(email).map(UserMapper::toDomain);
    }

    @Override
    public boolean existsByUsernameOrEmail(String username, String email) {
        return jpaRepository.existsByUsernameOrEmail(username, email);
    }
}
