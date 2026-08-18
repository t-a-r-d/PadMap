package com.slickstax841.padmap.sidecar;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shell-uid injector started via app_process after wireless ADB pairing.
 * Speaks a tiny binary protocol on 127.0.0.1 so the PadMap app never calls
 * injectInputEvent itself.
 *
 * Protocol (big-endian), after a 16-byte token:
 *   P                         ping
 *   D  u8 id  f32 x  f32 y    pointer down
 *   M  u8 id  f32 x  f32 y    pointer move
 *   B  u8 n   {u8 id f32 x f32 y}×n   batch move
 *   U  u8 id                  pointer up
 *   R                         release all
 *   Q                         quit
 * Reply: one byte, 0 = ok, 1 = fail.
 */
public final class SidecarMain {

    static final byte CMD_PING = 'P';
    static final byte CMD_DOWN = 'D';
    static final byte CMD_MOVE = 'M';
    static final byte CMD_BATCH = 'B';
    static final byte CMD_UP = 'U';
    static final byte CMD_RELEASE = 'R';
    static final byte CMD_QUIT = 'Q';

    private static final int MAX_POINTERS = 10;

    public static void main(String[] args) {
        int port = 18741;
        String token = "padmap-sidecar";
        if (args != null && args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        if (args != null && args.length > 1) token = args[1];
        byte[] tokenBytes = token.getBytes();

        Injector injector = new Injector();
        if (!injector.init()) {
            System.err.println("padmap-sidecar: injectInputEvent unavailable");
            System.err.flush();
            System.exit(2);
        }

        ServerSocket server;
        try {
            server = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
            server.setReuseAddress(true);
        } catch (IOException e) {
            System.err.println("padmap-sidecar: bind failed " + e.getMessage());
            System.err.flush();
            System.exit(3);
            return;
        }
        try {
            java.io.FileWriter pw = new java.io.FileWriter("/data/local/tmp/padmap-sidecar.pid");
            pw.write(Integer.toString(android.os.Process.myPid()));
            pw.close();
        } catch (IOException ignored) {}
        System.out.println("padmap-sidecar: listening " + port);
        System.out.flush();

        while (true) {
            try (Socket socket = server.accept()) {
                socket.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                byte[] got = new byte[tokenBytes.length];
                in.readFully(got);
                if (!java.util.Arrays.equals(got, tokenBytes)) {
                    out.writeByte(1);
                    out.flush();
                    continue;
                }
                out.writeByte(0);
                out.flush();
                boolean running = true;
                while (running) {
                    int cmd = in.read();
                    if (cmd < 0) break;
                    boolean ok = true;
                    try {
                        switch (cmd) {
                            case CMD_PING:
                                break;
                            case CMD_DOWN: {
                                int id = in.readUnsignedByte();
                                float x = in.readFloat();
                                float y = in.readFloat();
                                ok = injector.down(id, x, y);
                                break;
                            }
                            case CMD_MOVE: {
                                int id = in.readUnsignedByte();
                                float x = in.readFloat();
                                float y = in.readFloat();
                                ok = injector.move(id, x, y);
                                break;
                            }
                            case CMD_BATCH: {
                                int n = in.readUnsignedByte();
                                int[] ids = new int[n];
                                float[] xs = new float[n];
                                float[] ys = new float[n];
                                for (int i = 0; i < n; i++) {
                                    ids[i] = in.readUnsignedByte();
                                    xs[i] = in.readFloat();
                                    ys[i] = in.readFloat();
                                }
                                ok = injector.batch(ids, xs, ys);
                                break;
                            }
                            case CMD_UP: {
                                int id = in.readUnsignedByte();
                                ok = injector.up(id);
                                break;
                            }
                            case CMD_RELEASE:
                                injector.releaseAll();
                                break;
                            case CMD_QUIT:
                                injector.releaseAll();
                                running = false;
                                break;
                            default:
                                ok = false;
                                break;
                        }
                    } catch (Throwable t) {
                        System.err.println("padmap-sidecar: cmd " + cmd + " " + t.getMessage());
                        ok = false;
                    }
                    out.writeByte(ok ? 0 : 1);
                    out.flush();
                    if (cmd == CMD_QUIT) {
                        try { server.close(); } catch (IOException ignored) {}
                        System.exit(0);
                    }
                }
            } catch (IOException e) {
                System.err.println("padmap-sidecar: session " + e.getMessage());
            }
        }
    }

    private static final class Pointer {
        final int id;
        float x;
        float y;
        final long downTime;
        Pointer(int id, float x, float y, long downTime) {
            this.id = id; this.x = x; this.y = y; this.downTime = downTime;
        }
    }

    private static final class Injector {
        private final Map<Integer, Pointer> active = new LinkedHashMap<Integer, Pointer>();
        private Object inputManager;
        private Method injectMethod;
        private Method setDisplayId;
        private int[] displayIds = new int[] { 0 };
        private int touchDeviceId = 0;

        boolean init() {
            try {
                Class<?> cls;
                try {
                    cls = Class.forName("android.hardware.input.InputManager");
                } catch (ClassNotFoundException e) {
                    cls = Class.forName("android.hardware.input.InputManagerGlobal");
                }
                Method getInstance = cls.getDeclaredMethod("getInstance");
                getInstance.setAccessible(true);
                inputManager = getInstance.invoke(null);
                injectMethod = inputManager.getClass().getMethod(
                        "injectInputEvent", InputEvent.class, int.class);
                injectMethod.setAccessible(true);
                try {
                    setDisplayId = MotionEvent.class.getMethod("setDisplayId", int.class);
                } catch (Throwable ignored) {
                    setDisplayId = null;
                }
                displayIds = readDisplayIds();
                touchDeviceId = findTouchscreenId();
                System.out.println("padmap-sidecar: displays=" + java.util.Arrays.toString(displayIds)
                        + " touchDev=" + touchDeviceId);
                return true;
            } catch (Throwable t) {
                System.err.println("padmap-sidecar: init " + t);
                return false;
            }
        }

        synchronized boolean down(int id, float x, float y) {
            if (active.containsKey(id) || active.size() >= MAX_POINTERS) return false;
            boolean first = active.isEmpty();
            active.put(id, new Pointer(id, x, y, SystemClock.uptimeMillis()));
            return send(first ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_POINTER_DOWN, id);
        }

        synchronized boolean move(int id, float x, float y) {
            Pointer p = active.get(id);
            if (p == null) return false;
            p.x = x; p.y = y;
            return send(MotionEvent.ACTION_MOVE, -1);
        }

        synchronized boolean batch(int[] ids, float[] xs, float[] ys) {
            for (int i = 0; i < ids.length; i++) {
                Pointer p = active.get(ids[i]);
                if (p != null) { p.x = xs[i]; p.y = ys[i]; }
            }
            if (active.isEmpty()) return false;
            return send(MotionEvent.ACTION_MOVE, -1);
        }

        synchronized boolean up(int id) {
            if (!active.containsKey(id)) return false;
            boolean last = active.size() == 1;
            boolean ok = send(last ? MotionEvent.ACTION_UP : MotionEvent.ACTION_POINTER_UP, id);
            active.remove(id);
            return ok;
        }

        synchronized void releaseAll() {
            List<Integer> ids = new ArrayList<Integer>(active.keySet());
            Collections.reverse(ids);
            for (int id : ids) up(id);
        }

        private boolean send(int action, int actionId) {
            if (active.isEmpty()) return false;
            List<Pointer> list = new ArrayList<Pointer>(active.values());
            Collections.sort(list, new Comparator<Pointer>() {
                public int compare(Pointer a, Pointer b) { return a.id - b.id; }
            });
            int count = list.size();
            int encoded = action;
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                int idx = 0;
                for (int i = 0; i < count; i++) if (list.get(i).id == actionId) { idx = i; break; }
                encoded = (idx << 8) | action;
            }
            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
            long firstDown = list.get(0).downTime;
            for (int i = 0; i < count; i++) {
                Pointer p = list.get(i);
                if (p.downTime < firstDown) firstDown = p.downTime;
                props[i] = new MotionEvent.PointerProperties();
                props[i].id = p.id;
                props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coords[i] = new MotionEvent.PointerCoords();
                coords[i].x = p.x;
                coords[i].y = p.y;
                coords[i].pressure = 1f;
                coords[i].size = 1f;
            }
            MotionEvent ev = MotionEvent.obtain(
                    firstDown, SystemClock.uptimeMillis(),
                    encoded, count, props, coords,
                    0, 0, 1f, 1f, touchDeviceId, 0,
                    InputDevice.SOURCE_TOUCHSCREEN, 0);
            try {
                boolean any = false;
                int[] ids = displayIds.length == 0 ? new int[] { 0 } : displayIds;
                for (int displayId : ids) {
                    if (setDisplayId != null) {
                        try { setDisplayId.invoke(ev, Integer.valueOf(displayId)); } catch (Throwable ignored) {}
                    }
                    if (invokeInject(ev, 0) || invokeInject(ev, 2)) any = true;
                }
                if (!any) System.err.println("padmap-sidecar: inject returned false");
                return any;
            } catch (Throwable t) {
                System.err.println("padmap-sidecar: inject " + t);
                return false;
            } finally {
                ev.recycle();
            }
        }

        private boolean invokeInject(MotionEvent ev, int mode) {
            try {
                Object ret = injectMethod.invoke(inputManager, ev, Integer.valueOf(mode));
                return !(ret instanceof Boolean) || ((Boolean) ret).booleanValue();
            } catch (Throwable t) {
                System.err.println("padmap-sidecar: inject mode " + mode + " " + t);
                return false;
            }
        }

        private static int findTouchscreenId() {
            try {
                int[] ids = InputDevice.getDeviceIds();
                for (int i = 0; i < ids.length; i++) {
                    InputDevice d = InputDevice.getDevice(ids[i]);
                    if (d != null && (d.getSources() & InputDevice.SOURCE_TOUCHSCREEN) != 0) {
                        return d.getId();
                    }
                }
            } catch (Throwable ignored) {}
            return 0;
        }

        private static int[] readDisplayIds() {
            try {
                Class<?> cls = Class.forName("android.hardware.display.DisplayManagerGlobal");
                Object inst = cls.getDeclaredMethod("getInstance").invoke(null);
                return (int[]) inst.getClass().getMethod("getDisplayIds").invoke(inst);
            } catch (Throwable t) {
                System.err.println("padmap-sidecar: displays " + t);
                return new int[] { 0 };
            }
        }
    }
}
