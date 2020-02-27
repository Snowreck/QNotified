package nil.nadph.qnotified;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.*;
import android.os.Handler;
import android.os.Looper;
import nil.nadph.qnotified.hook.BaseDelayableHook;
import nil.nadph.qnotified.record.ConfigManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static nil.nadph.qnotified.util.Utils.getApplication;
import static nil.nadph.qnotified.util.Utils.log;

//import libcore.io.Libcore;


@SuppressLint("PrivateApi")
public class SyncUtils {

    public static final int PROC_ERROR = 0;
    public static final int PROC_MAIN = 1;
    public static final int PROC_MSF = 1 << 1;
    public static final int PROC_PEAK = 1 << 2;
    public static final int PROC_TOOL = 1 << 3;
    public static final int PROC_QZONE = 1 << 4;
    public static final int PROC_VIDEO = 1 << 5;
    public static final int PROC_MINI = 1 << 6;
    public static final int PROC_LOLA = 1 << 7;

    public static final int PROC_OTHERS = 1 << 31;
    public static final int PROC_ANY = 0xFFFFFFFF;
    //file=0
    public static final String SYNC_FILE_CHANGED = "nil.nadph.qnotified.SYNC_FILE_CHANGED";
    //process=010001 hook=0011000
    public static final String HOOK_DO_INIT = "nil.nadph.qnotified.HOOK_DO_INIT";
    public static final String ENUM_PROC_REQ = "nil.nadph.qnotified.ENUM_PROC_REQ";
    public static final String ENUM_PROC_RESP = "nil.nadph.qnotified.ENUM_PROC_RESP";

    private static int myId = 0;
    private static int seq = 0;
    private static boolean inited = false;
    private static int mProcType = 0;
    private static String mProcName = null;
    private static Handler sHandler;
    private static final ConcurrentHashMap<Integer, EnumRequestHolder> sEnumProcCallbacks = new ConcurrentHashMap<>();

    public static final int FILE_DEFAULT_CONFIG = 1;
    public static final int FILE_CACHE = 2;
    public static final int FILE_PROFILE_UIN = 3;

    private static class IpcReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case SYNC_FILE_CHANGED:
                    int id = intent.getIntExtra("id", -1);
                    int file = intent.getIntExtra("file", -1);
                    if (id != -1 && id != myId) {
                        //log("Rx: FILE_DEFAULT_CONFIG changed, setDirtyFlag");
                        ConfigManager.onRecvFileChanged(file);
                    }
                    break;
                case HOOK_DO_INIT:
                    int myType = getProcessType();
                    int targetType = intent.getIntExtra("process", 0);
                    int hookId = intent.getIntExtra("hook", -1);
                    if (hookId != -1 && (myType & targetType) != 0) {
                        BaseDelayableHook hook = BaseDelayableHook.getHookByType(hookId);
                        //log("Remote: recv init " + hook);
                        if (hook != null) hook.init();
                    }
                    break;
                case ENUM_PROC_REQ:
                    myType = getProcessType();
                    if (!intent.hasExtra("seq")) break;
                    int seq = intent.getIntExtra("seq", 0);
                    int mask = intent.getIntExtra("mask", 0);
                    if ((mask & myType) != 0) {
                        Intent resp = new Intent(ENUM_PROC_RESP);
                        resp.setPackage(ctx.getPackageName());
                        resp.setComponent(new ComponentName(ctx.getPackageName(), IpcReceiver.class.getName()));
                        initId();
                        resp.putExtra("seq", seq);
                        resp.putExtra("pid", android.os.Process.myPid());
                        resp.putExtra("type", myType);
                        resp.putExtra("time", System.currentTimeMillis());
                        resp.putExtra("name", getProcessName());
                        ctx.sendBroadcast(resp);
                    }
                    break;
                case ENUM_PROC_RESP:
                    if (!intent.hasExtra("seq")) break;
                    seq = intent.getIntExtra("seq", 0);
                    EnumRequestHolder holder = sEnumProcCallbacks.get(seq);
                    if (holder == null) break;
                    String name = intent.getStringExtra("name");
                    int pid = intent.getIntExtra("pid", 0);
                    int type = intent.getIntExtra("type", 0);
                    long time = intent.getLongExtra("time", -1);
                    ProcessInfo pi = new ProcessInfo();
                    pi.name = name;
                    pi.pid = pid;
                    pi.time = time;
                    pi.type = type;
                    holder.result.add(pi);
                    break;
            }
        }
    }

    public static void initBroadcast(Context ctx) {
        if (inited) return;
        BroadcastReceiver recv = new IpcReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SYNC_FILE_CHANGED);
        filter.addAction(HOOK_DO_INIT);
        ctx.registerReceiver(recv, filter);
        inited = true;
        //log("Proc:  " + android.os.Process.myPid() + "/" + getProcessType() + "/" + getProcessName());
    }


    public static void onFileChanged(int file) {
        Context ctx = getApplication();
        Intent changed = new Intent(SYNC_FILE_CHANGED);
        changed.setPackage(ctx.getPackageName());
        changed.setComponent(new ComponentName(ctx.getPackageName(), IpcReceiver.class.getName()));
        initId();
        changed.putExtra("id", myId);
        changed.putExtra("file", file);
        ctx.sendBroadcast(changed);
        //log("Tx: file changed " + file);
    }

    public static void requestInitHook(int hookId, int process) {
        Context ctx = getApplication();
        Intent changed = new Intent(HOOK_DO_INIT);
        changed.setPackage(ctx.getPackageName());
        changed.setComponent(new ComponentName(ctx.getPackageName(), IpcReceiver.class.getName()));
        initId();
        changed.putExtra("process", process);
        changed.putExtra("hook", hookId);
        log("Tx: " + hookId);
        ctx.sendBroadcast(changed);
    }

    public static int getProcessType() {
        if (mProcType != 0) return mProcType;
        String[] parts = getProcessName().split(":");
        if (parts.length == 1) {
            if (parts[0].equals("unknown")) {
                return PROC_MAIN;
            } else {
                mProcType = PROC_MAIN;
            }
        } else {
            String tail = parts[parts.length - 1];
            switch (tail) {
                case "MSF":
                    mProcType = PROC_MSF;
                    break;
                case "peak":
                    mProcType = PROC_PEAK;
                    break;
                case "tool":
                    mProcType = PROC_TOOL;
                    break;
                case "qzone":
                    mProcType = PROC_QZONE;
                    break;
                case "video":
                    mProcType = PROC_VIDEO;
                    break;
                case "mini":
                    mProcType = PROC_MINI;
                    break;
                case "lola":
                    mProcType = PROC_LOLA;
                    break;
                default:
                    mProcType = PROC_OTHERS;
                    break;
            }
        }
        return mProcType;
    }

    public static boolean isMainProcess() {
        return getProcessType() == PROC_MAIN;
    }

    public static String getProcessName() {
        if (mProcName != null) return mProcName;
        String name = "unknown";
        int retry = 0;
        do {
            try {
                List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = ((ActivityManager) getApplication().getSystemService(Context.ACTIVITY_SERVICE)).getRunningAppProcesses();
                if (runningAppProcesses != null) {
                    for (ActivityManager.RunningAppProcessInfo runningAppProcessInfo : runningAppProcesses) {
                        if (runningAppProcessInfo != null && runningAppProcessInfo.pid == android.os.Process.myPid()) {
                            mProcName = runningAppProcessInfo.processName;
                            return runningAppProcessInfo.processName;
                        }
                    }
                }
				/*FileInputStream fin = new FileInputStream("/proc/" + android.os.Process.myPid() + "/cmdline");
				 byte[] b = new byte[64];
				 int len = fin.read(b, 0, b.length);
				 fin.close();
				 String procName = new String(b, 0, len).trim();
				 //XposedBridge.log(procName);*/
            } catch (Throwable e) {
                log("getProcessName error " + e);
            }
            retry++;
            if (retry >= 3) {
                break;
            }
        } while ("unknown".equals(name));
        return name;
    }

    public static void enumerateProc(Context ctx, final int requestSeq, final int procMask, int timeout, EnumCallback callback) {
        if (callback == null) throw new NullPointerException("callback == null");
        if (ctx == null) throw new NullPointerException("ctx == null");
        Intent changed = new Intent(ENUM_PROC_REQ);
        changed.setPackage(ctx.getPackageName());
        changed.setComponent(new ComponentName(ctx.getPackageName(), IpcReceiver.class.getName()));
        initId();
        changed.putExtra("mask", procMask);
        changed.putExtra("seq", requestSeq);
        EnumRequestHolder holder = new EnumRequestHolder();
        holder.callback = callback;
        holder.deadline = System.currentTimeMillis() + timeout;
        sEnumProcCallbacks.put(requestSeq, holder);
        ctx.sendBroadcast(changed);
        if (sHandler == null) {
            sHandler = new Handler(Looper.getMainLooper());
        }
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                EnumRequestHolder holder = sEnumProcCallbacks.remove(requestSeq);
                if (holder == null) return;
                holder.callback.onEnumResult(requestSeq, procMask, holder.result);
            }
        }, timeout);
    }

    public static class EnumRequestHolder {
        public EnumCallback callback;
        public ArrayList<ProcessInfo> result = new ArrayList<>();
        public long deadline;
    }

    public interface EnumCallback {
        void onEnumResult(int requestSeq, int procMask, ArrayList<ProcessInfo> processes);
    }

    public static class ProcessInfo {
        public int pid;
        public String name;
        public int type;
        public long time;
    }

    /*
     public static synchronized String getSocketUuid() {
     Context ctx = getApplication();
     File f = new File(ctx.getFilesDir(), "nil_nadph_uuid");
     try {
     if (f.exists()) {
     FileInputStream fin = new FileInputStream(f);
     byte[] buf = new byte[20];
     int l = fin.read(buf);
     fin.close();
     String str = new String(buf, 0, l)
     .replace("\r", "").replace("\n", "").replace(" ", "").replace("\t", "");
     if (str.length() > 4) return str;
     }
     String uuid = UUID.randomUUID().toString().replace("\r", "").replace("\n", "").replace(" ", "").replace("\t", "");
     if (!f.exists()) f.createNewFile();
     FileOutputStream fout = new FileOutputStream(f);
     fout.write(uuid.getBytes());
     fout.flush();
     fout.close();
     return uuid;
     } catch (IOException e) {
     throw new RuntimeException("Unable to allocate uuid");
     }
     }
     */

//    public static int getUid() {
//        try {
//            return Libcore.os.getuid();
//        } catch (Throwable e) {
//            return android.os.Process.myUid();
//        }
//    }

    public static void initId() {
        if (myId == 0) {
            myId = (int) ((Math.random()) * (Integer.MAX_VALUE / 4));
        }
    }
}
