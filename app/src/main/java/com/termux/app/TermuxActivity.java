package com.termux.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.Toast;

import com.alpine_terminal.MainActivity;
import com.alpine_terminal.R;
import com.termux.terminal.EmulatorDebug;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSession.SessionChangedCallback;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.content.DialogInterface;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.AdapterView;


public final class TermuxActivity implements ServiceConnection {

    private static final int CONTEXTMENU_SELECT_URL_ID = 0;
    private static final int CONTEXTMENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXTMENU_PASTE_ID = 3;
    private static final int CONTEXTMENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXTMENU_RESET_TERMINAL_ID = 5;
    private static final int CONTEXTMENU_TOGGLE_KEEP_SCREEN_ON = 9;

    private static final int REQUESTCODE_PERMISSION_STORAGE = 1234;

    private static final String RELOAD_STYLE_ACTION = "com.alpine_terminal.reload_style";

	public static Activity activity;

    public static TerminalView mTerminalView;

    ExtraKeysView mExtraKeysView;

    TermuxPreferences mSettings;

    static TermuxService mTermService;

    boolean mIsVisible;

    TerminalCallback tcb;

    final SoundPool mBellSoundPool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
        new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
		.setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build();
    int mBellSoundId;

    private final BroadcastReceiver mBroadcastReceiever = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mIsVisible) {
                String whatToReload = intent.getStringExtra(RELOAD_STYLE_ACTION);
                if ("storage".equals(whatToReload)) {
                    if (ensureStoragePermissionGranted())
                        TermuxInstaller.setupStorageSymlinks(activity);
                    return;
                }
                checkForFontAndColors();
                mSettings.reloadFromProperties(activity);

                if (mExtraKeysView != null) {
					mExtraKeysView.reload();
                }
            }
        }
    };


	public TermuxActivity(final Activity activity, TerminalCallback tcb){
        this.tcb = tcb;
        FileUtils.context = activity;
        TermuxActivity.activity = activity;
        mTerminalView = activity.findViewById(R.id.terminal_view);
        if(!FileUtils.isExistFile(TermuxService.ALPINE + "/etc")) {

            final AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Installing....")
                    .setMessage("Please wait few seconds...")
                    .setPositiveButton("Close", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dia, int which) {
                            activity.finish();
                        }
                    })
                    .create();
            dialog.show();

            new Thread(new Runnable(){
                @Override
                public void run(){
                    FileUtils.makeDir(TermuxService.FILES_PATH);
                    FileUtils.makeDir(TermuxService.PROOT_PATH);
                    FileUtils.copyAssetFolder("proot", TermuxService.PROOT_PATH);

                    for(File file : new File(TermuxService.PROOT_PATH).listFiles()){
                        file.setExecutable(true);
                    }

                    FileUtils.copyAssetFile("alpine.iso", TermuxService.FILES_PATH + "/alpine.tar.xz");
                    try {
                        ProcessBuilder processBuilder = new ProcessBuilder();
                        processBuilder.command("sh", "-c", "./proot/busybox tar -xf alpine.tar.xz");
                        processBuilder.directory(new File(TermuxService.FILES_PATH));
                        Process process = processBuilder.start();
                        process.waitFor();
                    }catch(Exception ex){}

                    FileUtils.deleteFile(TermuxService.FILES_PATH + "/alpine.tar.xz");
                    onStart();

                    dialog.dismiss();
                }
            }).start();









            /*

            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    final AlertDialog dialog;
                    dialog = new AlertDialog.Builder(activity)
                            .setTitle("Alpine Installing....")
                            .setMessage("Please wait few seconds...").create();

                    dialog.show();
                    FileUtils.makeDir(TermuxService.FILES_PATH);
                    FileUtils.makeDir(TermuxService.PROOT_PATH);
                    FileUtils.copyAssetFolder("proot", TermuxService.PROOT_PATH);

                    for(File file : new File(TermuxService.PROOT_PATH).listFiles()){
                        file.setExecutable(true);
                    }

                    FileUtils.copyAssetFile("alpine.iso", TermuxService.FILES_PATH + "/alpine.tar.xz");
                    try {
                        ProcessBuilder processBuilder = new ProcessBuilder();
                        processBuilder.command("sh", "-c", "./proot/busybox tar -xf alpine.tar.xz");
                        processBuilder.directory(new File(TermuxService.FILES_PATH));
                        Process process = processBuilder.start();
                        process.waitFor();
                    }catch(Exception ex){}

                    FileUtils.deleteFile(TermuxService.FILES_PATH + "/alpine.tar.xz");
                    onStart();

                    dialog.dismiss();
                }
            });

             */
        } else {
            onStart();
        }
	}

    public void onStart() {
		try {

			mSettings = new TermuxPreferences(activity);

			mTerminalView.setOnKeyListener(new TermuxViewClient(TermuxActivity.this));

			mTerminalView.setTextSize(mSettings.getFontSize());
			mTerminalView.setKeepScreenOn(mSettings.isScreenAlwaysOn());
			mTerminalView.requestFocus();

			mExtraKeysView = (ExtraKeysView) activity.findViewById(R.id.extra_keys);
			mExtraKeysView.reload();


			activity.registerForContextMenu(mTerminalView);

			Intent serviceIntent = new Intent(activity, TermuxService.class);

			activity.startService(serviceIntent);
			
			if (!activity.bindService(serviceIntent, TermuxActivity.this, 0))
				throw new RuntimeException("bindService() failed");

			checkForFontAndColors();

			mBellSoundId = mBellSoundPool.load(activity, R.raw.bell, 1);

			mIsVisible = true;

			if (mTermService != null) {
				switchToSession(getStoredCurrentSessionOrLast());
			}

			activity.registerReceiver(mBroadcastReceiever, new IntentFilter(RELOAD_STYLE_ACTION));
			mTerminalView.onScreenUpdated();
		} catch(Exception e){
			
		}
    }
	
    public void onStop() {
		try {
			mIsVisible = false;
			TerminalSession currentSession = getCurrentTermSession();
			if (currentSession != null) TermuxPreferences.storeCurrentSession(activity, currentSession);
			try{
				activity.unregisterReceiver(mBroadcastReceiever);
			} catch (Exception ex){}
		} catch(Exception e){
			
		}
	}
	
	public void addSession(String terminalName, boolean failSafe) {
		
		try {
			TerminalSession currentSession = getCurrentTermSession();
			String cwd = (currentSession == null) ? null : currentSession.getCwd();
			TerminalSession newSession = mTermService.createTermSession(cwd, failSafe);
			newSession.mSessionName = terminalName;
			switchToSession(newSession);
			mTermService.updateNotification();
		} catch (Exception e){
			
		}
		
	}
	
	public void removeSession(final int position){
		try {
			if(position < 0 || position >= mTermService.getSessions().size()){
				removeFinishedSession(getCurrentTermSession());
			} else {
				removeFinishedSession((TerminalSession)mTermService.getSessions().get(position));
			}
			mTermService.updateNotification();
		} catch(Exception e){
			
		}
	}
	
	public void swipeSession(final int position){
		try {
			mTerminalView.requestFocus();
			if(position < 0 || position >= mTermService.getSessions().size()){
				switchToSession(getCurrentTermSession());
			} else {
				switchToSession((TerminalSession)mTermService.getSessions().get(position));
			}
			mTermService.updateNotification();
		} catch(Exception e){
			
		}
	}

	public void renameSession(final int position, final String name) {
		try {
			if(position < 0 || position >= mTermService.getSessions().size()){
				getCurrentTermSession().mSessionName = name;
			} else {
				mTermService.getSessions().get(position).mSessionName = name;
			}
			mTermService.updateNotification();
		} catch(Exception e){
			
		}
    }
	
	public void resetSession(final int position){
		try {
			if(position < 0 || position >= mTermService.getSessions().size()){
				getCurrentTermSession().reset();
			} else {
				((TerminalSession)mTermService.getSessions().get(position)).reset();
			}
		} catch(Exception e){
			
		}
	}

	public void runCommands(final int position, final String commands){
		try {
			if(position < 0 || position >= mTermService.getSessions().size()){
				getCurrentTermSession().getEmulator().paste(commands);
			} else {
				((TerminalSession) mTermService.getSessions().get(position)).reset();
			}
		} catch(Exception e){
		}
	}
	
	public String getOutput(int position){
		if(position < 0 || position >= mTermService.getSessions().size()){
			return getCurrentTermSession().getEmulator().getScreen().getTranscriptText();
		} else {
			return ((TerminalSession)mTermService.getSessions().get(position)).getEmulator().getScreen().getTranscriptText();
		}
	}

	public int getPid(){
		return getCurrentTermSession().getPid();
	}

	public int getExitStatus(){
		return getCurrentTermSession().getExitStatus();
	}

    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (requestCode == REQUESTCODE_PERMISSION_STORAGE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            TermuxInstaller.setupStorageSymlinks(activity);
        }
    }

	public void doPaste() {
		ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
		ClipData clipData = clipboard.getPrimaryClip();
		if (clipData == null) return;
		CharSequence paste = clipData.getItemAt(0).coerceToText(activity);
		if (!TextUtils.isEmpty(paste))
			getCurrentTermSession().getEmulator().paste(paste.toString());
	}

    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentTermSession();
        if (currentSession == null) return;
		menu.add(Menu.NONE, CONTEXTMENU_RESET_TERMINAL_ID, Menu.NONE, R.string.reset_terminal);
        menu.add(Menu.NONE, CONTEXTMENU_SELECT_URL_ID, Menu.NONE, R.string.select_url);
        menu.add(Menu.NONE, CONTEXTMENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.select_all_and_share);
        menu.add(Menu.NONE, CONTEXTMENU_KILL_PROCESS_ID, Menu.NONE, activity.getResources().getString(R.string.kill_process, getCurrentTermSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXTMENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.toggle_keep_screen_on).setCheckable(true).setChecked(mSettings.isScreenAlwaysOn());
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return true;
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        mTermService = ((TermuxService.LocalBinder) service).service;

        mTermService.mSessionChangeCallback = new SessionChangedCallback() {
            @Override
            public void onTextChanged(TerminalSession changedSession) {
                if (!mIsVisible) return;
                if (getCurrentTermSession() == changedSession) mTerminalView.onScreenUpdated();
            }

            @Override
            public void onTitleChanged(TerminalSession updatedSession) {
                if (!mIsVisible) return;
                if (updatedSession != getCurrentTermSession()) {
                }
            }

            @Override
            public void onSessionFinished(final TerminalSession finishedSession) {
                if (activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                    if (mTermService.getSessions().size() > 1) {
                        removeFinishedSession(finishedSession);
                    }
                } else {
                    if (finishedSession.getExitStatus() == 0 || finishedSession.getExitStatus() == 130) {
						removeFinishedSession(finishedSession);
                    }
                }
            }

            @Override
            public void onClipboardText(TerminalSession session, String text) {
                if (!mIsVisible) return;
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(new ClipData(null, new String[]{"text/plain"}, new ClipData.Item(text)));
            }

            @Override
            public void onBell(TerminalSession session) {
                if (!mIsVisible) return;

                switch (mSettings.mBellBehaviour) {
                    case TermuxPreferences.BELL_BEEP:
                        mBellSoundPool.play(mBellSoundId, 1.f, 1.f, 1, 0, 1.f);
                        break;
                    case TermuxPreferences.BELL_VIBRATE:
                        BellUtil.getInstance(activity).doBell();
                        break;
                    case TermuxPreferences.BELL_IGNORE:
                        // Ignore the bell character.
                        break;
                }

            }

            @Override
            public void onColorsChanged(TerminalSession changedSession) {
                if (getCurrentTermSession() == changedSession) updateBackgroundColor();
            }
        };

        if (mTermService.getSessions().isEmpty()) {
            if (mIsVisible) {
                if (mTermService != null){
                    addSession("Flutter Terminal", false);
                } else {
                    TermuxActivity.activity.finish();
                    return;
                }
            }
        } else {
			switchToSession(getStoredCurrentSessionOrLast());
        }

    }

	@Override
    public void onServiceDisconnected(ComponentName name) {
        activity.finish();
    }
    public int getSessionPosition(TerminalSession session){
        return mTermService.getSessions().indexOf(session);
    }
    public void switchToSession(boolean forward) {
        TerminalSession currentSession = getCurrentTermSession();
        int index = mTermService.getSessions().indexOf(currentSession);
        if (forward) {
            if (++index >= mTermService.getSessions().size()) index = 0;
        } else {
            if (--index < 0) index = mTermService.getSessions().size() - 1;
        }
        switchToSession(mTermService.getSessions().get(index));
    }

    public static TerminalSession getCurrentTermSession() {
        return mTerminalView.getCurrentSession();
    }

    /** Try switching to session and note about it, but do nothing if already displaying the session. */
    void switchToSession(TerminalSession session) {
        if (mTerminalView.attachSession(session)) {
            noteSessionInfo();
            updateBackgroundColor();
        }
    }

    void noteSessionInfo() {
        if (!mIsVisible) return;
        TerminalSession session = getCurrentTermSession();
        final int indexOfSession = mTermService.getSessions().indexOf(session);
    }

    static LinkedHashSet<CharSequence> extractUrls(String text) {

        StringBuilder regex_sb = new StringBuilder();

        regex_sb.append("(");                       // Begin first matching group.
        regex_sb.append("(?:");                     // Begin scheme group.
        regex_sb.append("dav|");                    // The DAV proto.
        regex_sb.append("dict|");                   // The DICT proto.
        regex_sb.append("dns|");                    // The DNS proto.
        regex_sb.append("file|");                   // File path.
        regex_sb.append("finger|");                 // The Finger proto.
        regex_sb.append("ftp(?:s?)|");              // The FTP proto.
        regex_sb.append("git|");                    // The Git proto.
        regex_sb.append("gopher|");                 // The Gopher proto.
        regex_sb.append("http(?:s?)|");             // The HTTP proto.
        regex_sb.append("imap(?:s?)|");             // The IMAP proto.
        regex_sb.append("irc(?:[6s]?)|");           // The IRC proto.
        regex_sb.append("ip[fn]s|");                // The IPFS proto.
        regex_sb.append("ldap(?:s?)|");             // The LDAP proto.
        regex_sb.append("pop3(?:s?)|");             // The POP3 proto.
        regex_sb.append("redis(?:s?)|");            // The Redis proto.
        regex_sb.append("rsync|");                  // The Rsync proto.
        regex_sb.append("rtsp(?:[su]?)|");          // The RTSP proto.
        regex_sb.append("sftp|");                   // The SFTP proto.
        regex_sb.append("smb(?:s?)|");              // The SAMBA proto.
        regex_sb.append("smtp(?:s?)|");             // The SMTP proto.
        regex_sb.append("svn(?:(?:\\+ssh)?)|");     // The Subversion proto.
        regex_sb.append("tcp|");                    // The TCP proto.
        regex_sb.append("telnet|");                 // The Telnet proto.
        regex_sb.append("tftp|");                   // The TFTP proto.
        regex_sb.append("udp|");                    // The UDP proto.
        regex_sb.append("vnc|");                    // The VNC proto.
        regex_sb.append("ws(?:s?)");                // The Websocket proto.
        regex_sb.append(")://");                    // End scheme group.
        regex_sb.append(")");                       // End first matching group.


        // Begin second matching group.
        regex_sb.append("(");

        // User name and/or password in format 'user:pass@'.
        regex_sb.append("(?:\\S+(?::\\S*)?@)?");

        // Begin host group.
        regex_sb.append("(?:");

        // IP address (from http://www.regular-expressions.info/examples.html).
        regex_sb.append("(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)|");

        // Host name or domain.
        regex_sb.append("(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)(?:(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)*(?:\\.(?:[a-z\\u00a1-\\uffff]{2,})))?|");

        // Just path. Used in case of 'file://' scheme.
        regex_sb.append("/(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)");

        // End host group.
        regex_sb.append(")");

        // Port number.
        regex_sb.append("(?::\\d{1,5})?");

        // Resource path with optional query string.
        regex_sb.append("(?:/[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?");

        // Fragment.
        regex_sb.append("(?:#[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?");

        // End second matching group.
        regex_sb.append(")");

        final Pattern urlPattern = Pattern.compile(
            regex_sb.toString(),
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

        LinkedHashSet<CharSequence> urlSet = new LinkedHashSet<>();
        Matcher matcher = urlPattern.matcher(text);

        while (matcher.find()) {
            int matchStart = matcher.start(1);
            int matchEnd = matcher.end();
            String url = text.substring(matchStart, matchEnd);
            urlSet.add(url);
        }

        return urlSet;
    }

    void showUrlSelection() {
        String text = getCurrentTermSession().getEmulator().getScreen().getTranscriptTextWithFullLinesJoined();
        LinkedHashSet<CharSequence> urlSet = extractUrls(text);
        if (urlSet.isEmpty()) {
            new AlertDialog.Builder(activity).setMessage(R.string.select_url_no_found).show();
            return;
        }

        final CharSequence[] urls = urlSet.toArray(new CharSequence[0]);
        Collections.reverse(Arrays.asList(urls)); // Latest first.

        // Click to copy url to clipboard:
        final AlertDialog dialog = new AlertDialog.Builder(activity).setItems(urls, new DialogInterface.OnClickListener(){
				@Override
				public void onClick(DialogInterface di, int which){
					String url = (String) urls[which];
					ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
					clipboard.setPrimaryClip(new ClipData(null, new String[]{"text/plain"}, new ClipData.Item(url)));
					Toast.makeText(activity, R.string.select_url_copied_to_clipboard, Toast.LENGTH_LONG).show();
				}}

		).setTitle(R.string.select_url_dialog_title).create();

        // Long press to open URL:
        dialog.setOnShowListener(new DialogInterface.OnShowListener(){
				@Override
				public void onShow(DialogInterface di){

					ListView lv = dialog.getListView(); // this is a ListView with your "buds" in it
					lv.setOnItemLongClickListener(new OnItemLongClickListener(){
							@Override
							public boolean onItemLongClick(AdapterView parent,View view,int position,long id) {
								dialog.dismiss();
								String url = (String) urls[position];
								Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
								try {
									activity.startActivity(i, null);
								} catch (ActivityNotFoundException e) {
									// If no applications match, Android displays a system message.
									activity.startActivity(Intent.createChooser(i, null));
								}
								return true;
							}});
				}
			});

        dialog.show();
    }

    void changeFontSize(boolean increase) {
        mSettings.changeFontSize(activity, increase);
        mTerminalView.setTextSize(mSettings.getFontSize());
    }

    /** The current session as stored or the last one if that does not exist. */
    public TerminalSession getStoredCurrentSessionOrLast() {
        TerminalSession stored = TermuxPreferences.getCurrentSession(this);
        if (stored != null) return stored;
        List<TerminalSession> sessions = mTermService.getSessions();
        return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
    }

	void checkForFontAndColors() {
        try {
            @SuppressLint("SdCardPath") File fontFile = new File("/storage/emulated/0/font.ttf");
            @SuppressLint("SdCardPath") File colorsFile = new File("/storage/emulated/0/colors.properties");

            final Properties props = new Properties();
            if (colorsFile.isFile()) {
                try (InputStream in = new FileInputStream(colorsFile)) {
                    props.load(in);
                }
            }

            TerminalColors.COLOR_SCHEME.updateWith(props);
            TerminalSession session = getCurrentTermSession();
            if (session != null && session.getEmulator() != null) {
                session.getEmulator().mColors.reset();
            }
            updateBackgroundColor();

            final Typeface newTypeface = (fontFile.exists() && fontFile.length() > 0) ? Typeface.createFromFile(fontFile) : Typeface.MONOSPACE;
            mTerminalView.setTypeface(newTypeface);
        } catch (Exception e) {
            Log.e(EmulatorDebug.LOG_TAG, "Error in checkForFontAndColors()", e);
        }
    }

    void updateBackgroundColor() {
        TerminalSession session = getCurrentTermSession();
        if (session != null && session.getEmulator() != null) {
            mTerminalView.setBackgroundColor(session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
			mExtraKeysView.BUTTON_COLOR = session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND];
			mExtraKeysView.TEXT_COLOR = session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND];
			mExtraKeysView.setBackgroundColor(session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
			mExtraKeysView.reload();
        }
    }

    /** For processes to access shared internal storage (/sdcard) we need this permission. */
    public boolean ensureStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                return true;
            } else {
                activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUESTCODE_PERMISSION_STORAGE);
                return false;
            }
        }
        return false;
    }


    public void removeFinishedSession(TerminalSession finishedSession) {
		TermuxService service = mTermService;
        int index = service.removeTermSession(finishedSession);
        tcb.onClose(index);
        if (!mTermService.getSessions().isEmpty()) {
            if (index >= service.getSessions().size()) {
                index = service.getSessions().size() - 1;
            }
            switchToSession(service.getSessions().get(index));
        }
    }

    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentTermSession();
        switch (item.getItemId()) {
            case CONTEXTMENU_SELECT_URL_ID:
                showUrlSelection();
                return true;
            case CONTEXTMENU_SHARE_TRANSCRIPT_ID:
                if (session != null) {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    String transcriptText = session.getEmulator().getScreen().getTranscriptTextWithoutJoinedLines().trim();
                    // See https://github.com/termux/termux-app/issues/1166.
                    final int MAX_LENGTH = 100_000;
                    if (transcriptText.length() > MAX_LENGTH) {
                        int cutOffIndex = transcriptText.length() - MAX_LENGTH;
                        int nextNewlineIndex = transcriptText.indexOf('\n', cutOffIndex);
                        if (nextNewlineIndex != -1 && nextNewlineIndex != transcriptText.length() - 1) {
                            cutOffIndex = nextNewlineIndex + 1;
                        }
                        transcriptText = transcriptText.substring(cutOffIndex).trim();
                    }
                    intent.putExtra(Intent.EXTRA_TEXT, transcriptText);
                    intent.putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.share_transcript_title));
                    activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_transcript_chooser_title)));
                }
                return true;
            case CONTEXTMENU_PASTE_ID:
                doPaste();
                return true;
            case CONTEXTMENU_KILL_PROCESS_ID:
                final AlertDialog.Builder b = new AlertDialog.Builder(activity);
                b.setIcon(android.R.drawable.ic_dialog_alert);
                b.setMessage(R.string.confirm_kill_process);
                b.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener(){
						@Override
						public void onClick(DialogInterface dialog, int which){
							dialog.dismiss();
							getCurrentTermSession().finishIfRunning();
						}
					});
                b.setNegativeButton(android.R.string.no, null);
                b.show();
                return true;
            case CONTEXTMENU_RESET_TERMINAL_ID: {
					if (getCurrentTermSession() != null) {
						getCurrentTermSession().reset();
					}
					return true;
				}
            case CONTEXTMENU_TOGGLE_KEEP_SCREEN_ON: {
					if(mTerminalView.getKeepScreenOn()) {
						mTerminalView.setKeepScreenOn(false);
						mSettings.setScreenAlwaysOn(activity, false);
					} else {
						mTerminalView.setKeepScreenOn(true);
						mSettings.setScreenAlwaysOn(activity, true);
					}
					return true;
				}
            default:
                return false;
        }
    }

    public interface TerminalCallback {
        void onClose(int position);
    }
}
