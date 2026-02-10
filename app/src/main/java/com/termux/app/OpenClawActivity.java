package com.termux.app;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

public class OpenClawActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_openclaw);

        View openTerminal = findViewById(R.id.btn_open_terminal);
        View openWechatBot = findViewById(R.id.btn_open_wechat_bot);
        View openAccessibility = findViewById(R.id.btn_open_accessibility);

        openTerminal.setOnClickListener(v -> startActivity(new Intent(this, TermuxActivity.class)));
        openWechatBot.setOnClickListener(v -> {
            Intent intent = new Intent();
            intent.setClassName(this, "com.ws.wx_server.ui.MainActivity");
            startActivity(intent);
        });
        openAccessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    }
}
