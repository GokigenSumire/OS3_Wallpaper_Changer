package personal.wallpaper.protocol.lock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 两个独立签名APK之间的有限协议。证书指纹是公开身份，不是秘密或能力。 */
public final class LockProtocol {
    public static final int VERSION = 1;
    public static final int MAX_PARCEL_BYTES = 1024;
    public static final int MAX_TOKEN_CHARS = 128;
    public static final int MAX_RECEIPTS = 32;
    public static final int NEXT = 1, PREVIOUS = 2, EDIT = 3;
    public static final int HELLO = 1, STATE = 2, COMMAND = 3, CLOSE = 4;
    public static final int EDIT_DIAGNOSTICS = 5;
    public static final int RELAY_EVENT = 1, RELAY_STATE = 2, RELAY_STOP = 3;
    public static final int CALLBACK_STATE = 1, CALLBACK_RESULT = 2;
    public static final int BOOTSTRAP_TRANSACTION = 0x485750;
    public static final int ROLE_MODULE = 1, ROLE_PRODUCER = 2;
    public static final int SESSION_ARM = 1, SESSION_STATE = 2, SESSION_EVENT = 3, SESSION_LAUNCH_EDIT = 4;
    public static final int PRODUCER_LAUNCH_EDIT = 1, PRODUCER_REVOKE = 2;
    public static final String BOOTSTRAP = "personal.wallpaper.os4.e1.LOCK_BOOTSTRAP.v1";
    public static final String SESSION = "personal.wallpaper.os4.e1.LOCK_SESSION.v1";
    public static final String PRODUCER = "personal.wallpaper.os4.e1.LOCK_PRODUCER.v1";
    public static final String COMMITTED = "COMMITTED", EDIT_READY = "EDIT_READY", NO_ENGINE = "NO_ENGINE";
    public static final String STALE_TICKET = "STALE_TICKET", BUSY_EDIT = "BUSY_EDIT", CANCELLED = "CANCELLED";
    public static final String FAILED = "FAILED", EXPIRED = "EXPIRED", TARGET_NOT_LOCK = "TARGET_NOT_LOCK";
    public static final String UNKNOWN_STATE = "UNKNOWN_STATE";
    public static final String HOST = "personal.wallpaper.os3.LOCK_HOST.v1";
    public static final String RELAY = "personal.wallpaper.os4.e1.LOCK_RELAY.v1";
    public static final String CALLBACK = "personal.wallpaper.os4.e1.LOCK_CALLBACK.v1";
    public static final String HOST_PACKAGE = "personal.wallpaper.os3";
    public static final String MODULE_PACKAGE = "personal.wallpaper.os4.e1";
    public static final String HOST_CERT = "56A7DCEDC755870317CFDC030E4E38BB57ED0BB66CC931E568AF27874323DEAF";
    public static final String MODULE_CERT = "3A1952E0EED6A417B8D7CC770B3391B80BC95C9A93FE2BF3EE444636C32B63DA";

    private LockProtocol() { }

    /** 稳定引擎场景与当前图片票据分开；不包含URI或图片元数据。 */
    public static String sceneTicket(String scene,String image) {
        if(!uuidPart(scene)||!uuidPart(image))throw new IllegalArgumentException("INVALID_SCENE_TICKET");
        return "LOCK2:"+scene+":"+image;
    }
    public static String sceneId(String ticket) {
        if(ticket==null||ticket.length()!=79||!ticket.startsWith("LOCK2:")||ticket.charAt(42)!=':')return null;
        String scene=ticket.substring(6,42),image=ticket.substring(43);
        return uuidPart(scene)&&uuidPart(image)?scene:null;
    }
    private static boolean uuidPart(String value) {
        if(value==null||value.length()!=36)return false;
        for(int i=0;i<36;i++){char c=value.charAt(i);if(i==8||i==13||i==18||i==23){if(c!='-')return false;}else if(!(c>='0'&&c<='9'||c>='a'&&c<='f'))return false;}
        return true;
    }
    /** ready=0时只声明输入场景存在，不伪称渲染或编辑READY。 */
    public static String inputOnlyPriorityReason(String hostReason) {
        if(hostReason==null)return null;
        return switch(hostReason){case "BUSY_EDIT"->"INPUT_ONLY_NATIVE_PRIORITY_BUSY_EDIT";case "UNKNOWN_STATE"->"INPUT_ONLY_NATIVE_PRIORITY_UNKNOWN_STATE";case "STALE_TICKET"->"INPUT_ONLY_NATIVE_PRIORITY_STALE_TICKET";default->null;};
    }
    public static boolean priorityInputOnly(String reason) {
        return "INPUT_ONLY_NATIVE_PRIORITY_BUSY_EDIT".equals(reason)||"INPUT_ONLY_NATIVE_PRIORITY_UNKNOWN_STATE".equals(reason)||"INPUT_ONLY_NATIVE_PRIORITY_STALE_TICKET".equals(reason);
    }
    public static boolean priorityInputOnlyAllowed(boolean nativeEnabled,boolean priorityEnabled,boolean modeCurrent,boolean renderReady,String ticket,String reason) {
        return nativeEnabled&&priorityEnabled&&modeCurrent&&!renderReady&&sceneId(ticket)!=null&&inputOnlyPriorityReason(reason)!=null;
    }

    /** 令牌不包含URI、路径、控制字符或自定义Parcelable。 */
    public static boolean token(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_TOKEN_CHARS) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '-' || c == '_' || c == ':')) return false;
        }
        return true;
    }

    public static boolean action(int value) { return value == NEXT || value == PREVIOUS || value == EDIT; }

    /** 使用同一次启动的elapsedRealtime；接收期限不是图片解码期限。 */
    public static boolean timely(int action, long issued, long now) {
        long ttl = action == EDIT ? 5000 : 1000;
        return action(action) && issued >= 0 && now >= issued && now - issued <= ttl;
    }

    public static boolean exactIdentity(String expectedPackage, String expectedCertificate,
            int callingUid, int expectedUid, String[] uidPackages, String[] currentSigners,
            boolean enabled, boolean multipleSigners) {
        return callingUid >= 10000 && callingUid == expectedUid && enabled && !multipleSigners
                && uidPackages != null && uidPackages.length == 1
                && expectedPackage.equals(uidPackages[0])
                && currentSigners != null && currentSigners.length == 1
                && expectedCertificate.equals(currentSigners[0]);
    }

    public static final class Command {
        public final String session, ticket, requestId;
        public final long sequence, issuedElapsed;
        public final int action;
        public Command(String session, String ticket, String requestId, long sequence, long issuedElapsed, int action) {
            this.session = session; this.ticket = ticket; this.requestId = requestId;
            this.sequence = sequence; this.issuedElapsed = issuedElapsed; this.action = action;
        }
        public boolean valid() {
            return token(session) && token(ticket) && token(requestId) && sequence > 0
                    && sequence < Long.MAX_VALUE && issuedElapsed >= 0 && action(action);
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Command)) return false;
            Command c = (Command) value;
            return sequence == c.sequence && issuedElapsed == c.issuedElapsed && action == c.action
                    && Objects.equals(session, c.session) && Objects.equals(ticket, c.ticket)
                    && Objects.equals(requestId, c.requestId);
        }
        @Override public int hashCode() { return Objects.hash(session, ticket, requestId, sequence, issuedElapsed, action); }
    }

    /** 必须先鉴别调用者及连接会话，再使用本有限幂等表；全部方法同步。 */
    public static final class Ledger {
        private static final class Entry {
            final Command command;
            String result = "ACCEPTED";
            Entry(Command command) { this.command = command; }
        }
        private final String session;
        private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        private long highWater;
        public Ledger(String session) {
            if (!token(session)) throw new IllegalArgumentException("BAD_SESSION");
            this.session = session;
        }
        public synchronized String admit(Command command, long now) {
            if (command == null || !command.valid()) return "BAD_REQUEST";
            if (!session.equals(command.session)) return "STALE_SESSION";
            Entry previous = entries.get(command.requestId);
            if (previous != null) return previous.command.equals(command) ? previous.result : "REQUEST_ID_CONFLICT";
            if (command.sequence <= highWater) return "REPLAY";
            if (!timely(command.action, command.issuedElapsed, now)) return "EXPIRED";
            highWater = command.sequence;
            entries.put(command.requestId, new Entry(command));
            if (entries.size() > MAX_RECEIPTS) entries.remove(entries.keySet().iterator().next());
            return "ACCEPTED";
        }
        public synchronized void complete(String requestId, String result) {
            Entry entry = entries.get(requestId);
            if (entry != null && token(result)) entry.result = result;
        }
        public synchronized boolean contains(String requestId) { return entries.containsKey(requestId); }
        public synchronized int size() { return entries.size(); }
    }
}
