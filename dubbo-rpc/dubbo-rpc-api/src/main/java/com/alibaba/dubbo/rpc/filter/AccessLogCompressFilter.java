package com.alibaba.dubbo.rpc.filter;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.json.JSON;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.rpc.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * * 记录Service的Access Log。
 * <p>
 * 使用的Logger key是<code><b>dubbo.accesslog</b></code>。
 * 如果想要配置Access Log只出现在指定的Appender中，可以在Log4j中注意配置上additivity。配置示例:
 * <code>
 * <pre>
 * &lt;logger name="<b>dubbo.accesslog</b>" <font color="red">additivity="false"</font>&gt;
 *    &lt;level value="info" /&gt;
 *    &lt;appender-ref ref="foo" /&gt;
 * &lt;/logger&gt;
 * </pre></code>
 *
 * Created by <a href="liao670223382@163.com">shengchou</a> on 2015/3/23.
 */
@Activate(group = Constants.PROVIDER, value = Constants.ACCESS_LOG_KEY)
public class AccessLogCompressFilter implements Filter{
    private static final Logger logger            = LoggerFactory.getLogger(AccessLogFilter.class);

    private static final String  ACCESS_LOG_KEY   = "dubbo.accesslog";

    private static final String DUBBO_PROTOCOL_ACCESSLOG = "dubbo.protocol.accesslog";

    private static final String  FILE_DATE_FORMAT   = "yyyyMMdd";

    private static final String  MESSAGE_DATE_FORMAT   = "yyyy-MM-dd HH:mm:ss";

    private static final int LOG_MAX_BUFFER = 5000;

    private static final long LOG_OUTPUT_INTERVAL = 5000;

    private static final long ZIP_LOG_OUTPUT_INTERVAL = 24*60;

    static final int ZIP_BUFFER = 8192;

    private final ConcurrentMap<String, Set<String>> logQueue = new ConcurrentHashMap<String, Set<String>>();

    private final ScheduledExecutorService logScheduled = Executors.newScheduledThreadPool(2, new NamedThreadFactory("Dubbo-Access-Log", true));

    private final ScheduledExecutorService zipLogScheduled = Executors.newScheduledThreadPool(1, new NamedThreadFactory("Dubbo-Zip-Access-Log", true));

    private volatile ScheduledFuture<?> logFuture = null;

    private volatile ScheduledFuture<?> zipLogFuture = null;

    private class LogTask implements Runnable {
        public void run() {
            try {
                if (logQueue != null && logQueue.size() > 0) {
                    for (Map.Entry<String, Set<String>> entry : logQueue.entrySet()) {
                        try {
                            String accesslog = entry.getKey();
                            Set<String> logSet = entry.getValue();
                            File file = new File(accesslog);
                            File dir = file.getParentFile();
                            if (null!=dir&&! dir.exists()) {
                                dir.mkdirs();
                            }
                            if (logger.isDebugEnabled()) {
                                logger.debug("Append log to " + accesslog);
                            }
                            if (file.exists()) {
                                String now = new SimpleDateFormat(FILE_DATE_FORMAT).format(new Date());
                                String last = new SimpleDateFormat(FILE_DATE_FORMAT).format(new Date(file.lastModified()));
                                if (! now.equals(last)) {
                                    File archive = new File(file.getAbsolutePath() + "." + last);
                                    file.renameTo(archive);
                                }
                            }
                            FileWriter writer = new FileWriter(file, true);
                            try {
                                for(Iterator<String> iterator = logSet.iterator();
                                    iterator.hasNext();
                                    iterator.remove()) {
                                    writer.write(iterator.next());
                                    writer.write("\r\n");
                                }
                                writer.flush();
                            } finally {
                                writer.close();
                            }
                        } catch (Exception e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    //压缩数据
    private class ZipLogTask implements Runnable {
        public void run() {
            try {
                //日志文件信息
                String logFilePath = ConfigUtils.getProperty(DUBBO_PROTOCOL_ACCESSLOG);
                final File file = new File(logFilePath);
                File dir =  file.getCanonicalFile().getParentFile();
                String fileName = file.getName();
                if(fileName.indexOf(".") != -1){
                    fileName = fileName.substring(0,fileName.indexOf("."));
                }
                if (null!=dir&&! dir.exists()) {
                    dir.mkdirs();

                }

                //创建日志压缩文件
                String zipaccesslog;
                Calendar calendar = Calendar.getInstance();
                int month = calendar.get(Calendar.MONTH)+1;
                String monthStr = (month>=10?""+month:"0"+month);
                zipaccesslog = dir.getAbsolutePath() +"/"+fileName+calendar.get(Calendar.YEAR) + monthStr+".zip";
                File zipFile = new File(zipaccesslog);
                if (!zipFile.exists()){
                    zipFile.createNewFile();
                }

                //遍历出需要压缩的日志文件
                final String finalFileName = fileName;
                final String filePattern = file.getName() + "." + calendar.get(Calendar.YEAR) + monthStr + calendar.get(Calendar.DATE);
                File[] logFiles = dir.listFiles(new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        if(name.startsWith(finalFileName) &&
                                name.lastIndexOf(".zip") == -1 &&
                                !name.equals(file.getName())){
                            if(name.compareTo(filePattern) < 0) {
                                return true;
                            }
                        }
                        return false;
                    }
                });
                //压缩文件
                if(logFiles != null && logFiles.length > 0){
                    ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(zipFile,true));

                    //拷贝老的文件到zip
                    ZipFile oldZip = new ZipFile(zipFile);
                    Enumeration<? extends ZipEntry> entries = oldZip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        System.out.println("copy: " + e.getName());
                        zipOutputStream.putNextEntry(e);
                        if (!e.isDirectory()) {
                            BufferedInputStream bi = new BufferedInputStream(oldZip.getInputStream(e));
                            int count;
                            byte data[] = new byte[ZIP_BUFFER];
                            while ((count = bi.read(data, 0, ZIP_BUFFER)) != -1) {
                                zipOutputStream.write(data, 0, count);
                            }
                            bi.close();
                        }
                        zipOutputStream.closeEntry();
                    }
                    oldZip.close();

                    //新增zip文件
                    ZipEntry zipEntry;
                    for(File logFile : logFiles){
                        zipEntry = new ZipEntry(logFile.getName());
                        zipOutputStream.putNextEntry(zipEntry);

                        FileInputStream in = new FileInputStream(logFile);
                        BufferedInputStream bi = new BufferedInputStream(in);
                        int count;
                        byte data[] = new byte[ZIP_BUFFER];
                        while ((count = bi.read(data, 0, ZIP_BUFFER)) != -1) {
                            zipOutputStream.write(data, 0, count);
                        }
                        bi.close();
                        in.close(); // 输入流关闭
                    }

                    zipOutputStream.close();
                }

                //删除日志文件
                if(logFiles != null){
                    for(File logFile : logFiles){
                        logFile.delete();
                    }
                }




                //清理日志zip文件
                final String zipFilePattern = fileName+calendar.get(Calendar.YEAR)+monthStr+".zip";

                File[] logZipFiles = dir.listFiles(new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        if(name.startsWith(finalFileName) && name.lastIndexOf(".zip") != -1) {
                            if (name.compareTo(zipFilePattern) < 0) {
                                return true;
                            }
                        }
                        return false;
                    }
                });

                if(logZipFiles != null && logZipFiles.length > 0){
                    for(File logZipFile : logZipFiles){
                        logZipFile.delete();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void init() {
        if (logFuture == null) {
            synchronized (logScheduled) {
                if (logFuture == null) {
                    logFuture = logScheduled.scheduleWithFixedDelay(new LogTask(), LOG_OUTPUT_INTERVAL, LOG_OUTPUT_INTERVAL, TimeUnit.MILLISECONDS);
                }
            }
        }

        if(zipLogFuture == null){
            synchronized (zipLogScheduled) {
                if (zipLogFuture == null) {
                    Calendar calendar = Calendar.getInstance();
                    long now = calendar.getTimeInMillis();
                    calendar.set(Calendar.HOUR_OF_DAY,24);
                    calendar.set(Calendar.MINUTE,0);
                    calendar.set(Calendar.SECOND,0);
                    //凌晨运行一次
                    long delay = (calendar.getTimeInMillis()-now)/1000/60+30;
                    zipLogFuture = zipLogScheduled.scheduleWithFixedDelay(new ZipLogTask(), delay, ZIP_LOG_OUTPUT_INTERVAL, TimeUnit.MINUTES);
                }
            }
        }
    }

    private void log(String accesslog, String logmessage) {
        init();
        Set<String> logSet = logQueue.get(accesslog);
        if (logSet == null) {
            logQueue.putIfAbsent(accesslog, new ConcurrentHashSet<String>());
            logSet = logQueue.get(accesslog);
        }
        if (logSet.size() < LOG_MAX_BUFFER) {
            logSet.add(logmessage);
        }
    }

    public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {
        try {
            String accesslog = invoker.getUrl().getParameter(Constants.ACCESS_LOG_KEY);
            if (ConfigUtils.isNotEmpty(accesslog)) {
                RpcContext context = RpcContext.getContext();
                String serviceName = invoker.getInterface().getName();
                String version = invoker.getUrl().getParameter(Constants.VERSION_KEY);
                String group = invoker.getUrl().getParameter(Constants.GROUP_KEY);
                StringBuilder sn = new StringBuilder();
                sn.append("[").append(new SimpleDateFormat(MESSAGE_DATE_FORMAT).format(new Date())).append("] ").append(context.getRemoteHost()).append(":").append(context.getRemotePort())
                        .append(" -> ").append(context.getLocalHost()).append(":").append(context.getLocalPort())
                        .append(" - ");
                if (null != group && group.length() > 0) {
                    sn.append(group).append("/");
                }
                sn.append(serviceName);
                if (null != version && version.length() > 0) {
                    sn.append(":").append(version);
                }
                sn.append(" ");
                sn.append(inv.getMethodName());
                sn.append("(");
                Class<?>[] types = inv.getParameterTypes();
                if (types != null && types.length > 0) {
                    boolean first = true;
                    for (Class<?> type : types) {
                        if (first) {
                            first = false;
                        } else {
                            sn.append(",");
                        }
                        sn.append(type.getName());
                    }
                }
                sn.append(") ");
                Object[] args = inv.getArguments();
                if (args != null && args.length > 0) {
                    sn.append(JSON.json(args));
                }
                String msg = sn.toString();
                if (ConfigUtils.isDefault(accesslog)) {
                    LoggerFactory.getLogger(ACCESS_LOG_KEY + "." + invoker.getInterface().getName()).info(msg);
                } else {
                    log(accesslog, msg);
                }
            }
        } catch (Throwable t) {
            logger.warn("Exception in AcessLogFilter of service(" + invoker + " -> " + inv + ")", t);
        }
        return invoker.invoke(inv);
    }
}
