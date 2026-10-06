-- =====================================================================
-- V22：把 t_user.student_no 真正填上（B7，PRD §12-24「馆员可见姓名+学号」）
--
-- ★ 这个列从 V2 就建好了（还带 uk_user_student_no 唯一索引），但 Java 侧一行代码都没读它：
--   既没进实体，也没进任何 VO ⇒ schema 在承诺一件产品功能其实不存在的事。
--   B7 的正解是"让列用起来"，不是"再加一列"。
--
-- ★ 为什么要"迁移 + Java 种子"两边都做（B9 的双向规矩）：
--   - 已迁移的老库：用户行早就存在，只有迁移能把学号补进去（`DataInitializer` 见到用户就跳过）；
--   - 空库冷启动：迁移跑的时候 t_user 还是空的、命中 0 行，学号由 `DataInitializer` 写。
--   两处的取值必须**逐字相同**，否则同一个演示账号在两种库里学号不一样。
--   对照表：student1=202401001 / student2=202401002 / student3=202401003 /
--           teacher1=JS2024001 / librarian1=JS2024002 / admin 留空（技术账号无校园号）。
--
-- WHERE 带 `student_no IS NULL`：不覆盖已由管理员在用户面填过的值。
-- =====================================================================

UPDATE t_user SET student_no = CASE username
    WHEN 'student1' THEN '202401001'
    WHEN 'student2' THEN '202401002'
    WHEN 'student3' THEN '202401003'
    WHEN 'teacher1' THEN 'JS2024001'
    WHEN 'librarian1' THEN 'JS2024002'
    END
WHERE username IN ('student1', 'student2', 'student3', 'teacher1', 'librarian1')
  AND student_no IS NULL;
