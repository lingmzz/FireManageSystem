CREATE TABLE IF NOT EXISTS fm_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(40) NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auth_version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES fm_user (id),
    FOREIGN KEY (role_id) REFERENCES fm_role (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_role_permission (
    role_id BIGINT NOT NULL,
    permission_code VARCHAR(48) NOT NULL,
    PRIMARY KEY (role_id, permission_code),
    FOREIGN KEY (role_id) REFERENCES fm_role (id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_menu (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT,
    name VARCHAR(60) NOT NULL,
    view_key VARCHAR(32),
    permission_code VARCHAR(48),
    sort_order INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    FOREIGN KEY (parent_id) REFERENCES fm_menu (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_approval (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    location VARCHAR(120) NOT NULL,
    description TEXT NOT NULL,
    reporter_id BIGINT NOT NULL,
    assignee_id BIGINT,
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (reporter_id) REFERENCES fm_user (id),
    FOREIGN KEY (assignee_id) REFERENCES fm_user (id),
    INDEX idx_approval_status_order (status, id),
    INDEX idx_approval_reporter_order (reporter_id, id),
    INDEX idx_approval_assignee_status (assignee_id, status, id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_approval_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    approval_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    remark TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (approval_id) REFERENCES fm_approval (id),
    FOREIGN KEY (actor_id) REFERENCES fm_user (id),
    INDEX idx_approval_event_order (approval_id, id),
    INDEX idx_approval_event_actor (actor_id, approval_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_workflow_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    flow_type VARCHAR(32) NOT NULL,
    version_no INT NOT NULL,
    start_key VARCHAR(24) NOT NULL,
    published_by BIGINT NOT NULL,
    published_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (flow_type, version_no),
    FOREIGN KEY (published_by) REFERENCES fm_user (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_workflow_config (
    flow_type VARCHAR(32) PRIMARY KEY,
    revision INT NOT NULL DEFAULT 0,
    last_version INT NOT NULL DEFAULT 0,
    active_version_id BIGINT,
    draft JSON,
    FOREIGN KEY (active_version_id) REFERENCES fm_workflow_version (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_workflow_step (
    version_id BIGINT NOT NULL,
    step_key VARCHAR(24) NOT NULL,
    display_order INT NOT NULL,
    step_name VARCHAR(80) NOT NULL,
    role_id BIGINT NOT NULL,
    role_name VARCHAR(80) NOT NULL,
    unit_scope VARCHAR(24) NOT NULL,
    assignee_only BOOLEAN NOT NULL,
    pass_label VARCHAR(30) NOT NULL,
    remark_required BOOLEAN NOT NULL,
    pass_to VARCHAR(24),
    return_to VARCHAR(24),
    PRIMARY KEY (version_id, step_key),
    INDEX idx_workflow_step_role (role_id, version_id),
    FOREIGN KEY (version_id) REFERENCES fm_workflow_version (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_approval_route (
    approval_id BIGINT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    step_key VARCHAR(24),
    FOREIGN KEY (approval_id) REFERENCES fm_approval (id),
    FOREIGN KEY (version_id) REFERENCES fm_workflow_version (id),
    FOREIGN KEY (version_id, step_key) REFERENCES fm_workflow_step (version_id, step_key)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_approval_step_owner (
    approval_id BIGINT NOT NULL,
    step_key VARCHAR(24) NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (approval_id, step_key),
    FOREIGN KEY (approval_id) REFERENCES fm_approval_route (approval_id),
    FOREIGN KEY (user_id) REFERENCES fm_user (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_approval_flow_event (
    event_id BIGINT PRIMARY KEY,
    action_label VARCHAR(30) NOT NULL,
    from_step VARCHAR(80),
    to_step VARCHAR(80),
    FOREIGN KEY (event_id) REFERENCES fm_approval_event (id)
) ENGINE=InnoDB;

INSERT IGNORE INTO fm_workflow_config (flow_type) VALUES
    ('WORKBENCH'), ('STATION_HAZARD'), ('DEPARTMENT_HAZARD'), ('SEGMENT_HAZARD');

CREATE TABLE IF NOT EXISTS fm_feature (
    code VARCHAR(80) PRIMARY KEY
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_fire_unit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    parent_id BIGINT,
    FOREIGN KEY (parent_id) REFERENCES fm_fire_unit (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_fire_user_unit (
    user_id BIGINT NOT NULL,
    unit_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, unit_id),
    FOREIGN KEY (user_id) REFERENCES fm_user (id),
    FOREIGN KEY (unit_id) REFERENCES fm_fire_unit (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_user_profile (
    user_id BIGINT PRIMARY KEY,
    unit_id BIGINT,
    phone VARCHAR(40),
    email VARCHAR(254),
    avatar MEDIUMBLOB,
    avatar_version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (user_id) REFERENCES fm_user (id) ON DELETE CASCADE,
    FOREIGN KEY (unit_id) REFERENCES fm_fire_unit (id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_fire_device (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    unit_id BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL UNIQUE,
    category VARCHAR(30) NOT NULL,
    item_name VARCHAR(100) NOT NULL,
    model_type VARCHAR(100),
    specification VARCHAR(100),
    use_site VARCHAR(180),
    storage_location VARCHAR(180) NOT NULL,
    manufactured_on DATE,
    inspected_on DATE,
    expires_on DATE,
    xfqc_bs TINYINT NOT NULL DEFAULT 2,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    created_role VARCHAR(140) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CHECK (xfqc_bs IN (0, 1, 2)),
    FOREIGN KEY (unit_id) REFERENCES fm_fire_unit (id),
    FOREIGN KEY (created_by) REFERENCES fm_user (id),
    INDEX idx_fire_device_unit_state (unit_id, deleted, xfqc_bs, id),
    INDEX idx_fire_device_expiry (unit_id, deleted, expires_on, id),
    INDEX idx_fire_device_inspection (unit_id, xfqc_bs, deleted, inspected_on, id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_fire_device_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    operation VARCHAR(30) NOT NULL,
    from_state TINYINT,
    to_state TINYINT NOT NULL,
    inspected_on DATE,
    related_device_id BIGINT,
    remark VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (device_id) REFERENCES fm_fire_device (id),
    FOREIGN KEY (actor_id) REFERENCES fm_user (id),
    FOREIGN KEY (related_device_id) REFERENCES fm_fire_device (id),
    INDEX idx_device_event_order (device_id, id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_fire_maintainer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    contact VARCHAR(60) NOT NULL,
    phone VARCHAR(40) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_fire_maintainer_unit (
    maintainer_id BIGINT NOT NULL,
    unit_id BIGINT NOT NULL,
    PRIMARY KEY (maintainer_id, unit_id),
    FOREIGN KEY (maintainer_id) REFERENCES fm_fire_maintainer (id),
    FOREIGN KEY (unit_id) REFERENCES fm_fire_unit (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_fire_maintainer_device (
    maintainer_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    PRIMARY KEY (maintainer_id, device_id),
    FOREIGN KEY (maintainer_id) REFERENCES fm_fire_maintainer (id),
    FOREIGN KEY (device_id) REFERENCES fm_fire_device (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_fire_drill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    unit_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    drilled_at DATETIME NOT NULL,
    participant_count INT NOT NULL,
    summary TEXT,
    version INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (unit_id) REFERENCES fm_fire_unit (id),
    FOREIGN KEY (created_by) REFERENCES fm_user (id),
    INDEX idx_fire_drill_unit_time (unit_id, drilled_at, id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_fire_drill_file (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    drill_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    name VARCHAR(180) NOT NULL,
    storage_key VARCHAR(80) NOT NULL UNIQUE,
    content_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (drill_id) REFERENCES fm_fire_drill (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fm_fire_approval_unit (
    approval_id BIGINT PRIMARY KEY,
    unit_id BIGINT NOT NULL,
    FOREIGN KEY (approval_id) REFERENCES fm_approval (id),
    FOREIGN KEY (unit_id) REFERENCES fm_fire_unit (id),
    INDEX idx_fire_approval_unit (unit_id, approval_id)
) ENGINE=InnoDB;
