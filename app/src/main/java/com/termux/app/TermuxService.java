package com.termux.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import com.alpine_terminal.R;
import com.termux.terminal.EmulatorDebug;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSession.SessionChangedCallback;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import com.alpine_terminal.MainActivity;

public final class TermuxService extends Service implements SessionChangedCallback {

    private static final String NOTIFICATION_CHANNEL_ID = "termux_notification_channel";
	
	public static final String FILES_PATH = TermuxActivity.activity.getFilesDir().getAbsolutePath();
	public static final String PROOT_PATH = FILES_PATH + "/proot";
	public static final String BUSYBOX = PROOT_PATH + "/busybox";
	public static final String PROOT = PROOT_PATH + "/proot";
	
	public static final String ALPINE = FILES_PATH + "/alpine";
	public static final String PREFIX_PATH = ALPINE + "/usr";
	
    private static final int NOTIFICATION_ID = 13457;

    private static final String ACTION_STOP_SERVICE = "com.termux.service_stop";
    private static final String ACTION_LOCK_WAKE = "com.termux.service_wake_lock";
    private static final String ACTION_UNLOCK_WAKE = "com.termux.service_wake_unlock";
    public static final String ACTION_EXECUTE = "com.termux.service_execute";
    public static final String EXTRA_ARGUMENTS = "com.termux.execute.arguments";
    public static final String EXTRA_CURRENT_WORKING_DIRECTORY = "com.termux.execute.cwd";
    public static final String EXTRA_EXECUTE_IN_BACKGROUND = "com.termux.execute.background";
	
    class LocalBinder extends Binder {
        public final TermuxService service = TermuxService.this;
    }

    private final IBinder mBinder = new LocalBinder();

    private final Handler mHandler = new Handler();
	
    final List<TerminalSession> mTerminalSessions = new ArrayList<>();

    final List<BackgroundJob> mBackgroundTasks = new ArrayList<>();
	
    SessionChangedCallback mSessionChangeCallback;
	
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    @SuppressLint("Wakelock")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (ACTION_STOP_SERVICE.equals(action)) {
            stopSelf();
            TermuxActivity.activity.finish();
        } else if (ACTION_LOCK_WAKE.equals(action)) {
            if (mWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, EmulatorDebug.LOG_TAG + ":service-wakelock");
                mWakeLock.acquire();
				
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, EmulatorDebug.LOG_TAG);
                mWifiLock.acquire();

                String packageName = getPackageName();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                        Intent whitelist = new Intent();
                        whitelist.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        whitelist.setData(Uri.parse("package:" + packageName));
                        whitelist.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                        try {
                            startActivity(whitelist);
                        } catch (ActivityNotFoundException e) {}
                    }
                }
                updateNotification();
            }
        } else if (ACTION_UNLOCK_WAKE.equals(action)) {
            if (mWakeLock != null) {
                mWakeLock.release();
                mWakeLock = null;

                mWifiLock.release();
                mWifiLock = null;

                updateNotification();
            }
        }
        return Service.START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        setupNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    public void updateNotification() {
        if (mWakeLock == null && mTerminalSessions.isEmpty() && mBackgroundTasks.isEmpty()) {
            stopSelf();
            TermuxActivity.activity.finish();
        } else
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent notifyIntent = new Intent(this, MainActivity.class);
        notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notifyIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this);
		builder.setContentTitle( getText(R.string.app_name));
		
		TerminalSession currentSession = TermuxActivity.mTerminalView.getCurrentSession();
        int taskCount = mBackgroundTasks.size();
		String contentText = "";
		
		if(currentSession != null){
			contentText = currentSession.mSessionName + "(" + (MainActivity.terminal.getSessionPosition(currentSession) + 1) + ")";
		}
		
		builder.setSmallIcon(R.drawable.ic_service_notification);
		
		if(taskCount > 1){
			contentText += ", " + taskCount + " -task" + (taskCount == 1 ? "" : "s");
		}
		
		Resources res = getResources();
		final boolean wakeLockHeld = mWakeLock != null;
        if (wakeLockHeld) contentText += " (wake lock held)";
		
		builder.setContentText(contentText);

		Intent exitIntent = new Intent(this, TermuxService.class).setAction(ACTION_STOP_SERVICE);
		builder.addAction(android.R.drawable.ic_delete, "Stop", PendingIntent.getService(this, 0, exitIntent, 0));

		String newWakeAction = wakeLockHeld ? ACTION_UNLOCK_WAKE : ACTION_LOCK_WAKE;
		Intent toggleWakeLockIntent = new Intent(this, TermuxService.class).setAction(newWakeAction);
		String actionTitle = res.getString(wakeLockHeld ?
										   R.string.notification_action_wake_unlock :
										   R.string.notification_action_wake_lock);
		int actionIcon = wakeLockHeld ? android.R.drawable.ic_lock_idle_lock : android.R.drawable.ic_lock_lock;
		builder.addAction(actionIcon, actionTitle, PendingIntent.getService(this, 0, toggleWakeLockIntent, 0));

        builder.setContentIntent(pendingIntent);
		builder.setOngoing(true);
		builder.setPriority((wakeLockHeld) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_LOW);
		builder.setShowWhen(false);
		builder.setColor(0xFF607D8B);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			builder.setChannelId(NOTIFICATION_CHANNEL_ID);
		}
        return builder.build();
    }

    @Override
    public void onDestroy() {
        File termuxTmpDir = new File(TermuxService.PREFIX_PATH + "/tmp");

        if (termuxTmpDir.exists()) {
            try {
                TermuxInstaller.deleteFolder(termuxTmpDir.getCanonicalFile());
            } catch (Exception e) {}

            termuxTmpDir.mkdirs();
        }

        if (mWakeLock != null) mWakeLock.release();
        if (mWifiLock != null) mWifiLock.release();

        stopForeground(true);

        for (int i = 0; i < mTerminalSessions.size(); i++)
            mTerminalSessions.get(i).finishIfRunning();
    }

    public List<TerminalSession> getSessions() {
        return mTerminalSessions;
    }

    boolean isLoginShell = false;
	TerminalSession createTermSession(String cwd, boolean failSafe) {
        
        if (cwd == null) cwd = FILES_PATH;

        String[] env = BackgroundJob.buildEnvironment(failSafe, cwd);

		String[] prootArgs = {
			"sh",
			"-c",
			PROOT + " --link2symlink -0 -r " + ALPINE + " -b /dev/ -b /sys/ -b /proc/ -b /sdcard -b /storage -b $HOME -w /root /usr/bin/env TMPDIR=/tmp HOME=/root PREFIX=/usr SHELL=/bin/sh TERM=\"$TERM\" LANG=$LANG PATH=/bin:/usr/bin:/sbin:/usr/sbin /bin/sh --login"
		};
		

	    String[] processArgs = BackgroundJob.setupProcessArgs(BUSYBOX, prootArgs);
        String executablePath = processArgs[0];

        int lastSlashIndex = executablePath.lastIndexOf('/');

        String processName = (isLoginShell ? "-" : "") +
            (lastSlashIndex == -1 ? executablePath : executablePath.substring(lastSlashIndex + 1));

        String[] args = new String[processArgs.length];
        args[0] = processName;
        if (processArgs.length > 1) System.arraycopy(processArgs, 1, args, 1, processArgs.length - 1);

        TerminalSession session = new TerminalSession(executablePath, cwd, args, env, this);
        session.mSessionName = "Flutter Terminal";
        mTerminalSessions.add(session);
        updateNotification();
        return session;
    }
	
    public int removeTermSession(TerminalSession sessionToRemove) {
        int indexOfRemoved = mTerminalSessions.indexOf(sessionToRemove);
        mTerminalSessions.remove(indexOfRemoved);
        if (mTerminalSessions.isEmpty() && mWakeLock == null) {
            TermuxActivity.activity.finish();
            stopSelf();
        } else {
            updateNotification();
        }
        return indexOfRemoved;
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        if (mSessionChangeCallback != null) mSessionChangeCallback.onTitleChanged(changedSession);
    }

    @Override
    public void onSessionFinished(final TerminalSession finishedSession) {
        if (mSessionChangeCallback != null)
            mSessionChangeCallback.onSessionFinished(finishedSession);
    }

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (mSessionChangeCallback != null) mSessionChangeCallback.onTextChanged(changedSession);
    }

    @Override
    public void onClipboardText(TerminalSession session, String text) {
        if (mSessionChangeCallback != null) mSessionChangeCallback.onClipboardText(session, text);
    }

    @Override
    public void onBell(TerminalSession session) {
        if (mSessionChangeCallback != null) mSessionChangeCallback.onBell(session);
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
        if (mSessionChangeCallback != null) mSessionChangeCallback.onColorsChanged(session);
    }

    public void onBackgroundJobExited(final BackgroundJob task) {
        mHandler.post(new Runnable(){
			@Override
			public void run(){
            mBackgroundTasks.remove(task);
            updateNotification();
			}
        });
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        String channelName = "Flutter Terminal";
        String channelDescription = "Notifications from Flutter Terminal";
        int importance = NotificationManager.IMPORTANCE_LOW;

        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName,importance);
        channel.setDescription(channelDescription);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }
}
