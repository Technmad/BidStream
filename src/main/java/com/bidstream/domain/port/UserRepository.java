package com.bidstream.domain.port;

import com.bidstream.domain.model.User;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    User save(User user);

    Optional<User> findById(UUID id);

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsernameOrEmail(String username, String email);
}
