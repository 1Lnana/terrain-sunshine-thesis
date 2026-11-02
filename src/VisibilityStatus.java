/** 单时刻可见性；三个 UNKNOWN 分别表示接收点、射线经过点或网格边界无效。 */
public enum VisibilityStatus {
    VISIBLE,
    SHADED,
    UNKNOWN_RECEIVER_INVALID,
    UNKNOWN_RAY_INVALID,
    UNKNOWN_OUT_OF_BOUNDS
}
