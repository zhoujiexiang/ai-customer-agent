package com.demo.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.agent.entity.MockLogistics;
import com.demo.agent.entity.MockOrder;
import com.demo.agent.mapper.MockLogisticsMapper;
import com.demo.agent.mapper.MockOrderMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表 —— Agent 能做的事全在这里。
 * <p>
 * 四个工具分两类：只读（查订单、查物流）直接执行；写操作（申请退款、改地址）
 * 会真实修改数据，必须先经过用户二次确认，见 {@code ChatService} 的挂起逻辑。
 * <p>
 * 工具描述本身是 Prompt 的一部分：模型只能依据描述判断「该不该用这个工具」，
 * 所以每个 description 都写清了使用场景和前置条件，这是降低误调用率最直接的手段。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    private final MockOrderMapper orderMapper;
    private final MockLogisticsMapper logisticsMapper;

    public ToolRegistry(MockOrderMapper orderMapper, MockLogisticsMapper logisticsMapper) {
        this.orderMapper = orderMapper;
        this.logisticsMapper = logisticsMapper;
        registerDefaults();
    }

    // ------------------------------------------------------------------
    // 对外能力
    // ------------------------------------------------------------------

    /** 生成给 LLM 的 tools 参数（OpenAI Function Calling 的 JSON Schema 数组） */
    public ArrayNode schema() {
        ArrayNode arr = mapper.createArrayNode();
        for (ToolDefinition def : tools.values()) {
            ObjectNode tool = arr.addObject();
            tool.put("type", "function");
            ObjectNode fn = tool.putObject("function");
            fn.put("name", def.name());
            fn.put("description", def.description());

            ObjectNode params = fn.putObject("parameters");
            params.put("type", "object");
            params.set("properties", mapper.valueToTree(def.parameters()));
            ArrayNode required = params.putArray("required");
            def.required().forEach(required::add);
            // 额外参数一律拒绝：模型偶尔会凭想象多塞字段，放行只会让参数校验失去意义
            params.put("additionalProperties", false);
        }
        return arr;
    }

    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 工具清单，供前端/文档展示 */
    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolDefinition def : tools.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", def.name());
            item.put("label", def.label());
            item.put("description", def.description());
            item.put("write", def.write());
            item.put("required", def.required());
            list.add(item);
        }
        return list;
    }

    private void register(String name, String label, String description, boolean write,
                          Map<String, Object> params, List<String> required,
                          java.util.function.Function<JsonNode, ToolResult> handler) {
        tools.put(name, new ToolDefinition(name, label, description, write, params, required, handler));
    }

    // ------------------------------------------------------------------
    // 工具定义
    // ------------------------------------------------------------------

    private void registerDefaults() {

        // ---------------- 只读 1：查订单 ----------------
        register("query_order", "查询订单",
                "根据订单号查询订单详情，返回商品名称、实付金额、订单状态和收货地址。"
                        + "当用户询问订单进度、订单金额、是否已发货、收货地址时使用。"
                        + "如果用户没有提供订单号，不要调用本工具，先向用户索要订单号。",
                false,
                Map.of("orderNo", prop("string", "订单号，通常是 12 位数字，例如 202610010001")),
                List.of("orderNo"),
                args -> {
                    String orderNo = text(args, "orderNo");
                    if (orderNo.isEmpty()) {
                        return ToolResult.fail("未提供订单号");
                    }
                    MockOrder order = findByOrderNo(orderNo);
                    if (order == null) {
                        return ToolResult.fail("订单号 " + orderNo + " 不存在，请确认后重新提供");
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("订单号", order.getOrderNo());
                    data.put("商品", order.getProductName());
                    data.put("实付金额", order.getAmount() == null ? "" : order.getAmount().toPlainString() + " 元");
                    data.put("订单状态", order.getStatus());
                    data.put("收货地址", maskAddress(order.getReceiverAddress()));
                    data.put("下单时间", format(order.getCreatedAt()));
                    return ToolResult.ok("订单 " + orderNo + " 当前状态：" + order.getStatus(), data);
                });

        // ---------------- 只读 2：查物流 ----------------
        register("query_logistics", "查询物流",
                "根据订单号查询物流轨迹，返回最近的运输节点。"
                        + "当用户询问快递到哪了、什么时候能送到、物流信息时使用。"
                        + "待发货订单没有物流记录，工具会明确告知这一点。",
                false,
                Map.of("orderNo", prop("string", "订单号，12 位数字")),
                List.of("orderNo"),
                args -> {
                    String orderNo = text(args, "orderNo");
                    if (orderNo.isEmpty()) {
                        return ToolResult.fail("未提供订单号");
                    }
                    MockOrder order = findByOrderNo(orderNo);
                    if (order == null) {
                        return ToolResult.fail("订单号 " + orderNo + " 不存在，请确认后重新提供");
                    }
                    List<MockLogistics> nodes = logisticsMapper.selectList(
                            new LambdaQueryWrapper<MockLogistics>()
                                    .eq(MockLogistics::getOrderNo, orderNo)
                                    .orderByDesc(MockLogistics::getNodeTime)
                                    .last("limit 8"));
                    if (nodes.isEmpty()) {
                        return ToolResult.ok("订单 " + orderNo + " 暂无物流记录，当前状态为「"
                                + order.getStatus() + "」，通常是尚未发货", Map.of("订单状态", order.getStatus()));
                    }
                    List<Map<String, Object>> traces = new ArrayList<>();
                    for (MockLogistics node : nodes) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("时间", format(node.getNodeTime()));
                        item.put("轨迹", node.getNodeDesc());
                        item.put("操作方", node.getOperator());
                        traces.add(item);
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("订单号", orderNo);
                    data.put("最新状态", nodes.get(0).getNodeDesc());
                    data.put("轨迹", traces);
                    return ToolResult.ok("订单 " + orderNo + " 最新物流：" + nodes.get(0).getNodeDesc(), data);
                });

        // ---------------- 写 1：申请退款（需确认） ----------------
        register("apply_refund", "申请退款",
                "为指定订单提交退款申请，会真实改变订单状态，属于不可逆的写操作。"
                        + "当用户明确要求退款、退货退款、取消订单并退款时使用。"
                        + "调用前必须已经从用户处获得订单号和退款原因；"
                        + "如果用户只说了「我要退款」但没给订单号，先索要订单号，不要调用本工具。",
                true,
                Map.of(
                        "orderNo", prop("string", "订单号，12 位数字"),
                        "reason", prop("string", "退款原因，例如「商品有质量问题」「拍错了」「不想要了」")),
                List.of("orderNo", "reason"),
                args -> {
                    String orderNo = text(args, "orderNo");
                    String reason = text(args, "reason");
                    if (orderNo.isEmpty()) {
                        return ToolResult.fail("未提供订单号");
                    }
                    if (reason.isEmpty()) {
                        return ToolResult.fail("未提供退款原因");
                    }
                    MockOrder order = findByOrderNo(orderNo);
                    if (order == null) {
                        return ToolResult.fail("订单号 " + orderNo + " 不存在，请确认后重新提供");
                    }
                    String status = order.getStatus();
                    if ("已退款".equals(status) || "退款中".equals(status)) {
                        return ToolResult.fail("订单 " + orderNo + " 已经是「" + status + "」状态，无需重复申请");
                    }
                    order.setStatus("退款中");
                    orderMapper.updateById(order);

                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("订单号", orderNo);
                    data.put("商品", order.getProductName());
                    data.put("退款金额", order.getAmount() == null ? "" : order.getAmount().toPlainString() + " 元");
                    data.put("退款原因", reason);
                    data.put("新状态", "退款中");
                    data.put("预计到账", "原路退回，微信/支付宝 1-3 个工作日，银行卡 3-7 个工作日");
                    return ToolResult.ok("已为订单 " + orderNo + " 提交退款申请", data);
                });

        // ---------------- 写 2：修改收货地址（需确认） ----------------
        register("update_address", "修改收货地址",
                "修改指定订单的收货地址，会真实改变订单数据，属于不可逆的写操作。"
                        + "仅「待发货」状态的订单可以修改，已发货订单无法修改地址。"
                        + "调用前必须已经从用户处获得订单号和完整的新地址。",
                true,
                Map.of(
                        "orderNo", prop("string", "订单号，12 位数字"),
                        "newAddress", prop("string", "新的完整收货地址")),
                List.of("orderNo", "newAddress"),
                args -> {
                    String orderNo = text(args, "orderNo");
                    String newAddress = text(args, "newAddress");
                    if (orderNo.isEmpty()) {
                        return ToolResult.fail("未提供订单号");
                    }
                    if (newAddress.isEmpty()) {
                        return ToolResult.fail("未提供新地址");
                    }
                    MockOrder order = findByOrderNo(orderNo);
                    if (order == null) {
                        return ToolResult.fail("订单号 " + orderNo + " 不存在，请确认后重新提供");
                    }
                    if (!"待发货".equals(order.getStatus())) {
                        return ToolResult.fail("订单 " + orderNo + " 当前状态为「" + order.getStatus()
                                + "」，已发货的订单无法修改收货地址，请引导用户联系快递方改址或申请退款重拍");
                    }
                    String oldAddress = order.getReceiverAddress();
                    order.setReceiverAddress(newAddress);
                    orderMapper.updateById(order);

                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("订单号", orderNo);
                    data.put("原地址", maskAddress(oldAddress));
                    data.put("新地址", newAddress);
                    return ToolResult.ok("订单 " + orderNo + " 收货地址已修改", data);
                });
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private MockOrder findByOrderNo(String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<MockOrder>()
                .eq(MockOrder::getOrderNo, orderNo.trim())
                .last("limit 1"));
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("description", description);
        return map;
    }

    /** 从模型生成的 arguments 里安全取字符串，缺失或类型不对都返回空串 */
    private static String text(JsonNode args, String field) {
        if (args == null || args.isNull() || args.isMissingNode()) {
            return "";
        }
        JsonNode node = args.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.asText("").trim();
    }

    private static String format(LocalDateTime time) {
        return time == null ? "" : time.format(TIME_FMT);
    }

    /** 地址脱敏：只保留到区/县一级，避免工具输出把完整住址回灌进模型上下文 */
    private static String maskAddress(String address) {
        if (address == null) {
            return "";
        }
        return address.length() <= 12 ? address : address.substring(0, 12) + "****";
    }
}
