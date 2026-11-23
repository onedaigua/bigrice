package com.bigrice;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal ustar reader: the phone bundles are plain (uncompressed) tar files
 * produced by GNU tar, so a full tar implementation is not worth its weight here.
 *
 * <p>Supports the classic 512-byte header with the 155-byte {@code prefix} field,
 * regular files and directories. Every other entry type (pax extended headers,
 * GNU long names, links) is skipped — the bundles contain none of them, and a
 * silent skip is better than a half-written file with a mangled name.
 */
final class Tar {
    private Tar() { }

    static void extract(InputStream in, File dest) throws IOException {
        byte[] header = new byte[512];
        byte[] buf = new byte[65536];
        while (true) {
            if (!readFully(in, header)) return;
            if (isZero(header)) return; // two zero blocks mark the end of the archive
            String name = field(header, 0, 100);
            long size = octal(header, 124, 12);
            char type = (char) header[156];
            String prefix = field(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            long padded = (size + 511) / 512 * 512;
            if (name.isEmpty() || name.contains("..")) {
                skip(in, padded);
                continue;
            }
            File target = new File(dest, name);
            boolean dir = type == '5' || name.endsWith("/");
            if (type != '0' && type != '\0' && !dir) {
                skip(in, padded);
                continue;
            }
            if (dir) {
                if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
                    throw new IOException("cannot create " + target);
                }
                skip(in, padded);
                continue;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("cannot create " + parent);
            }
            try (FileOutputStream out = new FileOutputStream(target)) {
                long left = size;
                while (left > 0) {
                    int want = (int) Math.min(buf.length, left);
                    int read = in.read(buf, 0, want);
                    if (read < 0) throw new IOException("truncated archive at " + name);
                    out.write(buf, 0, read);
                    left -= read;
                }
            }
            skip(in, padded - size);
        }
    }

    private static boolean readFully(InputStream in, byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int read = in.read(dst, off, dst.length - off);
            if (read < 0) return off > 0 ? false : false;
            off += read;
        }
        return true;
    }

    private static void skip(InputStream in, long count) throws IOException {
        long left = count;
        byte[] scratch = null;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped > 0) {
                left -= skipped;
                continue;
            }
            if (scratch == null) scratch = new byte[8192];
            int read = in.read(scratch, 0, (int) Math.min(scratch.length, left));
            if (read < 0) return;
            left -= read;
        }
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static String field(byte[] block, int off, int len) {
        int end = off;
        while (end < off + len && block[end] != 0) end++;
        return new String(block, off, end - off, StandardCharsets.UTF_8).trim();
    }

    private static long octal(byte[] block, int off, int len) {
        long value = 0;
        for (int i = off; i < off + len; i++) {
            byte b = block[i];
            if (b == 0 || b == ' ') continue;
            if (b < '0' || b > '7') break;
            value = value * 8 + (b - '0');
        }
        return value;
    }
}
