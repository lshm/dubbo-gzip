package com.alibaba.dubbo.common.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Created by <a href="liao670223382@163.com">shengchou</a> on 2016/1/12.
 */
public class GzipUtils {
    private static final int buffLen = 1024;
    /**
     * 压缩数据
     * @param data
     * @return
     * @throws Exception
     */
    public static byte[] compress( byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GZIPOutputStream out = new GZIPOutputStream(bytes);
        out.write(data);
        out.finish();
        return bytes.toByteArray();
    }

    /**
     * 解压数据
     * @param compressedData
     * @return
     * @throws Exception
     */
    public static byte[] decompress( byte[] compressedData) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(compressedData.length);
        GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressedData));
        byte[] buffer = new byte[compressedData.length];

        while(true) {
            int bytesRead = in.read(buffer, 0, buffer.length);
            if(bytesRead < 0) {
                return bytes.toByteArray();
            }

            bytes.write(buffer, 0, bytesRead);
        }
    }
}
