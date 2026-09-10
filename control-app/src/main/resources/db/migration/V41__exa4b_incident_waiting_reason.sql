-- EX-A4b（F24）：事故受理未调查的显式等待态——WAITING_CAPABILITY（路由不意愿）/
-- DEFERRED（背压暂扣）；null = 无等待。存量行不加写（默认 NULL 即"无等待"，
-- 存量迁移兼容 = 不分裂既有事故、不改键与哈希面）。重驱扫描见 IncidentWaitingRedrive。
ALTER TABLE incident ADD COLUMN waiting_reason TEXT;
