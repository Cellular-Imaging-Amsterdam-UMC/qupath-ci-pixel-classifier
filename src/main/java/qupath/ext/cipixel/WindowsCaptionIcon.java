// SPDX-FileCopyrightText: 2026 CI Pixel Classifier contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package qupath.ext.cipixel;

import javafx.scene.image.Image;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.*;

final class WindowsCaptionIcon {
    private static final int WM_SETICON = 0x0080;
    private static final int BITMAP_HEADER_BYTES = 40;
    private static MemorySegment smallIcon;
    private static MemorySegment largeIcon;

    private WindowsCaptionIcon() {}

    static void apply(String title, Image small, Image large) {
        if (title == null || title.isBlank() || ADDRESS.byteSize() != Long.BYTES)
            return;
        try (var arena = Arena.ofConfined()) {
            var text = arena.allocateFrom(title, StandardCharsets.UTF_16LE);
            int thread = (int) Win32.CURRENT_THREAD.invokeExact();
            var candidate = MemorySegment.NULL;
            do {
                candidate = (MemorySegment) Win32.FIND_WINDOW.invokeExact(
                        MemorySegment.NULL, candidate, MemorySegment.NULL, text);
                if (candidate.address() == 0)
                    return;
            } while ((int) Win32.WINDOW_THREAD.invokeExact(candidate, MemorySegment.NULL) != thread);

            if (smallIcon == null)
                smallIcon = create(small, arena);
            if (largeIcon == null)
                largeIcon = create(large, arena);
            if (smallIcon.address() != 0 && largeIcon.address() != 0) {
                // Reuse two icon handles for the process lifetime. JavaFX owns its separate HICON.
                Win32.MESSAGE.invoke(candidate, WM_SETICON, 0L, smallIcon);
                Win32.MESSAGE.invoke(candidate, WM_SETICON, 1L, largeIcon);
            }
        } catch (Throwable e) {
            LoggerFactory.getLogger(WindowsCaptionIcon.class).debug("Could not set the Windows title bar icon", e);
        }
    }

    private static MemorySegment create(Image image, Arena arena) throws Throwable {
        int size = (int) image.getWidth();
        int maskStart = BITMAP_HEADER_BYTES + size * size * Integer.BYTES;
        int maskStride = ((size + 31) / 32) * 4;
        int length = maskStart + maskStride * size;
        var dib = arena.allocate(length, Integer.BYTES);
        dib.set(JAVA_INT, 0, BITMAP_HEADER_BYTES);
        dib.set(JAVA_INT, 4, size);
        // An icon bitmap describes both the BGRA image and the transparency mask.
        dib.set(JAVA_INT, 8, size * 2);
        dib.set(JAVA_SHORT, 12, (short) 1);
        dib.set(JAVA_SHORT, 14, (short) 32);

        var pixels = image.getPixelReader();
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                int argb = pixels.getArgb(col, size - 1 - row);
                dib.set(JAVA_INT, BITMAP_HEADER_BYTES + (row * size + col) * Integer.BYTES, argb);
                if ((argb >>> 24) == 0) {
                    int mask = maskStart + row * maskStride + col / 8;
                    dib.set(JAVA_BYTE, mask, (byte) (dib.get(JAVA_BYTE, mask) | (0x80 >>> (col % 8))));
                }
            }
        }
        return (MemorySegment) Win32.CREATE_ICON.invokeExact(dib, length, 1, 0x00030000, size, size, 0);
    }

    private static final class Win32 {
        private static final SymbolLookup USER = SymbolLookup.libraryLookup("user32", Arena.global());
        private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32", Arena.global());
        private static final Linker LINKER = Linker.nativeLinker();
        private static final MethodHandle FIND_WINDOW = from(USER, "FindWindowExW",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        private static final MethodHandle WINDOW_THREAD = from(USER, "GetWindowThreadProcessId",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle CURRENT_THREAD = from(KERNEL, "GetCurrentThreadId",
                FunctionDescriptor.of(JAVA_INT));
        private static final MethodHandle MESSAGE = from(USER, "SendMessageW",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS));
        private static final MethodHandle CREATE_ICON = from(USER, "CreateIconFromResourceEx",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

        private static MethodHandle from(SymbolLookup library, String symbol, FunctionDescriptor signature) {
            return LINKER.downcallHandle(library.find(symbol).orElseThrow(), signature);
        }
    }
}
