# -*- coding: utf-8 -*-
"""规则臂（rules_arm.select_rules）离线契约测试：零模型、纯确定性。"""

import unittest

from rules_arm import select_rules, _normalize


def case(items, max_items=20, max_chars=8000):
    evidence = [{"id": e[0], "text": e[1], "required": bool(e[2]) if len(e) > 2 else False}
                for e in items]
    return {"evidence": evidence}, {"max_items": max_items, "max_chars": max_chars}


class RulesArmTest(unittest.TestCase):

    def test_必保留恒入选_即使无关键词(self):
        c, o = case([("e1", "everything is fine"), ("e2", "error downstream", 1)])
        picked = select_rules(c, o)
        ids = [e["id"] for e in picked]
        self.assertIn("e2", ids)

    def test_同模板去重_保首条(self):
        c, o = case([("e1", "error connection refused 12"), ("e2", "error connection refused 34"),
                     ("e3", "different signature ok")])
        picked = select_rules(c, o)
        ids = [e["id"] for e in picked]
        self.assertIn("e1", ids)
        self.assertNotIn("e2", ids)  # 同模板（数字归一后同形）只保首条
        self.assertIn("e3", ids)

    def test_条数预算截断_保持原始顺序(self):
        # 30 条互不同模板的行（字母后缀，数字归一不合并它们）
        suffixes = [chr(ord('a') + i // 26) + chr(ord('a') + i % 26) for i in range(30)]
        items = [("e%d" % i, "plain row %s" % suffixes[i]) for i in range(30)]
        c, o = case(items, max_items=5)
        picked = select_rules(c, o)
        self.assertEqual(len(picked), 5)
        self.assertEqual([e["id"] for e in picked],
                         ["e0", "e1", "e2", "e3", "e4"])  # 原序补足，不重排

    def test_关键词优先于原序补足(self):
        items = [("e0", "plain"), ("e1", "plain"), ("e2", "plain"),
                 ("ek", "timeout while calling payment")]
        c, o = case(items, max_items=2)
        picked = select_rules(c, o)
        ids = [e["id"] for e in picked]
        self.assertIn("ek", ids)          # 关键词命中先于原序补足
        self.assertEqual(len(ids), 2)

    def test_必保留超预算_任何选择之前拒绝(self):
        c, o = case([("e1", "x" * 9000, 1)], max_chars=8000)
        with self.assertRaises(ValueError):
            select_rules(c, o)

    def test_字符预算_非必保留丢尾_必保留不丢(self):
        items = [("req", "r" * 5000, 1), ("big", "b" * 6000), ("small", "s" * 100)]
        c, o = case(items, max_chars=8000)
        picked = select_rules(c, o)
        ids = [e["id"] for e in picked]
        self.assertIn("req", ids)   # 必保留永不因预算被丢
        self.assertNotIn("big", ids)  # 超预算非必保留丢尾
        self.assertIn("small", ids)

    def test_确定性_同输入同输出(self):
        items = [("e1", "error a"), ("e2", "plain"), ("e3", "error a")]
        c, o = case(items)
        self.assertEqual(select_rules(c, o), select_rules(c, o))

    def test_归一化锚_uuid与数字同形(self):
        self.assertEqual(_normalize("err id=550e8400-e29b-41d4-a716-446655440000"),
                         _normalize("err id=550e8400-e29b-41d4-a716-446655440001"))
        self.assertEqual(_normalize("code 12345"), _normalize("code 999"))


if __name__ == "__main__":
    unittest.main()
