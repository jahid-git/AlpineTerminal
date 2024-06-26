package com.termux.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A background job launched by Termux.
 */
public final class BackgroundJob {

    private static final String LOG_TAG = "termux-task";

    final Process mProcess;

    public BackgroundJob(String cwd, String fileToExecute, final String[] args, final TermuxService service){
        this(cwd, fileToExecute, args, service, null);
    }

    public BackgroundJob(String cwd, String fileToExecute, final String[] args, final TermuxService service,final PendingIntent pendingIntent) {
		String[] env = buildEnvironment(false, cwd);
		
        if (cwd == null) cwd = TermuxService.FILES_PATH;

        final String[] progArray = setupProcessArgs(fileToExecute, args);
        final String processDescription = Arrays.toString(progArray);

        Process process;
        try {
            process = Runtime.getRuntime().exec(progArray, env, new File(cwd));
        } catch (IOException e) {
            mProcess = null;
            // TODO: Visible error message?
            Log.e(LOG_TAG, "Failed running background job: " + processDescription, e);
            return;
        }

        mProcess = process;
        final int pid = getPid(mProcess);
        final Bundle result = new Bundle();
        final StringBuilder outResult = new StringBuilder();
        final StringBuilder errResult = new StringBuilder();

        final Thread errThread = new Thread() {
            @Override
            public void run() {
                InputStream stderr = mProcess.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8));
                String line;
                try {
                    // FIXME: Long lines.
                    while ((line = reader.readLine()) != null) {
                        errResult.append(line).append('\n');
                        Log.i(LOG_TAG, "[" + pid + "] stderr: " + line);
                    }
                } catch (IOException e) {
                    // Ignore.
                }
            }
        };
        errThread.start();

        new Thread() {
            @Override
            public void run() {
                Log.i(LOG_TAG, "[" + pid + "] starting: " + processDescription);
                InputStream stdout = mProcess.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));

                String line;
                try {
                    // FIXME: Long lines.
                    while ((line = reader.readLine()) != null) {
                        Log.i(LOG_TAG, "[" + pid + "] stdout: " + line);
                        outResult.append(line).append('\n');
                    }
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Error reading output", e);
                }

                try {
                    int exitCode = mProcess.waitFor();
                    service.onBackgroundJobExited(BackgroundJob.this);
                    if (exitCode == 0) {
                        Log.i(LOG_TAG, "[" + pid + "] exited normally");
                    } else {
                        Log.w(LOG_TAG, "[" + pid + "] exited with code: " + exitCode);
                    }

                    result.putString("stdout", outResult.toString());
                    result.putInt("exitCode", exitCode);

                    errThread.join();
                    result.putString("stderr", errResult.toString());

                    Intent data = new Intent();
                    data.putExtra("result", result);

                    if(pendingIntent != null) {
                        try {
                            pendingIntent.send(service.getApplicationContext(), Activity.RESULT_OK, data);
                        } catch (PendingIntent.CanceledException e) {
                            // The caller doesn't want the result? That's fine, just ignore
                        }
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }.start();
    }
	
	public static String[] buildEnvironment(boolean failSafe, String cwd) {
		
		if(cwd == null) cwd = TermuxService.ALPINE + "/root";
		
        final String termEnv = "TERM=xterm-256color";
        final String homeEnv = "HOME=" + TermuxService.ALPINE + "/root";
        final String prefixEnv = "PREFIX=" + TermuxService.PREFIX_PATH;
        final String androidRootEnv = "ANDROID_ROOT=" + System.getenv("ANDROID_ROOT");
        final String androidDataEnv = "ANDROID_DATA=" + System.getenv("ANDROID_DATA");
		
        final String externalStorageEnv = "EXTERNAL_STORAGE=" + System.getenv("EXTERNAL_STORAGE");
        if (failSafe) {
            final String pathEnv = "PATH=" + System.getenv("PATH");
            return new String[]{termEnv, homeEnv, prefixEnv, androidRootEnv, androidDataEnv, pathEnv, externalStorageEnv};
        } else {
            final String ldEnv = "LD_LIBRARY_PATH=" + TermuxService.PROOT_PATH;
            final String langEnv = "LANG=en_US.UTF-8";
            final String pathEnv = "PATH=" +TermuxService.PREFIX_PATH + "/sbin:" + TermuxService.PREFIX_PATH + "/sbin/applets";
            final String pwdEnv = "PWD=" + cwd;
            final String tmpdirEnv = "TMPDIR=" + TermuxService.PREFIX_PATH + "/tmp";
			
			final String prootTmpDir = "PROOT_TMP_DIR=" + TermuxService.FILES_PATH;
			
            return new String[]{termEnv, prootTmpDir, homeEnv, prefixEnv, ldEnv, langEnv, pathEnv, pwdEnv, androidRootEnv, androidDataEnv, externalStorageEnv, tmpdirEnv};
        }
    }

    public static int getPid(Process p) {
        try {
            Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            try {
                return f.getInt(p);
            } finally {
                f.setAccessible(false);
            }
        } catch (Throwable e) {
            return -1;
        }
    }
	
	static String[] setupProcessArgs(String fileToExecute, String[] args) {
        String interpreter = null;
        try {
            File file = new File(fileToExecute);
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[256];
                int bytesRead = in.read(buffer);
                if (bytesRead > 4) {
                    if (buffer[0] == 0x7F && buffer[1] == 'E' && buffer[2] == 'L' && buffer[3] == 'F') {
                        // Elf file, do nothing.
                    } else if (buffer[0] == '#' && buffer[1] == '!') {
                        // Try to parse shebang.
                        StringBuilder builder = new StringBuilder();
                        for (int i = 2; i < bytesRead; i++) {
                            char c = (char) buffer[i];
                            if (c == ' ' || c == '\n') {
                                if (builder.length() == 0) {
                                    // Skip whitespace after shebang.
                                } else {
                                    // End of shebang.
                                    String executable = builder.toString();
                                    if (executable.startsWith("/usr") || executable.startsWith("/bin")) {
                                        String[] parts = executable.split("/");
                                        String binary = parts[parts.length - 1];
                                        interpreter = TermuxService.PREFIX_PATH + "/bin/" + binary;
                                    }
                                    break;
                                }
                            } else {
                                builder.append(c);
                            }
                        }
                    } else {
                        interpreter = TermuxService.PREFIX_PATH + "/bin/sh";
                    }
                }
            }
        } catch (IOException e) {
            // Ignore.
        }

        List<String> result = new ArrayList<>();
        if (interpreter != null) result.add(interpreter);
        result.add(fileToExecute);
        if (args != null) Collections.addAll(result, args);
        return result.toArray(new String[0]);
    }
	

}
