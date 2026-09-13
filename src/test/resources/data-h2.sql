-- 来自上传的 school.sql 的真实样例数据（精简列）。

INSERT INTO clazz VALUES (1, '计科2601班', '2026级');
INSERT INTO clazz VALUES (2, '软件2602班', '2026级');
INSERT INTO clazz VALUES (3, '大数据2603班', '2026级');

INSERT INTO student VALUES (1, 'S2026001', '张三', 1, '在读');
INSERT INTO student VALUES (2, 'S2026002', '李四', 1, '在读');
INSERT INTO student VALUES (3, 'S2026003', '王五', 2, '在读');
INSERT INTO student VALUES (4, 'S2026004', '赵六', 2, '在读');
INSERT INTO student VALUES (5, 'S2026005', '孙七', 3, '休学');

INSERT INTO student_profile VALUES (1, 1, '110101200501011234', '13800000001');
INSERT INTO student_profile VALUES (2, 2, '110101200502022345', '13800000002');

INSERT INTO teacher VALUES (1, '王教授', '教授');
INSERT INTO teacher VALUES (2, '李副教授', '副教授');
INSERT INTO teacher VALUES (3, '张讲师', '讲师');

INSERT INTO course VALUES (101, 'CS101', '高等数学', 5.0, 1, '已开课');
INSERT INTO course VALUES (102, 'CS102', '数据结构', 4.5, 2, '已开课');
INSERT INTO course VALUES (103, 'CS103', '离散数学', 3.0, 3, '已开课');

INSERT INTO course_syllabus VALUES (1, 101, '高中数学基础', '教学大纲：微积分、线性代数基础、常微分方程。考核方式：期末70%+平时30%');
INSERT INTO course_syllabus VALUES (2, 102, 'C/C++程序设计', '教学大纲：线性表、树与二叉树、图论算法。考核方式：期末60%+上机实验40%');

INSERT INTO course_schedule VALUES (1, 101, 1, '01-02节', '第一教学楼 302');

INSERT INTO student_course VALUES (1, 1, 101, '2026-秋', '高等数学', 95.00);
INSERT INTO student_course VALUES (2, 1, 102, '2026-秋', '数据结构', 88.00);
INSERT INTO student_course VALUES (3, 1, 103, '2026-秋', '离散数学', 92.00);
INSERT INTO student_course VALUES (4, 2, 101, '2026-秋', '高等数学', 85.00);
INSERT INTO student_course VALUES (5, 2, 102, '2026-秋', '数据结构', 90.00);
INSERT INTO student_course VALUES (6, 3, 101, '2026-秋', '高等数学', 78.00);
INSERT INTO student_course VALUES (7, 5, NULL, '2026-秋', '新选课程1', 85.00);
INSERT INTO student_course VALUES (8, 5, NULL, '2026-秋', '新选课程2', 85.00);

INSERT INTO student_course_score_item VALUES (1, 1, '期中测试', 30.00, 96.00, '基础公式推导准确');
INSERT INTO student_course_score_item VALUES (2, 1, '课后作业与大练兵', 30.00, 94.00, '平时习题全勤');
INSERT INTO student_course_score_item VALUES (3, 1, '期末综合大考', 40.00, 95.00, '压轴题解答完整');
INSERT INTO student_course_score_item VALUES (4, 2, '阶段小测一', 25.00, 85.00, '链表与树结构理解透彻');
INSERT INTO student_course_score_item VALUES (5, 2, '算法实验与代码评审', 35.00, 90.00, '红黑树实验一次通过');
INSERT INTO student_course_score_item VALUES (6, 2, '期末闭卷考核', 40.00, 88.00, '动态规划掌握良好');
INSERT INTO student_course_score_item VALUES (8, 3, '图论专题作业', 30.00, 90.00, '证明步骤严谨');
INSERT INTO student_course_score_item VALUES (9, 3, '期末大考', 50.00, 92.00, '总评优秀');

INSERT INTO student_award VALUES (1, 1, '国家奖学金', '国家级', '2026-05-20');
INSERT INTO student_award VALUES (3, 2, '优秀学生干部', '校级', '2026-06-18');
INSERT INTO student_award VALUES (4, 3, 'ACM程序设计大赛二等奖', '省部级', '2026-07-01');
INSERT INTO student_award VALUES (5, 5, '校级三好学生荣誉', '校级', '2026-06-30');

INSERT INTO student_award_detail VALUES (1, 1, '教育部国家奖学金荣誉证书', 'CERT-GJ-2026-001', '中华人民共和国教育部', 10.00);
INSERT INTO student_award_detail VALUES (2, 1, '学年成绩单与综合测评第一名证明', 'ZM-2026-088', '教务处', 5.00);
INSERT INTO student_award_detail VALUES (3, 3, '校团委优秀学生干部荣誉聘书', 'CERT-XGB-2026-03', '共青团委员会', 5.00);
INSERT INTO student_award_detail VALUES (4, 4, 'ACM-ICPC区域赛组委会二等奖奖状', 'ACM-REG-2026-042', 'ACM中国组委会', 8.00);
