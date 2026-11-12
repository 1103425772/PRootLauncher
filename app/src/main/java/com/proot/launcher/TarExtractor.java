package com.proot.launcher;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.tukaani.xz.XZInputStream;

/**
 * Pure Java tar extractor supporting .tar, .tar.gz, .tar.xz
 * Handles ustar and GNU tar long filename extensions.
 */
public class TarExtractor {

    public interface ProgressListener {
        void onProgress(long extractedBytes, long totalBytes);
        void onMessage(String msg);
    }

    private static final int BLOCK_SIZE = 512;
    private static final int TYPE_NORMAL = '0';
    private static final int TYPE_NORMAL_ALT = '\0';
    private static final int TYPE_HARD_LINK = '1';
    private static final int TYPE_SYMLINK = '2';
    private static final int TYPE_DIR = '5';
    private static final int TYPE_GNU_LONG_NAME = 'L';
    private static final int TYPE_GNU_LONG_LINK = 'K';

    public static void extract(File archive, File destDir, ProgressListener listener) throws IOException {
        if (!destDir.exists()) destDir.mkdirs();
        InputStream in = openArchiveStream(archive);
        try {
            extractTar(in, destDir, listener, archive.length());
        } finally {
            in.close();
        }
    }

    private static InputStream openArchiveStream(File file) throws IOException {
        String name = file.getName().toLowerCase();
        FileInputStream fis = new FileInputStream(file);
        BufferedInputStream bis = new BufferedInputStream(fis, 65536);
        if (name.endsWith(".tar.xz") || name.endsWith(".txz")) {
            return new XZInputStream(bis);
        } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            return new GZIPInputStream(bis);
        }
        return bis;
    }

    private static void extractTar(InputStream in, File destDir, ProgressListener listener, long totalBytes) throws IOException {
        byte[] header = new byte[BLOCK_SIZE];
        String longName = null;
        String longLink = null;
        long processed = 0;
        int count = 0;
        // Track symlinks that failed to create (Android may not support symlinks)
        // We'll fix them after extraction by copying the target file
        final List<String[]> failedSymlinks = new ArrayList<>();
        // Track hard links whose target doesn't exist yet (target may come later in tar)
        final List<String[]> pendingHardLinks = new ArrayList<>();

        while (true) {
            int read = readFully(in, header);
            if (read < BLOCK_SIZE) break;
            processed += BLOCK_SIZE;

            // Check for end of archive (two zero blocks)
            if (isZeroBlock(header)) {
                // Read next block to confirm
                read = readFully(in, header);
                if (read < BLOCK_SIZE || isZeroBlock(header)) break;
                processed += BLOCK_SIZE;
            }

            String name = readString(header, 0, 100);
            int type = header[156] & 0xFF;
            long size = readOctal(header, 124, 12);
            // ustar/GNU tar header: linkname starts at offset 157 (after
            // typeflag at 156). Offset 140 lands in the mtime field, which
            // made every symlink target a numeric timestamp (e.g. sh -> 6564446).
            String linkTarget = readString(header, 157, 100);
            int mode = (int) readOctal(header, 100, 8);

            // GNU long name
            if (type == TYPE_GNU_LONG_NAME) {
                longName = readLongData(in, size);
                processed += alignTo512(size);
                continue;
            }
            if (type == TYPE_GNU_LONG_LINK) {
                longLink = readLongData(in, size);
                processed += alignTo512(size);
                continue;
            }

            if (longName != null) {
                name = longName;
                longName = null;
            }
            if (longLink != null) {
                linkTarget = longLink;
                longLink = null;
            }

            if (name.isEmpty()) {
                skipData(in, size);
                processed += alignTo512(size);
                continue;
            }

            File outFile = new File(destDir, name);
            // Security: prevent path traversal
            if (!outFile.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)
                && !outFile.getCanonicalPath().equals(destDir.getCanonicalPath())) {
                skipData(in, size);
                processed += alignTo512(size);
                continue;
            }

            if (type == TYPE_DIR) {
                outFile.mkdirs();
            } else if (type == TYPE_SYMLINK) {
                File parent = outFile.getParentFile();
                if (parent != null) parent.mkdirs();
                try {
                    java.nio.file.Files.deleteIfExists(outFile.toPath());
                    java.nio.file.Files.createSymbolicLink(outFile.toPath(), new File(linkTarget).toPath());
                } catch (Exception e) {
                    // symlink not supported, record for post-extraction fix
                    failedSymlinks.add(new String[]{outFile.getAbsolutePath(), linkTarget});
                }
            } else if (type == TYPE_HARD_LINK) {
                // Hard link: copy target file content (Android Java doesn't support hard links)
                File parent = outFile.getParentFile();
                if (parent != null) parent.mkdirs();
                File targetFile = new File(linkTarget);
                if (!targetFile.isAbsolute()) {
                    targetFile = new File(destDir, linkTarget);
                }
                if (targetFile.exists() && targetFile.isFile()) {
                    copyFile(targetFile, outFile);
                    outFile.setExecutable((mode & 0111) != 0, false);
                    outFile.setReadable(true, false);
                    outFile.setWritable((mode & 0222) != 0, false);
                } else {
                    // Target doesn't exist yet (may come later in tar), record for post-processing
                    pendingHardLinks.add(new String[]{outFile.getAbsolutePath(), linkTarget, String.valueOf(mode)});
                }
            } else if (type == TYPE_NORMAL || type == TYPE_NORMAL_ALT) {
                File parent = outFile.getParentFile();
                if (parent != null) parent.mkdirs();
                writeFileData(in, outFile, size);
                // Set file permissions from tar header
                outFile.setReadable(true, false);
                outFile.setExecutable((mode & 0111) != 0, false);
                outFile.setWritable((mode & 0222) != 0, false);
            } else {
                skipData(in, size);
            }

            processed += alignTo512(size);
            count++;
            if (count % 100 == 0 && listener != null) {
                listener.onProgress(processed, totalBytes);
            }
        }
        // Post-extraction: fix failed symlinks by copying target file content.
        // 循环直到一轮没有任何进展：覆盖任意深度的 symlink 链
        // （如 A→B→C→D 且 D 在归档中排在 A 后面；两轮固定扫描不够）。
        boolean madeProgress = true;
        while (madeProgress && !failedSymlinks.isEmpty()) {
            madeProgress = false;
            java.util.Iterator<String[]> it = failedSymlinks.iterator();
            while (it.hasNext()) {
                String[] sl = it.next();
                try {
                    File linkFile = new File(sl[0]);
                    File targetFile = new File(sl[1]);
                    // If target is relative, resolve against the link's parent directory
                    if (!targetFile.isAbsolute()) {
                        targetFile = new File(linkFile.getParentFile(), sl[1]);
                    }
                    if (targetFile.exists() && targetFile.isFile()) {
                        copyFile(targetFile, linkFile);
                        linkFile.setExecutable(true, false);
                        it.remove();
                        madeProgress = true;
                    }
                } catch (Exception e) {
                    // 保留到下一轮重试；最终仍失败的走下面的日志
                }
            }
        }
        // 剩下的：目标在整个归档中都不存在，打日志（而不是静默跳过）
        for (String[] sl : failedSymlinks) {
            if (listener != null) {
                listener.onMessage("symlink 目标缺失: " + sl[0] + " -> " + sl[1]
                    + "（将由 proot --link2symlink 兜底）");
            }
        }
        // Post-extraction: fix pending hard links (target didn't exist when first encountered)
        for (String[] hl : pendingHardLinks) {
            try {
                File linkFile = new File(hl[0]);
                File targetFile = new File(hl[1]);
                if (!targetFile.isAbsolute()) {
                    targetFile = new File(destDir, hl[1]);
                }
                int mode = Integer.parseInt(hl[2]);
                if (targetFile.exists() && targetFile.isFile()) {
                    copyFile(targetFile, linkFile);
                    linkFile.setExecutable((mode & 0111) != 0, false);
                    linkFile.setReadable(true, false);
                    linkFile.setWritable((mode & 0222) != 0, false);
                }
            } catch (Exception e) {
                // ignore individual failures
            }
        }
        if (listener != null) {
            listener.onProgress(processed, totalBytes);
        }
    }

    private static String readLongData(InputStream in, long size) throws IOException {
        // 恶意/损坏的 tar 可声明超大 size；强转 int 会溢出成负数导致
        // NegativeArraySizeException/OOM。超过上限（GNU 长名/长链接
        // 实际远小于 10MB）直接跳过该记录。
        if (size <= 0 || size > 10L * 1024L * 1024L) {
            skipBytes(in, alignTo512(size));
            return "";
        }
        byte[] data = new byte[(int) size];
        readFully(in, data);
        // skip padding
        long padding = alignTo512(size) - size;
        if (padding > 0) skipBytes(in, padding);
        // Remove trailing nulls and slashes
        String s = new String(data, "UTF-8");
        int idx = s.indexOf('\0');
        if (idx >= 0) s = s.substring(0, idx);
        return s;
    }

    private static void writeFileData(InputStream in, File outFile, long size) throws IOException {
        FileOutputStream fos = new FileOutputStream(outFile);
        try {
            byte[] buf = new byte[65536];
            long remaining = size;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int r = in.read(buf, 0, toRead);
                if (r < 0) break;
                fos.write(buf, 0, r);
                remaining -= r;
            }
        } finally {
            fos.close();
        }
        // skip padding
        long padding = alignTo512(size) - size;
        if (padding > 0) skipBytes(in, padding);
    }

    private static void skipData(InputStream in, long size) throws IOException {
        skipBytes(in, alignTo512(size));
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private static long alignTo512(long v) {
        return (v + 511) & ~511L;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private static void skipBytes(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                // read one byte to unblock
                if (in.read() < 0) break;
                n--;
            } else {
                n -= s;
            }
        }
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte v : b) if (v != 0) return false;
        return true;
    }

    private static String readString(byte[] buf, int offset, int length) {
        int end = offset;
        while (end < offset + length && buf[end] != 0) end++;
        return new String(buf, offset, end - offset);
    }

    private static long readOctal(byte[] buf, int offset, int length) {
        // Skip leading spaces and zeros
        int i = offset;
        while (i < offset + length && (buf[i] == ' ' || buf[i] == '0')) i++;
        long val = 0;
        while (i < offset + length && buf[i] >= '0' && buf[i] <= '7') {
            val = val * 8 + (buf[i] - '0');
            i++;
        }
        return val;
    }
}
