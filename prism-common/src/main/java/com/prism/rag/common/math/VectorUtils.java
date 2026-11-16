package com.prism.rag.common.math;

import java.util.Arrays;

/**
 * 向量工具：归一化、余弦相似度、字节序列化。
 *
 * <p><b>向量存哪？</b>本项目默认把向量以 {@code byte[]} 存进 H2 的 BLOB 字段，
 * 同时在内存里维护一份用于检索。真实项目里这一步会换成 Milvus / PGVector / Elasticsearch，
 * 届时本类的工具方法仍然会被复用——所以单独抽出来，不要写死在业务里。</p>
 */
public final class VectorUtils {

    private VectorUtils() {
    }

    /**
     * L2 归一化：把向量缩放到单位长度。
     *
     * <p>归一化之后，余弦相似度就退化成点积，计算量减半，
     * 而且"点积越大越相似"这种直觉可以直接用。</p>
     */
    public static float[] normalize(float[] vector) {
        if (vector == null || vector.length == 0) {
            return new float[0];
        }
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        if (sum <= 0) {
            return vector.clone();
        }
        double norm = Math.sqrt(sum);
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) (vector[i] / norm);
        }
        return result;
    }

    /**
     * 余弦相似度（不要求向量已归一化）。
     *
     * @return 相似度，范围 [-1, 1]，越大越相似
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA <= 0 || normB <= 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * float[] → byte[]（用于数据库 BLOB 字段）。
     */
    public static byte[] toBytes(float[] vector) {
        if (vector == null) {
            return new byte[0];
        }
        byte[] bytes = new byte[vector.length * 4];
        for (int i = 0; i < vector.length; i++) {
            int bits = Float.floatToIntBits(vector[i]);
            bytes[i * 4] = (byte) (bits >>> 24);
            bytes[i * 4 + 1] = (byte) (bits >>> 16);
            bytes[i * 4 + 2] = (byte) (bits >>> 8);
            bytes[i * 4 + 3] = (byte) bits;
        }
        return bytes;
    }

    /**
     * byte[] → float[]（从数据库读回向量）。
     */
    public static float[] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new float[0];
        }
        int length = bytes.length / 4;
        float[] vector = new float[length];
        for (int i = 0; i < length; i++) {
            int bits = ((bytes[i * 4] & 0xFF) << 24)
                    | ((bytes[i * 4 + 1] & 0xFF) << 16)
                    | ((bytes[i * 4 + 2] & 0xFF) << 8)
                    | (bytes[i * 4 + 3] & 0xFF);
            vector[i] = Float.intBitsToFloat(bits);
        }
        return vector;
    }

    /**
     * 打印向量的前若干维，便于日志排查。
     */
    public static String preview(float[] vector, int dims) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        int len = Math.min(dims, vector.length);
        float[] head = Arrays.copyOf(vector, len);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.format("%.4f", head[i]));
        }
        if (len < vector.length) {
            sb.append(", ...(").append(vector.length).append("维)");
        }
        sb.append(']');
        return sb.toString();
    }
}
