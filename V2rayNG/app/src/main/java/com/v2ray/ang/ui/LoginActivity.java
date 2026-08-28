package com.v2ray.ang.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.v2ray.ang.R;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {
    EditText etTelegramUid;
    Button btnLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etTelegramUid = findViewById(R.id.etTelegramUid);
        btnLogin = findViewById(R.id.btnLogin);

        btnLogin.setOnClickListener(v -> {
            String uid = etTelegramUid.getText().toString().trim();
            if (!uid.isEmpty()) {
                checkAuthWithApi(uid);
            } else {
                Toast.makeText(this, "Please enter your Telegram UID", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void checkAuthWithApi(String uid) {
        String urlString = "http://157.85.105.50:5000/check-auth?uid=" + uid;

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject jsonResponse = new JSONObject(response.toString());
                String status = jsonResponse.getString("status");

                runOnUiThread(() -> {
                    if (status.equals("allowed")) {
                        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                        startActivity(intent);
                        finish();
                    } else {
                        Toast.makeText(this, "Access Denied or Expired!", Toast.LENGTH_LONG).show();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Connection Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }
}
