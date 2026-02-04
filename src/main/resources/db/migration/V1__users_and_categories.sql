CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(50)  NOT NULL UNIQUE,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    roles         TEXT[]       NOT NULL DEFAULT ARRAY['ROLE_USER'],
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE categories (
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name  VARCHAR(100) NOT NULL UNIQUE,
    slug  VARCHAR(120) NOT NULL UNIQUE
);
