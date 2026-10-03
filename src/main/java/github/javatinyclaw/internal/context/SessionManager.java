package github.javatinyclaw.internal.context;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// ==========================================
// 全局 Session Manager: 用于多用户/多终端隔离
// ==========================================
public class SessionManager {
    private final Map<String, Session> sessions = new HashMap<>();
    private final ReentrantReadWriteLock mu = new ReentrantReadWriteLock();

    public static final SessionManager globalSessionMgr = new SessionManager();

    // GetOrCreate 获取或创建一个会话
    public Session getOrCreate(String id, String workDir) {
        mu.writeLock().lock();
        try {
            Session sess = sessions.get(id);
            if (sess != null) {
                return sess;
            }
            sess = Session.newSession(id, workDir);
            sessions.put(id, sess);
            return sess;
        } finally {
            mu.writeLock().unlock();
        }
    }
}
