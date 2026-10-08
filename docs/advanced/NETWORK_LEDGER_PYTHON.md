# 独立Python网络与数据流证据检查

## 运行方式

[verify-network-ledger.py](<../../scripts/verify-network-ledger.py>)只依赖Python标准库（Python 3.9+，CI使用3.12）。检查时不调用JVM、Maven、生产求解器或网络服务，不改写输入文件。

```bash
# 推荐：核对完整工件及其运行上下文
python3 -B scripts/verify-network-ledger.py path/to/result.manifest.json --json

# 单独检查侧车内部契约；不冒称验证外层运行
python3 -B scripts/verify-network-ledger.py path/to/result.network-ledger.json --json
python3 -B scripts/verify-network-ledger.py path/to/result.file-lifecycle.json --json
python3 -B scripts/verify-network-ledger.py path/to/result.storage-lifecycle.json --json
```

manifest模式只认登记的工件角色，核对受限相对路径、SHA256、文件大小、配置及上下文；目录中未引用的旧侧车不会把OFF变成ON。测试与生产端夹具生成命令统一见[构建指南](<../getting-started/BUILD.md>)。

## 分派与证明范围

| 输入契约 | 检查内容 | 不能据此声称 |
|---|---|---|
| V1组级账本 | 流体记录语法、稳定分配容量/max-min证书、精确会计、派生指标及可用上下文 | 逐文件来源或TCP连接重放 |
| 文件生命周期V2 | 文件计划、来源/路径、复制/可见性、输入及CPU因果 | 未记录的逐区间流体服务会计 |
| 存储生命周期V3 | V2式逐文件检查，加受限SOURCE、提交等待、成功输出义务和对应上下文 | 复杂存储一致性、缓存淘汰或存储故障 |
| 显式在线目标绑定 | 完整生命周期支持下的绑定、PE/MI、CPU预留、名义输入边界和已记录分数 | 精确在途余额、未来争用或全局最优性 |

在线动作检查通过后仍明确报告`liveProgressReplayed=false`。ON账本以及已识别的V2/V3/在线扩展会进入相应的模式、物理Kind、文件系统和角色检查；不能把这些已知扩展的不完整声明当作普通OFF绕过。

普通旧模式的OFF入口范围更窄：只检查manifest版本、工件路径/哈希/大小和记录开关/角色关系，然后返回`contextualRunChecked=false`；它不验证完整配置支持矩阵，也不保证拒绝未知模型Kind。需要完整配置/运行验证时使用相应Java工件校验器，不能将此处的`DISABLED`当作全配置认证。

## 结果状态

- `VALID_COMPLETE`：完整捕获在声明范围内检查通过；standalone的完整前缀仍不等于整个仿真已经结束。
- `VALID_PREFIX`：V1截断前缀的必要约束通过，`completeCaptureCertified=false`；不发布完整流量/FCT认证，不证明未知后缀存在。
- `DISABLED`：允许的未记录模式通过相应基础检查，不是“认证零流量”。V2/V3可保留核心计划，但在线动作认证不能用OFF替代完整生命周期。
- `EVIDENCE_INVALID`：格式、引用、数值或语义不一致，CLI以非零退出。

同时阅读输出中的scope及上下文标记。单独侧车不包含完整运行结果，不能与manifest入口的检查范围混为一谈。

## 独立性与数值处理

实现入口为[_network_audit.py](<../../scripts/_network_audit.py>)，由契约分派到[_file_lifecycle_audit.py](<../../scripts/_file_lifecycle_audit.py>)、[_storage_lifecycle_audit.py](<../../scripts/_storage_lifecycle_audit.py>)及[_dataflow_assignment_audit.py](<../../scripts/_dataflow_assignment_audit.py>)等专用检查器。

- 时间、速率和余额按所声明的binary64标量规则检查；精确int/long和十进制聚合不通过有损double转换。
- V1利用`Fraction.from_float`取得记录中binary64值的精确有理数，累计余额差、速率面积及容量负载；均值/利用率的舍入遵守其专用契约。
- max-min稳定分配使用瓶颈证书验证，不重新调用生产分配器；截断尾部只检查已固定事实和必要上下界，不搜索未知历史。
- V2/V3独立重建逻辑文件、出处、路径和生命周期；在线动作使用请求前状态检查名义边界与分数。处于允许边界内的进度变化仍会在[精确重放](<../experiments/RERUN_DIFF_CONTRACT.md>)中形成核心差异。
- 拒绝重复JSON键、非标准常量、类型伪装、缺字段、超深结构、超范围数字、目录逃逸及NUL文件名。允许跨运行时等价的最小binary64数值拼写，但不接受非零数值无声下溢为零。

这不是对被一致重写的所有证据的密码学真实性证明。一般工作流统计与研究汇总另由[研究检查工具](<../../scripts/STUDY_AUDIT.md>)处理；各工具只对自己的声明范围负责。
