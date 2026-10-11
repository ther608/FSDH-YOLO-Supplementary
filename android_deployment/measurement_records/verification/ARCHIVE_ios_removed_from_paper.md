# 已移出论文：iPhone 14 / WebGPU 链路

**这不是论文文本。** 记录"什么被移出、为什么、去哪找"，供日后溯源。

---

## 移出决定（2026-10-08）

本文的部署结论是：

> 所提出模型不仅在离线测试集上有效，而且能够导出并部署至消费级 Android 移动终端，
> 在真实摄像头输入下完成连续端侧检测。

Android 原生这一条链已足以完成**初步工程部署验证**。iPhone/WebGPU 链路属"跨平台可移植性"，
**不是该结论所必需**，且会额外引入若干与荔枝检测方法无关的讨论
（WebGPU/WebCanvas 与 Android Canvas 的计时边界、FP16 在各平台的收益方向、WebGPU adapter 实现差异等）。
故**整体移出正文与附录**，只保留在工程归档中。

## 移出了什么

| 原位置 | 内容 |
|---|---|
| 正文 §3.9 | 两条部署链路的并列（Android + iPhone WebGPU）；跨平台绝对时延不可比较的说明 |
| 正文 §3.9.2 | iPhone 14 WebGPU 的 FP32 / FP16 行 |
| 附录 A.7 | 设备表的 iOS 列；iOS 预热（25 帧）与固定图片输入协议；"两平台统一"的时延定义说明；iOS 轮转（3 轮 × 100 帧）行 |
| 附录 B.5.3 | **整节** iPhone 14 WebGPU 结果：表 B.5-6 轮转对照、B.5.3.1 首轮冷启动判据、B.5.3.2 分段计时口径偏差（canvas `getImageData` GPU 回读污染） |
| 附录 B.5.6 | 表 B.5-11 桌面 WebGPU 机理对照（RTX 5060 Ti + Chrome），及其"FP16 收益的平台依赖性"结论 |
| 表 B.5-17 | iPhone 14 · WebGPU 的配对时延比 $R = 1.11$ |

## 原始数据在哪

**`deploy_fsdh_yolo_n_android/`**（本目录同级，2026-10-06 的双平台合并前的单平台版本，"保留作溯源"）：

- `APPENDIX_A7.md` —— iPhone 版协议，含**完整轮转表**（132.44 / 111.30 / 119.09 ms，
  以及逐轮 122.19→132.62→132.27 等原始行）；
- `PAPER_SECTION.md` —— iPhone 版正文（表 3-X 的 WebGPU 三行、$R = 1.11$ 的推导）；
- `webapp/` —— 可运行的 PWA 本身，以及 `webapp/test/` 下的原始测量
  （`bench_result.json`、`browser_result.json`、`compare_result.json`、`bench_all_models.json`）；
- `README.md` —— 当时的工程记录与排错过程。

本目录的 `webapp/` 亦保留了同一份 PWA 与测量数据。

## 若日后要恢复

数据都在，恢复路径是：把上述四类内容按原样搬回 A.7 与 B.5，
并在正文 §3.9 恢复"两条独立链路"的并列叙述。
**但恢复前请先确认论文确实需要跨平台可移植性这一结论** —— 见 `DEPLOYMENT_OPEN_ISSUES.md` 第四节的分层纪律。
