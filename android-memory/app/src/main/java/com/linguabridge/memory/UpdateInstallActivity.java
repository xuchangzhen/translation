package com.linguabridge.memory;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

public final class UpdateInstallActivity extends Activity {
    public static final String EXTRA_FILE_NAME = "updateFileName";
    private static final int REQUEST_UNKNOWN_SOURCES = 40;
    private String fileName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        if (!validFile().isFile()) {
            Toast.makeText(this, "更新文件已经失效，请等待应用重新下载", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        continueInstall();
    }

    private void continueInstall() {
        if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !getPackageManager().canRequestPackageInstalls()
        ) {
            Intent settings = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(settings, REQUEST_UNKNOWN_SOURCES);
            return;
        }
        launchPackageInstaller();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_UNKNOWN_SOURCES) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls()) {
            launchPackageInstaller();
        } else {
            Toast.makeText(this, "需要允许本应用安装更新", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void launchPackageInstaller() {
        File apk = validFile();
        if (!apk.isFile()) {
            finish();
            return;
        }
        Uri contentUri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".updates",
                apk
        );
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(contentUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(install);
        finish();
    }

    private File validFile() {
        if (fileName == null || !fileName.matches("^linguabridge-memory-[0-9]+\\.apk$")) {
            return new File(getCacheDir(), "invalid");
        }
        return new File(new File(getCacheDir(), "updates"), fileName);
    }
}
