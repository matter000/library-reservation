-- =====================================================================
-- V22：填上 t_user.student_no（与 h2/V22 同义，B7 / PRD §12-24）
--
-- 列自 V2 就在、但 Java 从没读过 ⇒ schema 承诺了一个不存在的功能。
-- 老库靠本迁移补齐，新库由 DataInitializer 写入，两处取值逐字一致（见 DESIGN §14.3）。
-- 对照：student1=202401001 / student2=202401002 / student3=202401003 /
--       teacher1=JS2024001 / librarian1=JS2024002 / admin 留空。
-- WHERE 带 student_no IS NULL：不覆盖管理员手工填过的值。
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
