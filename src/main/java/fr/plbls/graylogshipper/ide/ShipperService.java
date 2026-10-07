package fr.plbls.graylogshipper.ide;

import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import fr.plbls.graylogshipper.core.GelfDispatcher;
import fr.plbls.graylogshipper.core.LogSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Owns the GELF sender and the live log sessions (one per running process). */
@Service(Service.Level.APP)
public final class ShipperService implements Disposable {

    static final String NOTIFICATION_GROUP = "Graylog Log Shipper";
    private static final Logger LOG = Logger.getInstance(ShipperService.class);
    private static final long ERROR_NOTIFICATION_INTERVAL_MS = 60_000;
    private static final long MULTILINE_IDLE_MS = 400;

    private final GelfDispatcher dispatcher;
    private final Set<LogSession> sessions = ConcurrentHashMap.newKeySet();
    private final ScheduledFuture<?> flushTask;
    private volatile long lastErrorNotification;

    public ShipperService() {
        dispatcher = new GelfDispatcher(ShipperSettings.getInstance().toConfig(), 20_000,
                this::onSendError, this::onRecovered);
        dispatcher.start();
        flushTask = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(this::flushIdle, 250, 250, TimeUnit.MILLISECONDS);
    }

    public static ShipperService getInstance() {
        return ApplicationManager.getApplication().getService(ShipperService.class);
    }

    /** Called after the settings changed. */
    public void reconfigure() {
        dispatcher.updateConfig(ShipperSettings.getInstance().toConfig());
    }

    public GelfDispatcher.Stats stats() {
        return dispatcher.stats();
    }

    public LogSession openSession(Map<String, Object> baseFields) {
        LogSession session = new LogSession(baseFields, dispatcher::config, dispatcher::offer, MULTILINE_IDLE_MS);
        sessions.add(session);
        return session;
    }

    public void closeSession(LogSession session) {
        sessions.remove(session);
        session.close();
    }

    private void flushIdle() {
        long now = System.currentTimeMillis();
        for (LogSession s : sessions) {
            try {
                s.flushIdle(now);
            } catch (RuntimeException e) {
                LOG.warn("Graylog Log Shipper: flush failed", e);
            }
        }
    }

    private void onSendError(String error) {
        LOG.info("Graylog Log Shipper: cannot send logs: " + error);
        long now = System.currentTimeMillis();
        if (now - lastErrorNotification < ERROR_NOTIFICATION_INTERVAL_MS) {
            return;
        }
        lastErrorNotification = now;
        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("Graylog injoignable",
                        "Les logs ne sont pas envoyés (" + escape(error) + "). Nouvel essai toutes les 2 s.",
                        NotificationType.WARNING)
                .addAction(NotificationAction.createSimple("Réglages…",
                        () -> ShowSettingsUtil.getInstance().showSettingsDialog(null, ShipperConfigurable.class)))
                .addAction(NotificationAction.createSimple("Couper l'envoi", () -> {
                    ShipperSettings.getInstance().getState().enabled = false;
                }))
                .notify(null);
    }

    private void onRecovered() {
        LOG.info("Graylog Log Shipper: Graylog reachable again");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void dispose() {
        flushTask.cancel(false);
        for (LogSession s : sessions) {
            s.close();
        }
        sessions.clear();
        dispatcher.shutdown(1500);
    }
}
