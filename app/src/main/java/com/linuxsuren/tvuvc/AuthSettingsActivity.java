package com.linuxsuren.tvuvc;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.PasswordTransformationMethod;
import android.text.method.HideReturnsTransformationMethod;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

/**
 * 访问认证设置页（RTSP/HTTP/ONVIF 三服务共用单一账号）。
 * <p>
 * 密码支持查看（勾选「显示密码」在明文/圆点间切换）与修改（保存即持久化并
 * 即时注入运行中的服务，无需重启推流）。口令明文存应用私有
 * SharedPreferences：RTSP Digest 与 WS-PasswordDigest 的服务端校验都
 * 需要明文参与哈希，无法只存摘要。
 */
public class AuthSettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        android.content.SharedPreferences prefs = getSharedPreferences("tvuvc", MODE_PRIVATE);
        Switch authSwitch = findViewById(R.id.auth_switch);
        final EditText authUser = findViewById(R.id.auth_user);
        final EditText authPass = findViewById(R.id.auth_pass);
        CheckBox showPass = findViewById(R.id.auth_show_pass);
        Button save = findViewById(R.id.auth_save);

        authSwitch.setChecked(prefs.getBoolean("auth_enabled", false));
        authUser.setText(prefs.getString("auth_user", ""));
        authPass.setText(prefs.getString("auth_pass", ""));

        // 查看密码：明文/圆点切换（保存与否都可直接查看当前已存口令）
        showPass.setOnCheckedChangeListener((CompoundButton btn, boolean on) -> {
            int selStart = authPass.getSelectionStart();
            int selEnd = authPass.getSelectionEnd();
            authPass.setTransformationMethod(on
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
            authPass.setSelection(selStart, selEnd);
        });

        save.setOnClickListener(v -> {
            boolean enabled = authSwitch.isChecked();
            String user = authUser.getText().toString().trim();
            String pass = authPass.getText().toString();
            prefs.edit()
                    .putBoolean("auth_enabled", enabled)
                    .putString("auth_user", user)
                    .putString("auth_pass", pass)
                    .apply();
            // 即时注入运行中的服务：下一个请求即按新凭证校验
            Auth.configure(enabled, user, pass);
            String tip = Auth.isEnabled()
                    ? getString(R.string.auth_saved_on, Auth.getUser())
                    : getString(R.string.auth_saved_off);
            Toast.makeText(this, tip, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
    }
}
