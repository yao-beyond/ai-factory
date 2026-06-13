package com.lza.aifactory.dto;

/**
 * Pipeline status. Besides the machine name, each value carries a plain-language
 * label, an emoji, and a rough progress percentage so non-technical users can
 * follow along without knowing the internal state machine.
 */
public enum TaskStatus {
    SUBMITTED("📩", "任務已受理", 5),
    RUNNING("⏳", "正在準備程式碼", 10),
    PLANNING("🧠", "正在構思實作方案…", 25),
    AWAITING_CONFIRMATION("📝", "請確認開工方向", 35),
    DEVELOPING("👩‍💻", "AI 工程師平行開發中", 45),
    SELECTING("🧪", "正在挑選最佳方案", 65),
    MR_CREATED("📬", "已產生成果草案", 75),
    REVIEWING("🔍", "程式碼品質與安全檢查", 85),
    FIXING("🛠️", "依審查建議修正中", 95),
    AWAITING_DELIVERY_APPROVAL("🧑‍⚖️", "等待人類核准交付", 90),
    COMPLETED("✅", "開發完成", 100),
    FAILED("⚠️", "發生問題，需要人工查看", 100),
    PAUSED("⏸️", "已暫停，等你說繼續", 50),
    CANCELLED("🛑", "已停止（沒有產生任何變更）", 100);

    private final String emoji;
    private final String displayName;
    private final int progress;

    TaskStatus(String emoji, String displayName, int progress) {
        this.emoji = emoji;
        this.displayName = displayName;
        this.progress = progress;
    }

    public String emoji() {
        return emoji;
    }

    public String displayName() {
        return displayName;
    }

    /** Rough completion percentage (0–100) for a progress bar. */
    public int progress() {
        return progress;
    }
}
