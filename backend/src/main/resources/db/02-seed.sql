-- ==========================================================
-- 种子数据：模拟订单与物流
-- 说明：管理员账号不在此处创建，由后端 DataInitializer 启动时
--       用 BCrypt 加密写入，避免密文硬编码。
-- ==========================================================

-- ----------------------------------------------------------
-- 30 条订单，覆盖四种状态
-- ----------------------------------------------------------
INSERT INTO mock_order (order_no, user_phone, product_name, amount, status, receiver_address, created_at)
SELECT
    '2026100100' || LPAD(i::text, 2, '0'),
    '138' || LPAD((10000000 + i * 137)::text, 8, '0'),
    (ARRAY[
        '小米 15 Pro 手机 12+256G',
        '戴森吹风机 HD15',
        '耐克跑鞋 Air Zoom Pegasus',
        '罗技鼠标 MX Master 3S',
        '美的空气炸锅 5L 智能款'
    ])[1 + (i % 5)],
    ROUND((99 + i * 37.5)::numeric, 2),
    (ARRAY['待发货', '已发货', '已签收', '已完成'])[1 + (i % 4)],
    (ARRAY[
        '北京市朝阳区建国路 88 号 SOHO 现代城 A 座 1801',
        '上海市浦东新区世纪大道 100 号环球金融中心 25 层',
        '广州市天河区体育西路 12 号维多利广场 B 塔 903'
    ])[1 + (i % 3)],
    NOW() - (i || ' days')::interval
FROM generate_series(1, 30) AS i
ON CONFLICT (order_no) DO NOTHING;

-- ----------------------------------------------------------
-- 每条已发货的订单生成 5 个物流节点
-- 注意：待发货的订单没有物流轨迹，这本身就是 Agent 需要处理的分支
-- ----------------------------------------------------------
INSERT INTO mock_logistics (order_no, node_time, node_desc, operator)
SELECT
    o.order_no,
    o.created_at + ((n * 6) || ' hours')::interval,
    CASE n
        WHEN 1 THEN '商家已发货，包裹交给快递'
        WHEN 2 THEN '包裹已揽收'
        WHEN 3 THEN '到达分拣中心'
        WHEN 4 THEN '运输中，下一站派送网点'
        ELSE '已签收，感谢使用云集商城'
    END,
    (ARRAY['北京朝阳网点', '上海浦东网点', '广州天河网点'])[1 + (n % 3)]
FROM mock_order o
CROSS JOIN generate_series(1, 5) AS n
WHERE o.status <> '待发货';

-- ----------------------------------------------------------
-- 校验
-- ----------------------------------------------------------
DO $$
DECLARE
    order_cnt     INT;
    logistics_cnt INT;
BEGIN
    SELECT COUNT(*) INTO order_cnt FROM mock_order;
    SELECT COUNT(*) INTO logistics_cnt FROM mock_logistics;
    RAISE NOTICE '种子数据完成：订单 % 条，物流节点 % 条', order_cnt, logistics_cnt;
END $$;
