-- Run once as the application schema owner on Oracle 10g.
CREATE SEQUENCE fm_id_seq START WITH 1 INCREMENT BY 1 CACHE 20 NOCYCLE;

CREATE TABLE fm_user (
    id NUMBER(19) PRIMARY KEY,
    username VARCHAR2(40 CHAR) NOT NULL UNIQUE,
    display_name VARCHAR2(80 CHAR) NOT NULL,
    password_hash VARCHAR2(255 CHAR) NOT NULL,
    enabled NUMBER(1) DEFAULT 1 NOT NULL CHECK (enabled IN (0, 1)),
    auth_version NUMBER(10) DEFAULT 0 NOT NULL,
    created_at TIMESTAMP(0) DEFAULT LOCALTIMESTAMP NOT NULL
);

CREATE TABLE fm_role (
    id NUMBER(19) PRIMARY KEY,
    code VARCHAR2(40 CHAR) NOT NULL UNIQUE,
    name VARCHAR2(80 CHAR) NOT NULL
);

CREATE TABLE fm_user_role (
    user_id NUMBER(19) NOT NULL,
    role_id NUMBER(19) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES fm_user (id),
    FOREIGN KEY (role_id) REFERENCES fm_role (id)
);

CREATE TABLE fm_role_permission (
    role_id NUMBER(19) NOT NULL,
    permission_code VARCHAR2(48 CHAR) NOT NULL,
    PRIMARY KEY (role_id, permission_code),
    FOREIGN KEY (role_id) REFERENCES fm_role (id) ON DELETE CASCADE
);

CREATE TABLE fm_menu (
    id NUMBER(19) PRIMARY KEY,
    parent_id NUMBER(19),
    name VARCHAR2(60 CHAR) NOT NULL,
    view_key VARCHAR2(32 CHAR),
    permission_code VARCHAR2(48 CHAR),
    sort_order NUMBER(10) DEFAULT 0 NOT NULL,
    enabled NUMBER(1) DEFAULT 1 NOT NULL CHECK (enabled IN (0, 1)),
    FOREIGN KEY (parent_id) REFERENCES fm_menu (id)
);

CREATE TABLE fm_approval (
    id NUMBER(19) PRIMARY KEY,
    title VARCHAR2(120 CHAR) NOT NULL,
    location VARCHAR2(120 CHAR) NOT NULL,
    description CLOB NOT NULL,
    reporter_id NUMBER(19) NOT NULL,
    assignee_id NUMBER(19),
    status VARCHAR2(32 CHAR) NOT NULL,
    version NUMBER(10) DEFAULT 0 NOT NULL,
    created_at TIMESTAMP(0) DEFAULT LOCALTIMESTAMP NOT NULL,
    updated_at TIMESTAMP(0) DEFAULT LOCALTIMESTAMP NOT NULL,
    FOREIGN KEY (reporter_id) REFERENCES fm_user (id),
    FOREIGN KEY (assignee_id) REFERENCES fm_user (id)
);

CREATE TABLE fm_approval_event (
    id NUMBER(19) PRIMARY KEY,
    approval_id NUMBER(19) NOT NULL,
    actor_id NUMBER(19) NOT NULL,
    action VARCHAR2(32 CHAR) NOT NULL,
    from_status VARCHAR2(32 CHAR),
    to_status VARCHAR2(32 CHAR) NOT NULL,
    remark CLOB,
    created_at TIMESTAMP(0) DEFAULT LOCALTIMESTAMP NOT NULL,
    FOREIGN KEY (approval_id) REFERENCES fm_approval (id),
    FOREIGN KEY (actor_id) REFERENCES fm_user (id)
);

CREATE TABLE fm_workflow_version (
    id NUMBER(19) PRIMARY KEY,
    flow_type VARCHAR2(32 CHAR) NOT NULL,
    version_no NUMBER(10) NOT NULL,
    start_key VARCHAR2(24 CHAR) NOT NULL,
    published_by NUMBER(19) NOT NULL,
    published_at TIMESTAMP(0) DEFAULT LOCALTIMESTAMP NOT NULL,
    UNIQUE (flow_type, version_no),
    FOREIGN KEY (published_by) REFERENCES fm_user (id)
);

CREATE TABLE fm_workflow_config (
    flow_type VARCHAR2(32 CHAR) PRIMARY KEY,
    revision NUMBER(10) DEFAULT 0 NOT NULL,
    last_version NUMBER(10) DEFAULT 0 NOT NULL,
    active_version_id NUMBER(19),
    draft CLOB,
    FOREIGN KEY (active_version_id) REFERENCES fm_workflow_version (id)
);

CREATE TABLE fm_workflow_step (
    version_id NUMBER(19) NOT NULL,
    step_key VARCHAR2(24 CHAR) NOT NULL,
    display_order NUMBER(10) NOT NULL,
    step_name VARCHAR2(80 CHAR) NOT NULL,
    role_id NUMBER(19) NOT NULL,
    role_name VARCHAR2(80 CHAR) NOT NULL,
    unit_scope VARCHAR2(24 CHAR) NOT NULL,
    assignee_only NUMBER(1) NOT NULL CHECK (assignee_only IN (0, 1)),
    pass_label VARCHAR2(30 CHAR) NOT NULL,
    remark_required NUMBER(1) NOT NULL CHECK (remark_required IN (0, 1)),
    pass_to VARCHAR2(24 CHAR),
    return_to VARCHAR2(24 CHAR),
    PRIMARY KEY (version_id, step_key),
    FOREIGN KEY (version_id) REFERENCES fm_workflow_version (id)
);

CREATE TABLE fm_approval_route (
    approval_id NUMBER(19) PRIMARY KEY,
    version_id NUMBER(19) NOT NULL,
    step_key VARCHAR2(24 CHAR),
    FOREIGN KEY (approval_id) REFERENCES fm_approval (id),
    FOREIGN KEY (version_id) REFERENCES fm_workflow_version (id),
    FOREIGN KEY (version_id, step_key) REFERENCES fm_workflow_step (version_id, step_key)
);

CREATE TABLE fm_approval_step_owner (
    approval_id NUMBER(19) NOT NULL,
    step_key VARCHAR2(24 CHAR) NOT NULL,
    user_id NUMBER(19) NOT NULL,
    PRIMARY KEY (approval_id, step_key),
    FOREIGN KEY (approval_id) REFERENCES fm_approval_route (approval_id),
    FOREIGN KEY (user_id) REFERENCES fm_user (id)
);

CREATE TABLE fm_approval_flow_event (
    event_id NUMBER(19) PRIMARY KEY,
    action_label VARCHAR2(30 CHAR) NOT NULL,
    from_step VARCHAR2(80 CHAR),
    to_step VARCHAR2(80 CHAR),
    FOREIGN KEY (event_id) REFERENCES fm_approval_event (id)
);

CREATE TABLE fm_feature (
    code VARCHAR2(80 CHAR) PRIMARY KEY
);

CREATE TABLE fm_fire_unit (
    id NUMBER(19) PRIMARY KEY,
    code VARCHAR2(40 CHAR) NOT NULL UNIQUE,
    name VARCHAR2(100 CHAR) NOT NULL,
    kind VARCHAR2(24 CHAR) NOT NULL,
    parent_id NUMBER(19),
    FOREIGN KEY (parent_id) REFERENCES fm_fire_unit (id)
);

CREATE TABLE fm_fire_user_unit (
    user_id NUMBER(19) NOT NULL,
    unit_id NUMBER(19) NOT NULL,
    PRIMARY KEY (user_id, unit_id),
    FOREIGN KEY (user_id) REFERENCES fm_user (id),
    FOREIGN KEY (unit_id) REFERENCES fm_fire_unit (id)
);

CREATE TABLE fm_user_profile (
    user_id NUMBER(19) PRIMARY KEY,
    unit_id NUMBER(19),
    phone VARCHAR2(40 CHAR),
    email VARCHAR2(254 CHAR),
    avatar BLOB,
    avatar_version NUMBER(19) DEFAULT 0 NOT NULL,
    FOREIGN KEY (user_id) REFERENCES fm_user (id) ON DELETE CASCADE,
    FOREIGN KEY (unit_id) REFERENCES fm_fire_unit (id) ON DELETE SET NULL
);

CREATE TABLE fm_fire_device (
    id NUMBER(19) PRIMARY KEY,
    unit_id NUMBER(19) NOT NULL,
    code VARCHAR2(64 CHAR) NOT NULL,
    category VARCHAR2(30 CHAR) NOT NULL,
    item_name VARCHAR2(100 CHAR) NOT NULL,
    model_type VARCHAR2(100 CHAR),
    specification VARCHAR2(100 CHAR),
    use_site VARCHAR2(180 CHAR),
    storage_location VARCHAR2(180 CHAR) NOT NULL,
    manufactured_on DATE,
    inspected_on DATE,
    expires_on DATE,
    xfqc_bs NUMBER(3) DEFAULT 2 NOT NULL,
    deleted NUMBER(1) DEFAULT 0 NOT NULL CHECK (deleted IN (0, 1)),
    version NUMBER(10) DEFAULT 0 NOT NULL,
    created_by NUMBER(19) NOT NULL,
    created_role VARCHAR2(140 CHAR) NOT NULL,
    created_at TIMESTAMP(0) DEFAULT LOCALTIMESTAMP NOT NULL,
    updated_at TIMESTAMP(0) DEFAULT LOCALTIMESTAMP NOT NULL,
    CHECK (xfqc_bs IN (0, 1, 2)),
    FOREIGN KEY (unit_id) REFERENCES fm_fire_unit (id),
    FOREIGN KEY (created_by) REFERENCES fm_user (id)
);

CREATE TABLE fm_fire_device_event (
    id NUMBER(19) PRIMARY KEY,
    device_id NUMBER(19) NOT NULL,
    actor_id NUMBER(19) NOT NULL,
    operation VARCHAR2(30 CHAR) NOT NULL,
    from_state NUMBER(3),
    to_state NUMBER(3) NOT NULL,
    inspected_on DATE,
    related_device_id NUMBER(19),
    remark VARCHAR2(500 CHAR),
    created_at TIMESTAMP(0) DEFAULT LOCALTIMESTAMP NOT NULL,
    FOREIGN KEY (device_id) REFERENCES fm_fire_device (id),
    FOREIGN KEY (actor_id) REFERENCES fm_user (id),
    FOREIGN KEY (related_device_id) REFERENCES fm_fire_device (id)
);

CREATE TABLE fm_fire_maintainer (
    id NUMBER(19) PRIMARY KEY,
    name VARCHAR2(160 CHAR) NOT NULL,
    contact VARCHAR2(60 CHAR) NOT NULL,
    phone VARCHAR2(40 CHAR) NOT NULL,
    deleted NUMBER(1) DEFAULT 0 NOT NULL CHECK (deleted IN (0, 1)),
    version NUMBER(10) DEFAULT 0 NOT NULL,
    created_at TIMESTAMP(0) DEFAULT LOCALTIMESTAMP NOT NULL
);

CREATE TABLE fm_fire_maintainer_unit (
    maintainer_id NUMBER(19) NOT NULL,
    unit_id NUMBER(19) NOT NULL,
    PRIMARY KEY (maintainer_id, unit_id),
    FOREIGN KEY (maintainer_id) REFERENCES fm_fire_maintainer (id),
    FOREIGN KEY (unit_id) REFERENCES fm_fire_unit (id)
);

CREATE TABLE fm_fire_maintainer_device (
    maintainer_id NUMBER(19) NOT NULL,
    device_id NUMBER(19) NOT NULL,
    PRIMARY KEY (maintainer_id, device_id),
    FOREIGN KEY (maintainer_id) REFERENCES fm_fire_maintainer (id),
    FOREIGN KEY (device_id) REFERENCES fm_fire_device (id)
);

CREATE TABLE fm_fire_drill (
    id NUMBER(19) PRIMARY KEY,
    unit_id NUMBER(19) NOT NULL,
    title VARCHAR2(160 CHAR) NOT NULL,
    drilled_at TIMESTAMP(0) NOT NULL,
    participant_count NUMBER(10) NOT NULL,
    summary CLOB,
    version NUMBER(10) DEFAULT 0 NOT NULL,
    created_by NUMBER(19) NOT NULL,
    created_at TIMESTAMP(0) DEFAULT LOCALTIMESTAMP NOT NULL,
    FOREIGN KEY (unit_id) REFERENCES fm_fire_unit (id),
    FOREIGN KEY (created_by) REFERENCES fm_user (id)
);

CREATE TABLE fm_fire_drill_file (
    id NUMBER(19) PRIMARY KEY,
    drill_id NUMBER(19) NOT NULL,
    kind VARCHAR2(20 CHAR) NOT NULL,
    name VARCHAR2(180 CHAR) NOT NULL,
    storage_key VARCHAR2(80 CHAR) NOT NULL UNIQUE,
    content_type VARCHAR2(120 CHAR) NOT NULL,
    size_bytes NUMBER(19) NOT NULL,
    created_at TIMESTAMP(0) DEFAULT LOCALTIMESTAMP NOT NULL,
    FOREIGN KEY (drill_id) REFERENCES fm_fire_drill (id)
);

CREATE TABLE fm_fire_approval_unit (
    approval_id NUMBER(19) PRIMARY KEY,
    unit_id NUMBER(19) NOT NULL,
    FOREIGN KEY (approval_id) REFERENCES fm_approval (id),
    FOREIGN KEY (unit_id) REFERENCES fm_fire_unit (id)
);

CREATE INDEX idx_approval_status_order ON fm_approval (status, id);
CREATE INDEX idx_approval_reporter_order ON fm_approval (reporter_id, id);
CREATE INDEX idx_approval_assignee_status ON fm_approval (assignee_id, status, id);
CREATE INDEX idx_approval_event_order ON fm_approval_event (approval_id, id);
CREATE INDEX idx_approval_event_actor ON fm_approval_event (actor_id, approval_id);
CREATE INDEX idx_workflow_step_role ON fm_workflow_step (role_id, version_id);
CREATE UNIQUE INDEX uq_fire_device_code_ci ON fm_fire_device (LOWER(code));
CREATE INDEX idx_fire_device_unit_state ON fm_fire_device (unit_id, deleted, xfqc_bs, id);
CREATE INDEX idx_fire_device_expiry ON fm_fire_device (unit_id, deleted, expires_on, id);
CREATE INDEX idx_fire_device_inspection ON fm_fire_device (unit_id, xfqc_bs, deleted, inspected_on, id);
CREATE INDEX idx_device_event_order ON fm_fire_device_event (device_id, id);
CREATE INDEX idx_fire_drill_unit_time ON fm_fire_drill (unit_id, drilled_at, id);
CREATE INDEX idx_fire_approval_unit ON fm_fire_approval_unit (unit_id, approval_id);

INSERT INTO fm_workflow_config (flow_type) VALUES ('WORKBENCH');
INSERT INTO fm_workflow_config (flow_type) VALUES ('STATION_HAZARD');
INSERT INTO fm_workflow_config (flow_type) VALUES ('DEPARTMENT_HAZARD');
INSERT INTO fm_workflow_config (flow_type) VALUES ('SEGMENT_HAZARD');
COMMIT;
