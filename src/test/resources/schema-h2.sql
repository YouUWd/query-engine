-- 精简版 school 库结构（H2 方言），只保留查询引擎用到的列。
-- 完整版见上传的 school.sql（MySQL 方言，含 charset/collation/索引等 H2 不需要的子句）。

CREATE TABLE clazz (
    id BIGINT PRIMARY KEY,
    clazz_name VARCHAR(64) NOT NULL,
    grade VARCHAR(32) NOT NULL
);

CREATE TABLE student (
    id BIGINT PRIMARY KEY,
    student_no VARCHAR(32) NOT NULL,
    name VARCHAR(32) NOT NULL,
    clazz_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL
);

CREATE TABLE student_profile (
    id BIGINT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    id_card VARCHAR(32) NOT NULL,
    emergency_phone VARCHAR(32)
);

CREATE TABLE teacher (
    id BIGINT PRIMARY KEY,
    teacher_name VARCHAR(32) NOT NULL,
    title VARCHAR(32) NOT NULL
);

CREATE TABLE course (
    id BIGINT PRIMARY KEY,
    course_code VARCHAR(32) NOT NULL,
    course_name VARCHAR(64) NOT NULL,
    credit DECIMAL(3,1) NOT NULL,
    teacher_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL
);

CREATE TABLE course_syllabus (
    id BIGINT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    prerequisite VARCHAR(128),
    syllabus_content CLOB
);

CREATE TABLE course_schedule (
    id BIGINT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    day_of_week TINYINT NOT NULL,
    time_slot VARCHAR(32) NOT NULL,
    classroom VARCHAR(64) NOT NULL
);

CREATE TABLE student_course (
    id BIGINT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    course_id BIGINT,
    semester VARCHAR(32) NOT NULL,
    course_name VARCHAR(64) NOT NULL,
    score DECIMAL(5,2)
);

CREATE TABLE student_course_score_item (
    id BIGINT PRIMARY KEY,
    student_course_id BIGINT NOT NULL,
    item_name VARCHAR(100) NOT NULL,
    weight DECIMAL(5,2) NOT NULL,
    score DECIMAL(5,2) NOT NULL,
    remark VARCHAR(255)
);

CREATE TABLE student_award (
    id BIGINT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    award_name VARCHAR(64) NOT NULL,
    level VARCHAR(50),
    award_date VARCHAR(32) NOT NULL
);

CREATE TABLE student_award_detail (
    id BIGINT PRIMARY KEY,
    award_id BIGINT NOT NULL,
    evidence_name VARCHAR(128) NOT NULL,
    evidence_no VARCHAR(64),
    issuing_authority VARCHAR(128),
    award_score DECIMAL(5,2)
);
