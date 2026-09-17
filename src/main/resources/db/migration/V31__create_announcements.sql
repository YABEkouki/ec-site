CREATE TABLE
    announcements (
        id BIGSERIAL PRIMARY KEY,
        type VARCHAR(30) NOT NULL,
        importance VARCHAR(30) NOT NULL,
        title VARCHAR(200) NOT NULL,
        content TEXT NOT NULL,
        published BOOLEAN NOT NULL DEFAULT FALSE,
        published_at TIMESTAMP,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT chk_announcements_type CHECK (
            type IN ('GENERAL', 'PRODUCT', 'SHIPPING', 'MAINTENANCE')
        ),
        CONSTRAINT chk_announcements_importance CHECK (importance IN ('NORMAL', 'IMPORTANT', 'URGENT')),
        CONSTRAINT chk_announcements_published_at CHECK (
            published = FALSE
            OR published_at IS NOT NULL
        )
    );