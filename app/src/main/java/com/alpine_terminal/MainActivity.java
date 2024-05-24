package com.alpine_terminal;

import android.app.Activity;
import android.os.Bundle;

import com.termux.app.TermuxActivity;

public class MainActivity extends Activity implements TermuxActivity.TerminalCallback {
    public static TermuxActivity terminal;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        terminal = new TermuxActivity(this, this);
        /*
        terminal.addSession();
        terminal.removeSession();
        terminal.renameSession();
        terminal.swipeSession();
         */
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        terminal.onStop();
    }

    @Override
    public void onClose(int position) {
    }
}